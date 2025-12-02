package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class JsonEyeTrackerMapper {

    private final Project project;

    public JsonEyeTrackerMapper(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Call this for each gaze sample (normalized Tobii coords, 0..1).
     * Maps to PSI element / word under gaze.
     */
    public void onGazeSample(double leftX, double leftY,
                             double rightX, double rightY) {
        if (Double.isNaN(leftX) || Double.isNaN(leftY) ||
                Double.isNaN(rightX) || Double.isNaN(rightY)) {
            return;
        }

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            System.out.println("[AI4SE] No active editor for gaze.");
            return;
        }

        double avgX = (leftX + rightX) / 2.0;
        double avgY = (leftY + rightY) / 2.0;

        // 🔹 Reuse your calibrated GazeMapper
        Point screenPoint = GazeMapper.gazeToScreenPoint(avgX, avgY);
        if (screenPoint == null) {
            System.out.println("[AI4SE] Gaze off-screen / bad monitor index.");
            return;
        }

        JComponent content = editor.getContentComponent();
        Point editorOnScreen;
        try {
            editorOnScreen = content.getLocationOnScreen();
        } catch (IllegalComponentStateException e) {
            System.out.println("[AI4SE] Editor not visible.");
            return;
        }

        int localX = screenPoint.x - editorOnScreen.x;
        int localY = screenPoint.y - editorOnScreen.y;

        System.out.printf(
                "[AI4SE][MAP] avg=(%.3f, %.3f) | screen=(%d,%d) | editorTL=(%d,%d) | local=(%d,%d) | size=(%d,%d)%n",
                avgX, avgY,
                screenPoint.x, screenPoint.y,
                editorOnScreen.x, editorOnScreen.y,
                localX, localY,
                content.getWidth(), content.getHeight()
        );

        // 🔹 Simple bounds check: same as EditorGazeMapper
        if (localX < 0 || localY < 0 ||
                localX >= content.getWidth() || localY >= content.getHeight()) {
            System.out.println("[AI4SE] Gaze is outside editor bounds.");
            return;
        }

        Point relativePoint = new Point(localX, localY);

        ApplicationManager.getApplication().invokeLater(() -> {
            PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
            PsiFile psiFile = psiDocumentManager.getPsiFile(editor.getDocument());
            if (psiFile == null) return;

            LogicalPosition logicalPosition = editor.xyToLogicalPosition(relativePoint);
            int offset = editor.logicalPositionToOffset(logicalPosition);
            PsiElement psiElement = psiFile.findElementAt(offset);

            VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
            String path = (vf != null) ? vf.getPath() : "<no-file>";

            String token = (psiElement != null && psiElement.getTextLength() > 0)
                    ? psiElement.getText()
                    : "";

            System.out.println(
                    "[AI4SE][GAZE→TOKEN] file=" + path +
                            " | line=" + logicalPosition.line +
                            " | col=" + logicalPosition.column +
                            " | offset=" + offset +
                            " | token='" + shorten(token) + "'" +
                            " | screen=(" + screenPoint.x + "," + screenPoint.y + ")"
            );
        });
    }

    private String shorten(String s) {
        if (s == null) return "";
        s = s.replace("\n", "\\n").replace("\t", "\\t");
        if (s.length() > 30) {
            return s.substring(0, 27) + "...";
        }
        return s;
    }
}
