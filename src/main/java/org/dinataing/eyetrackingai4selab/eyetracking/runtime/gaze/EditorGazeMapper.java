package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.util.Computable;

import javax.swing.*;
import java.awt.*;

public class EditorGazeMapper {

    private static RangeHighlighter currentHighlighter;

    /**
     * Map averaged normalized gaze (0..1 on calibrated display)
     * to a character in the currently selected editor.
     *
     * @return GazeHit if successful, null otherwise.
     */
    public static GazeHit mapGazeToEditor(Project project,
                                          double gx,
                                          double gy) {
        if (project == null) return null;
        if (Double.isNaN(gx) || Double.isNaN(gy)) return null;

        // 🔒 Everything that touches editor / PSI is now in a read action
        return ApplicationManager.getApplication().runReadAction(
                (Computable<GazeHit>) () -> {

                    Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                    if (editor == null) {
                        System.out.println("[AI4SE] No active editor; ignoring gaze.");
                        return null;
                    }

                    JComponent content = editor.getContentComponent();

                    // 1) normalized → screen coords
                    Point screenPoint = GazeMapper.gazeToScreenPoint(gx, gy);
                    if (screenPoint == null) {
                        System.out.println("[AI4SE] Gaze is off-screen or monitor index wrong.");
                        return null;
                    }

                    // 2) editor origin on screen
                    Point editorScreenPoint;
                    try {
                        editorScreenPoint = content.getLocationOnScreen();
                    } catch (IllegalComponentStateException e) {
                        System.out.println("[AI4SE] Editor not visible on screen.");
                        return null;
                    }

                    int localX = screenPoint.x - editorScreenPoint.x;
                    int localY = screenPoint.y - editorScreenPoint.y;
                    Point localPoint = new Point(localX, localY);

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

                    // 3) ensure we are inside the editor AND visible text area
                    if (localX < visibleArea.x || localY < visibleArea.y ||
                            localX > visibleArea.x + visibleArea.width ||
                            localY > visibleArea.y + visibleArea.height) {
                        System.out.println("[AI4SE] Gaze out of text editor visible area.");
                        return null;
                    }

                    // 4) local -> logical position
                    LogicalPosition logicalPos = editor.xyToLogicalPosition(localPoint);
                    int offset = editor.logicalPositionToOffset(logicalPos);
                    CharSequence chars = editor.getDocument().getCharsSequence();

                    if (offset < 0 || offset >= chars.length()) {
                        System.out.println("[AI4SE] Offset out of document range: " + offset);
                        return null;
                    }

                    char ch = chars.charAt(offset);

                    // simple “word” extraction around offset
                    int start = offset;
                    int end = offset;
                    while (start > 0 && Character.isJavaIdentifierPart(chars.charAt(start - 1))) {
                        start--;
                    }
                    while (end < chars.length() && Character.isJavaIdentifierPart(chars.charAt(end))) {
                        end++;
                    }
                    String word = chars.subSequence(start, end).toString();

                    System.out.println(
                            "[AI4SE][GAZE] char='" + ch + "' word=\"" + word + "\" offset=" + offset +
                                    " (line " + logicalPos.line + ", col " + logicalPos.column + ")"
                    );

                    // 5) PSI lookup
                    PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                    PsiElement psiElement = null;
                    if (psiFile != null) {
                        psiElement = psiFile.findElementAt(offset);
                        if (psiElement != null) {
                            String token = psiElement.getText();
                            String type = psiElement.getNode().getElementType().toString();
                            System.out.println("[AI4SE][PSI] token=\"" + token + "\" type=" + type);

                            // Upward AST like your logs
                            PsiElement parent = psiElement;
                            int level = 0;
                            while (parent != null) {
                                if (parent instanceof PsiFile) break;
                                LogicalPosition startPos =
                                        editor.offsetToLogicalPosition(parent.getTextRange().getStartOffset());
                                LogicalPosition endPos =
                                        editor.offsetToLogicalPosition(parent.getTextRange().getEndOffset());

                                System.out.printf(
                                        "[AI4SE][PSI-LEVEL %d] %s | start=%d:%d end=%d:%d%n",
                                        level,
                                        parent,
                                        startPos.line, startPos.column,
                                        endPos.line, endPos.column
                                );

                                parent = parent.getParent();
                                level++;
                            }
                        }
                    }

                    // 6) highlight
                    highlightChar(editor, offset);

                    // 7) return hit object
                    return new GazeHit(
                            gx,
                            gy,
                            screenPoint,
                            editorScreenPoint,
                            localPoint,
                            offset,
                            logicalPos,
                            ch,
                            word,
                            psiElement
                    );
                }
        );
    }

    private static void highlightChar(Editor editor, int offset) {
        if (currentHighlighter != null) {
            currentHighlighter.dispose();
            currentHighlighter = null;
        }

        MarkupModel markupModel = editor.getMarkupModel();
        currentHighlighter = markupModel.addRangeHighlighter(
                offset,
                offset + 1,
                HighlighterLayer.SELECTION - 1,
                new TextAttributes(null, null, Color.RED, EffectType.BOXED, Font.BOLD),
                HighlighterTargetArea.EXACT_RANGE
        );
    }
}
