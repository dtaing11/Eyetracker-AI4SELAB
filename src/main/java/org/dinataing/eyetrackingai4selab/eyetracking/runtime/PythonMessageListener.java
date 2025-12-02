package org.dinataing.eyetrackingai4selab.eyetracking.runtime;

import org.json.JSONObject;

public interface PythonMessageListener {

    /** Called when a gaze frame is received from Python. */
    void onGaze(double x, double y, double timestamp, JSONObject rawJson);

    /** Called for non-gaze status messages (device_detected, etc.). */
    void onStatus(String status, JSONObject rawJson);

    /** Called for error messages emitted by Python. */
    void onError(String errorType, JSONObject rawJson);
}
