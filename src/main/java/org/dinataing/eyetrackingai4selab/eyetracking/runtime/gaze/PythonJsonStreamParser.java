package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.diagnostic.Logger;
import org.json.JSONObject;

public class PythonJsonStreamParser {

    private static final Logger LOG = Logger.getInstance(PythonJsonStreamParser.class);

    private final PythonMessageListener listener;

    public PythonJsonStreamParser(PythonMessageListener listener) {
        this.listener = listener;

    }

    /**
     * Feed a single stdout line from the Python process into this method.
     * It will:
     *  - ignore non-JSON lines
     *  - parse JSON objects
     *  - dispatch based on "type": "gaze" | "status" | "error"
     */
    public void handleLine(String rawLine) {
        if (rawLine == null) return;
        String line = rawLine.trim();
        if (line.isEmpty()) return;

        // Only treat lines that look like bare JSON objects
        if (!line.startsWith("{") || !line.endsWith("}")) {
            LOG.info("[AI4SE Python] " + line);
            return;
        }

        try {
            JSONObject obj = new JSONObject(line);
            String type = obj.optString("type", "");

            switch (type) {
                case "gaze": {
                    // Left eye data
                    double leftX  = obj.optDouble("leftX", Double.NaN);
                    double leftY  = obj.optDouble("leftY", Double.NaN);
                    int leftValidity  = obj.optInt("leftValidity", -1);
                    double leftPupil  = obj.optDouble("leftPupil", Double.NaN);
                    int leftPupilValidity = obj.optInt("leftPupilValidity", -1);

                    // Right eye data
                    double rightX = obj.optDouble("rightX", Double.NaN);
                    double rightY = obj.optDouble("rightY", Double.NaN);
                    int rightValidity = obj.optInt("rightValidity", -1);
                    double rightPupil = obj.optDouble("rightPupil", Double.NaN);
                    int rightPupilValidity = obj.optInt("rightPupilValidity", -1);

                    double ts = obj.optDouble("timestamp", Double.NaN);

                    // Determine which eyes are valid
                    // Tobii validity: 1 = valid, 0 = invalid (you can adjust if needed)
                    boolean leftValid  = leftValidity == 1 && !Double.isNaN(leftX) && !Double.isNaN(leftY);
                    boolean rightValid = rightValidity == 1 && !Double.isNaN(rightX) && !Double.isNaN(rightY);

                    double x;
                    double y;

                    if (leftValid && rightValid) {
                        x = (leftX + rightX) / 2.0;
                        y = (leftY + rightY) / 2.0;
                    } else if (leftValid) {
                        x = leftX;
                        y = leftY;
                    } else if (rightValid) {
                        x = rightX;
                        y = rightY;
                    } else {
                        x = Double.NaN;
                        y = Double.NaN;
                    }

                    LOG.info(String.format(
                            "[AI4SE] Gaze ts=%f | L=(%.3f, %.3f, v=%d, pupil=%.3f, pv=%d) R=(%.3f, %.3f, v=%d, pupil=%.3f, pv=%d) -> avg=(%.3f, %.3f)",
                            ts,
                            leftX, leftY, leftValidity, leftPupil, leftPupilValidity,
                            rightX, rightY, rightValidity, rightPupil, rightPupilValidity,
                            x, y
                    ));

                    // Single averaged gaze point + full raw JSON
                    listener.onGaze(x, y, ts, obj);
                    break;
                }

                case "status": {
                    String status = obj.optString("status", "unknown");
                    listener.onStatus(status, obj);
                    break;
                }

                case "error": {
                    String errorType = obj.optString("errorType", "unknown_error");
                    listener.onError(errorType, obj);
                    break;
                }

                default: {
                    LOG.info("[AI4SE Python] Unknown JSON type: " + type + " -> " + obj);
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse JSON line from Python: " + line, e);
        }
    }
}
