package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import java.awt.*;

public class GazeMapper {

    private static final int MONITOR_INDEX = 0;

    // 🔧 tweak these until localX/localY are positive when you look at the editor
    private static double CALIBRATION_OFFSET_X = 80.0;
    private static double CALIBRATION_OFFSET_Y = 80.0;

    public static Point gazeToScreenPoint(double gx, double gy) {
        if (Double.isNaN(gx) || Double.isNaN(gy)) return null;

        GraphicsDevice[] devices = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getScreenDevices();

        if (MONITOR_INDEX < 0 || MONITOR_INDEX >= devices.length) {
            return null;
        }

        Rectangle bounds = devices[MONITOR_INDEX]
                .getDefaultConfiguration()
                .getBounds();

        int px = (int) Math.round(bounds.x + gx * bounds.width  + CALIBRATION_OFFSET_X);
        int py = (int) Math.round(bounds.y + gy * bounds.height + CALIBRATION_OFFSET_Y);

        return new Point(px, py);
    }

    // optional helper so you can adjust from somewhere else if you want
    public static void setCalibrationOffsets(double dx, double dy) {
        CALIBRATION_OFFSET_X = dx;
        CALIBRATION_OFFSET_Y = dy;
    }
}
