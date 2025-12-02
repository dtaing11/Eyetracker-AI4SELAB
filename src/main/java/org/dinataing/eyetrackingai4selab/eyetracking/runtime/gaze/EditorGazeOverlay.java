package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.editor.Editor;

import javax.swing.*;
import java.awt.*;
import java.util.WeakHashMap;

public class EditorGazeOverlay {

    private static class EyePoint {
        double x, y;
        boolean valid;
    }

    private static final WeakHashMap<Editor, OverlayPanel> overlays = new WeakHashMap<>();

    public static void updateOverlay(Editor editor,
                                     double leftLocalX, double leftLocalY, boolean leftValid,
                                     double rightLocalX, double rightLocalY, boolean rightValid) {

        OverlayPanel panel = overlays.computeIfAbsent(editor, e -> {
            OverlayPanel p = new OverlayPanel();
            JComponent content = editor.getContentComponent();
            content.setLayout(null);
            content.add(p);
            p.setBounds(0, 0, content.getWidth(), content.getHeight());

            content.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent evt) {
                    p.setBounds(0, 0, content.getWidth(), content.getHeight());
                }
            });

            return p;
        });

        panel.leftEye.x = leftLocalX;
        panel.leftEye.y = leftLocalY;
        panel.leftEye.valid = leftValid;

        panel.rightEye.x = rightLocalX;
        panel.rightEye.y = rightLocalY;
        panel.rightEye.valid = rightValid;

        panel.repaint();
    }

    private static class OverlayPanel extends JComponent {
        EyePoint leftEye = new EyePoint();
        EyePoint rightEye = new EyePoint();

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int radius = 8;

            // LEFT EYE – BLUE
            if (leftEye.valid) {
                g2.setColor(new Color(0, 122, 255)); // blue
                g2.fillOval((int) leftEye.x - radius, (int) leftEye.y - radius, radius * 2, radius * 2);
            }

            // RIGHT EYE – RED
            if (rightEye.valid) {
                g2.setColor(new Color(255, 50, 50)); // red
                g2.fillOval((int) rightEye.x - radius, (int) rightEye.y - radius, radius * 2, radius * 2);
            }
        }
    }
}
