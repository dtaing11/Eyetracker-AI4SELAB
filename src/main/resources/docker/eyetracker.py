import json
import time
import tobii_research as tr



##---------------------Helper Function-----------------------------------##
def gaze_data_callback(gaze_data):
    timestamp = round(time.time() * 1000)

    data = {
        "type": "gaze",
        "timestamp": timestamp,

        # Left eye
        "leftX": gaze_data["left_gaze_point_on_display_area"][0],
        "leftY": gaze_data["left_gaze_point_on_display_area"][1],
        "leftValidity": gaze_data["left_gaze_point_validity"],
        "leftPupil": gaze_data["left_pupil_diameter"],
        "leftPupilValidity": gaze_data["left_pupil_validity"],

        # Right eye
        "rightX": gaze_data["right_gaze_point_on_display_area"][0],
        "rightY": gaze_data["right_gaze_point_on_display_area"][1],
        "rightValidity": gaze_data["right_gaze_point_validity"],
        "rightPupil": gaze_data["right_pupil_diameter"],
        "rightPupilValidity": gaze_data["right_pupil_validity"],
    }

    send(data)



def send(obj):
    print(json.dumps(obj), flush=True)
##---------------------End of Helper Function-----------------------------------##


# Detect devices
eyetrackers = tr.find_all_eyetrackers()

if len(eyetrackers) == 0:
    send({
        "type": "error",
        "errorType": "no_device",
        "message": "Eye Tracker is not detected"
    })
    exit()

my_eyetracker = eyetrackers[0]

send({
    "type": "status",
    "status": "device_detected",
    "model": my_eyetracker.model,
    "deviceName": my_eyetracker.device_name
})

my_eyetracker.subscribe_to(tr.EYETRACKER_GAZE_DATA, gaze_data_callback, as_dictionary=True)

try:
    while True:
        time.sleep(0.000001)
except KeyboardInterrupt:
    my_eyetracker.unsubscribe_from(tr.EYETRACKER_GAZE_DATA)
    print("Stopped.")
