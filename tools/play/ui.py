"""Drive the phone by on-screen text: tap, scroll, capture. Used to make the Play Store assets.

Set ANDROID_SERIAL when more than one device is attached.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = ["adb"]


def sh(*a, out=False):
    r = subprocess.run(ADB + list(a), capture_output=True)
    return r.stdout if out else None


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return ET.fromstring(sh("exec-out", "cat", "/sdcard/ui.xml", out=True))


def nodes(root):
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            yield n, ((x1 + x2) // 2, (y1 + y2) // 2), (x1, y1, x2, y2)


def find(text, exact=True, root=None):
    """The centre and bounds of the first node whose text or description is (or contains) text."""
    root = root or dump()
    for n, c, b in nodes(root):
        vals = [n.get("text") or "", n.get("content-desc") or ""]
        if (exact and text in vals) or (not exact and text.lower() in "|".join(vals).lower()):
            return c, b
    return None


def tap(text, exact=True, tries=1):
    for _ in range(tries):
        hit = find(text, exact)
        if hit:
            sh("shell", "input", "tap", str(hit[0][0]), str(hit[0][1]))
            time.sleep(1.2)
            return True
        time.sleep(0.8)
    return False


def swipe(y1, y2, ms=400):
    sh("shell", "input", "swipe", "640", str(y1), "640", str(y2), str(ms))
    time.sleep(1.0)


def scroll_to(text, exact=True, limit=8):
    for _ in range(limit):
        if find(text, exact):
            return True
        swipe(2000, 900)
    return False


def shot(path):
    with open(path, "wb") as f:
        f.write(sh("exec-out", "screencap", "-p", out=True))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    print({"tap": lambda: tap(args[0]), "find": lambda: find(args[0], False), "shot": lambda: shot(args[0])}[cmd]())
