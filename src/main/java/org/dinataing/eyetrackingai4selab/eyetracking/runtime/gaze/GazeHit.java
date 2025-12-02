package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.psi.PsiElement;

import java.awt.*;

public class GazeHit {

    // Raw normalized gaze (0..1)
    public final double gx;
    public final double gy;

    // Screen & editor coordinates
    public final Point screenPoint;
    public final Point editorTopLeft;
    public final Point localPoint;

    // Editor / document info
    public final int offset;
    public final LogicalPosition logicalPosition;
    public final char ch;
    public final String word;

    // PSI
    public final PsiElement psiElement;

    public GazeHit(
            double gx,
            double gy,
            Point screenPoint,
            Point editorTopLeft,
            Point localPoint,
            int offset,
            LogicalPosition logicalPosition,
            char ch,
            String word,
            PsiElement psiElement
    ) {

        this.gx = gx;
        this.gy = gy;
        this.screenPoint = screenPoint;
        this.editorTopLeft = editorTopLeft;
        this.localPoint = localPoint;
        this.offset = offset;
        this.logicalPosition = logicalPosition;
        this.ch = ch;
        this.word = word;
        this.psiElement = psiElement;
    }
}
