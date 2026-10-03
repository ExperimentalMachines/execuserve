"""Record the foreground-service declaration video: start hosting, then use it from Chrome while
ExecuServe is in the background.

    tools/execuserve --model qwen3-1.7b-8da4w-gptq-4k && tools/execuserve stop
    python3 tools/play/record.py <the key tools/execuserve printed>
    adb pull /sdcard/fgs.mp4 build/play/video/fgs-raw.mp4 && python3 tools/play/compose.py video

Start on the console's Hosting tab with the server stopped. The key is typed into the page's
password field, so it never shows on screen.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ui  # noqa: E402

KEY = sys.argv[1]
URL = "http://127.0.0.1:8080/models/qwen3-1.7b-8da4w-gptq-4k/"
marks = []
rec = subprocess.Popen(["adb", "shell", "screenrecord", "--bit-rate", "12000000", "--time-limit", "170", "/sdcard/fgs.mp4"])
t0 = time.time(); time.sleep(1.5)
def mark(name): marks.append((name, round(time.time() - t0, 2))); print(name, marks[-1][1], flush=True)
mark("app-stopped"); time.sleep(2.5)
ui.tap("Start"); mark("tap-start")
t = time.time()
while time.time() - t < 30 and not ui.find("Serving"): time.sleep(0.5)
mark("serving"); time.sleep(3)
ui.sh("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", URL, "com.android.chrome"); mark("chrome")
time.sleep(3.5)
ui.tap("Connect"); time.sleep(1)
ui.sh("shell", "input", "tap", "639", "1460"); time.sleep(0.8)
mark("key")
ui.sh("shell", "input", "text", KEY); time.sleep(0.8)
ui.sh("shell", "input", "keyevent", "KEYCODE_ENTER"); time.sleep(2)
mark("connected")
ui.sh("shell", "input", "tap", "600", "2090"); time.sleep(0.8)
ui.sh("shell", "input", "text", "Write%sa%shaiku%sabout%sa%sphone%sthat%sserves%sAI"); time.sleep(0.8)
hit = ui.find("Send message"); ui.sh("shell", "input", "tap", str(hit[0][0]), str(hit[0][1]))
mark("sent")
time.sleep(1.2); ui.sh("shell", "input", "keyevent", "KEYCODE_BACK")  # put the keyboard away to show the reply
t = time.time()
while time.time() - t < 90:
    time.sleep(1)
    if ui.find("elapsed", exact=False): break
mark("replied"); time.sleep(3)
ui.sh("shell", "am", "start", "-n", "org.experimentalmachines.execuserve/.app.ui.MainActivity"); time.sleep(1.5)
ui.tap("Activity", tries=6); mark("activity"); time.sleep(5)
mark("end")
subprocess.run(["adb", "shell", "pkill", "-INT", "screenrecord"]); rec.wait(); time.sleep(2)
Path("build/play").mkdir(parents=True, exist_ok=True)
json.dump(marks, open("build/play/marks.json", "w"))
