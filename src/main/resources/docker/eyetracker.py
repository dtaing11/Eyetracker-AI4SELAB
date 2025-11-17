import json
import time
import random
import tobii_research as tr

# -----------------------------------------
# Helper to safely print JSON
# -----------------------------------------
def send(obj):
    print(json.dumps(obj), flush=True)

# -----------------------------------------
# Detect devices
# -----------------------------------------
found_eyetrackers = tr.find_all_eyetrackers()

if len(found_eyetrackers) > 0:
    my_eyetracker = found_eyetrackers[0]

    # STATUS: device successfully detected
    send({
        "type": "status",
        "status": "device_detected",
        "model": my_eyetracker.model,
        "deviceName": my_eyetracker.device_name
    })

    # -----------------------------------------
    # DATA STREAM LOOP
    # (right now fake data; Tobii data can replace later)
    # -----------------------------------------
    while True:
        try:
            # Fake sample; swap with Tobii gaze data later
            data = {
                "type": "gaze",
                "timestamp": time.time(),
                "eyeX": random.random(),
                "eyeY": random.random()
            }
            send(data)

        except Exception as e:
            # ERROR inside gaze loop
            send({
                "type": "error",
                "errorType": "runtime_error",
                "message": str(e)
            })

        time.sleep(1)

else:
    # STATUS: device NOT detected
    data = {
        "type": "gaze",
        "timestamp": time.time(),
        "eyeX": random.random(),
        "eyeY": random.random()
    }
    send(data)
    send({
        "type": "error",
        "errorType": "no_device",
        "message": "Eye Tracker is not detected"
    })
