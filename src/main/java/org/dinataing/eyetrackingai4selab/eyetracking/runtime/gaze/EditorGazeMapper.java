package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Maps normalized gaze (0..1) coming from the Tobii/Docker pipeline
 * onto the active editor, and tries to infer:
 *  - which character
 *  - which identifier/word
 *  - which PSI token & its AST context
 *
 * This is a "runtime" version of the EyeTracker logic from the reference project.
 */
public final class EditorGazeMapper {

    private static RangeHighlighter currentHighlighter;

    // Small tolerance so slightly off values (gutter, tiny calibration error)
    // are still treated as "inside" the editor.
    private static final int MARGIN_X = 40;
    private static final int MARGIN_Y = 40;

    private EditorGazeMapper() {}

    /**
     * Called from DockerManager when a new averaged gaze point arrives.
     *
     * @param project IntelliJ project
     * @param gx      normalized x in [0,1] (display space)
     * @param gy      normalized y in [0,1] (display space)
     */
    public static void mapGazeToEditor(Project project, double gx, double gy) {
        if (project == null) return;
        if (Double.isNaN(gx) || Double.isNaN(gy)) return;

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            System.out.println("[AI4SE] No active editor; ignoring gaze.");
            return;
        }

        JComponent content = editor.getContentComponent();

        // 1) normalized → screen coords (same idea as EyeTracker: normalized * screenWidth/Height)
        Point screenPoint = GazeMapper.gazeToScreenPoint(gx, gy);
        if (screenPoint == null) {
            System.out.println("[AI4SE] Gaze is off-screen or monitor index wrong.");
            return;
        }

        // 2) editor origin on screen
        Point editorScreenPoint;
        try {
            editorScreenPoint = content.getLocationOnScreen();
        } catch (IllegalComponentStateException e) {
            System.out.println("[AI4SE] Editor not visible on screen.");
            return;
        }

        int localX = screenPoint.x - editorScreenPoint.x;
        int localY = screenPoint.y - editorScreenPoint.y;

        Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();

        System.out.printf(
                "[AI4SE][MAP] avg=(%.3f, %.3f) | screen=(%d,%d) | editorTL=(%d,%d) | local=(%d,%d) | visibleArea=(%d,%d,%d,%d) | editorSize=(%d,%d)%n",
                gx, gy,
                screenPoint.x, screenPoint.y,
                editorScreenPoint.x, editorScreenPoint.y,
                localX, localY,
                visibleArea.x, visibleArea.y, visibleArea.width, visibleArea.height,
                content.getWidth(), content.getHeight()
        );

        // 3) Filter using visible area + margin (EyeTracker-style)
        // local coords are in content-space; visibleArea.x/y are scroll offsets in the same space.

// 3) Filter using visible area (NO margin on X to avoid snapping to col 0)
        boolean outside =
                (localX < visibleArea.x) ||
                        (localY < visibleArea.y - MARGIN_Y) ||  // keep small Y margin if you want
                        (localX > visibleArea.x + visibleArea.width) ||
                        (localY > visibleArea.y + visibleArea.height + MARGIN_Y);

        if (outside) {
            System.out.println("[AI4SE] Gaze out of text editor visible area.");
            return;
        }

        // DO NOT clamp localX/localY to visibleArea.x / visibleArea.y.
        // Just pass the raw localPoint to xyToLogicalPosition.
        Point localPoint = new Point(localX, localY);




        // Everything below must be on EDT because of PSI / editor interaction
        ApplicationManager.getApplication().invokeLater(() -> mapPointToPsi(project, editor, localPoint, gx, gy, screenPoint));
    }

    /**
     * Runs on EDT: maps the editor-local point to LogicalPosition, character, word, and PSI element.
     */
    private static void mapPointToPsi(Project project,
                                      Editor editor,
                                      Point localPoint,
                                      double gx, double gy,
                                      Point screenPoint) {

        // 4) local -> logical position (EyeTracker style: editor.xyToLogicalPosition(relativePoint))
        LogicalPosition logicalPos = editor.xyToLogicalPosition(localPoint);
        int offset = editor.logicalPositionToOffset(logicalPos);

        CharSequence chars = editor.getDocument().getCharsSequence();
        if (offset < 0 || offset >= chars.length()) {
            System.out.println("[AI4SE] Offset out of document range: " + offset);
            return;
        }

        // 5) Expand to identifier "word" (similar idea to EyeTracker's token/AST)
        int start = offset;
        int end = offset;

        while (start > 0 && Character.isJavaIdentifierPart(chars.charAt(start - 1))) {
            start--;
        }
        while (end < chars.length() && Character.isJavaIdentifierPart(chars.charAt(end))) {
            end++;
        }

        String word = chars.subSequence(start, end).toString();
        char ch = chars.charAt(offset);

        System.out.println(
                "[AI4SE][GAZE] char='" + ch + "'" +
                        " word=\"" + word + "\"" +
                        " offset=" + offset +
                        " (line " + logicalPos.line + ", col " + logicalPos.column + ")"
        );

        // 6) Highlight the word for visual feedback (like selecting token)
        highlightRange(editor, start, end);

        // 7) PSI mapping: token + AST upward traversal (EyeTracker.getASTStructureElement logic)
        PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
        PsiFile psiFile = psiDocumentManager.getPsiFile(editor.getDocument());
        if (psiFile == null) {
            System.out.println("[AI4SE][PSI] No PsiFile for current document.");
            return;
        }

        PsiElement leaf = psiFile.findElementAt(offset);
        if (leaf == null) {
            System.out.println("[AI4SE][PSI] No PsiElement at offset " + offset);
            return;
        }

        String tokenText = leaf.getText();
        String tokenType = leaf.getNode() != null ? leaf.getNode().getElementType().toString() : "UNKNOWN";

        System.out.println("[AI4SE][PSI] token=\"" + tokenText + "\" type=" + tokenType);

        // Walk up parents like EyeTracker.getASTStructureElement
        PsiElement parent = leaf;
        int levelIdx = 0;
        while (parent != null && !(parent instanceof PsiFile)) {
            int startOffset = parent.getTextRange().getStartOffset();
            int endOffset = parent.getTextRange().getEndOffset();
            LogicalPosition startLogical = editor.offsetToLogicalPosition(startOffset);
            LogicalPosition endLogical = editor.offsetToLogicalPosition(endOffset);

            System.out.printf(
                    "[AI4SE][PSI-LEVEL %d] %s | start=%d:%d end=%d:%d%n",
                    levelIdx,
                    parent.toString(),
                    startLogical.line, startLogical.column,
                    endLogical.line, endLogical.column
            );

            parent = parent.getParent();
            levelIdx++;
        }

        // You now have:
        //  - gx, gy (normalized gaze)
        //  - screenPoint (pixel)
        //  - localPoint (inside editor)
        //  - logicalPos (line/col)
        //  - offset, char, word
        //  - PSI token + AST chain
        //
        // If you want, you can dispatch this to your own listener / logger here
        // instead of just printing to stdout.
    }

    // --- Highlighter helpers ---

    private static void highlightRange(@NotNull Editor editor, int start, int end) {
        MarkupModel markup = editor.getMarkupModel();

        if (currentHighlighter != null) {
            markup.removeHighlighter(currentHighlighter);
            currentHighlighter = null;
        }

        if (start >= end) return;

        TextAttributes attrs = new TextAttributes();
        // Soft yellow, transparent, similar to a selection overlay
        attrs.setBackgroundColor(new Color(255, 255, 0, 80));

        currentHighlighter = markup.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.SELECTION - 1,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
        );
    }
}
