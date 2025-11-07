
import json, time, random
while True:
    data = {"timestamp": time.time(), "eyeX": random.random(), "eyeY": random.random()}
    print(json.dumps(data), flush=True)
    time.sleep(1)