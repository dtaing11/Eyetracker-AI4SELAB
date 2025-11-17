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
                    double x = obj.optDouble("eyeX", Double.NaN);
                    double y = obj.optDouble("eyeY", Double.NaN);
                    double ts = obj.optDouble("timestamp", Double.NaN);
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
