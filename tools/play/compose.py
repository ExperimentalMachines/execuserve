"""Compose the Play Store screenshots and the captioned foreground-service video.

    python3 tools/play/compose.py [stills] [tablets] [video]

- stills: phone screenshots, 1080x1920, from build/play/raw (1280x2772 captures).
- tablets: 7- and 10-inch screenshots at 16:9, from build/play/tablet7 and tablet10
  (1920x1080 and 2560x1440 captures from the es-tablet7 and es-tablet10 emulators).
- video: from build/play/video/fgs-raw.mp4.

The finished files land in build/play/store and build/play/video; docs/play keeps the ones
published. Captions are drawn with Pillow, since Homebrew's ffmpeg has no drawtext.
"""
import subprocess
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
PLAY = ROOT / "build/play"
RAW, STORE, VIDEO = PLAY / "raw", PLAY / "store", PLAY / "video"
FONT = str(ROOT / "android/app/src/main/res/font/ibm_plex_sans.ttf")
INK, PAPER, EMBER, MUTED = "#262626", "#F3F4F7", "#EE4C2C", "#B4B6BC"
W, H = 1080, 1920
BAND = 330            # caption band above the screen
SCREEN_W = 770        # the phone screen, scaled: 330 + 1588 fits 1920
CROP_BOTTOM = 2640    # drop the system navigation bar from 1280x2772 captures
RADIUS = 44

def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f

def wrap(draw, text, f, width):
    words, lines, line = text.split(), [], ""
    for w in words:
        trial = f"{line} {w}".strip()
        if draw.textlength(trial, font=f) <= width or not line:
            line = trial
        else:
            lines.append(line); line = w
    return lines + [line]

def caption(title, sub, size=(W, BAND), bg=INK, scale=1.0):
    """The caption band: an ember rule, the headline, and one supporting line."""
    img = Image.new("RGBA", size, bg)
    d = ImageDraw.Draw(img)
    width, mid = size[0], size[0] // 2
    px = lambda v: round(v * scale)  # noqa: E731
    big, small = font(px(64), b"SemiBold"), font(px(34), b"Regular")
    y = px(78)
    d.rounded_rectangle((mid - px(36), y, mid + px(36), y + px(8)), px(4), fill=EMBER)
    y += px(34)
    for line in wrap(d, title, big, width - px(140)):
        d.text((mid, y), line, font=big, fill=PAPER, anchor="ma"); y += px(76)
    y += px(8)
    for line in wrap(d, sub, small, width - px(160)):
        d.text((mid, y), line, font=small, fill=MUTED, anchor="ma"); y += px(46)
    return img

def screen_size():
    h = round(CROP_BOTTOM * SCREEN_W / 1280)
    return SCREEN_W, h

def corners_mask(size, radius=RADIUS):
    """Opaque ink everywhere but a rectangle with rounded top corners: laid over the screen."""
    w, h = size
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w, h + radius), radius, fill=255)
    over = Image.new("RGBA", size, INK)
    over.putalpha(m.point(lambda a: 255 - a))
    return over

def still(raw, out, title, sub):
    shot = Image.open(RAW / raw).convert("RGB").crop((0, 0, 1280, CROP_BOTTOM))
    sw, sh = screen_size()
    shot = shot.resize((sw, sh), Image.LANCZOS).convert("RGBA")
    canvas = Image.new("RGBA", (W, H), INK)
    canvas.alpha_composite(caption(title, sub), (0, 0))
    x, y = (W - sw) // 2, BAND
    shot.alpha_composite(corners_mask((sw, sh)))
    canvas.alpha_composite(shot.crop((0, 0, sw, H - y)), (x, y))
    canvas.convert("RGB").save(STORE / out, optimize=True)

SHOTS = [
    ("01-hosting.png", "01-hosting.png", "Your phone is the server", "OpenAI- and Anthropic-compatible APIs, running on-device with ExecuTorch"),
    ("02-chat.png", "02-chat.png", "Try any model in Chat", "Prefill and decode speeds on every reply"),
    ("04-connect.png", "03-connect.png", "Connect any app", "Copy the base URL and model ID, or scan a QR code"),
    ("07-catalog.png", "04-catalog.png", "Download open models", "Ready-made ExecuTorch exports from Qwen, Meta, Liquid AI and more"),
    ("08-activity.png", "05-activity.png", "See every request", "Speeds, phases and device state, kept across restarts"),
    ("11-dark-chat.png", "06-private.png", "Private by design", "Replies come from the model on this phone. No account, no cloud."),
    ("09-settings.png", "07-settings.png", "You decide who connects", "This phone only, or your network with API keys"),
    ("06-report.png", "08-report.png", "Report any reply", "You see the whole report before anything is sent"),
]

# Tablets: Play takes 16:9 or 9:16, so each image keeps its capture's size and shape. The
# caption band takes the top 17%; the screen, without the gesture bar, fills the rest.
TABLET_BAND = 0.17
TABLET_CROP = 0.96  # of the capture's height: the gesture bar goes

def tablet_still(folder, raw, out, title, sub):
    shot = Image.open(PLAY / folder / raw).convert("RGB")
    cw, ch = shot.size
    shot = shot.crop((0, 0, cw, round(ch * TABLET_CROP)))
    band = round(ch * TABLET_BAND)
    scale = ch / 1440 * 0.9  # caption type in proportion to the canvas
    sh = ch - band
    sw = round(shot.width * sh / shot.height)
    radius = round(RADIUS * ch / 1440)
    shot = shot.resize((sw, sh), Image.LANCZOS).convert("RGBA")
    shot.alpha_composite(corners_mask((sw, sh), radius))
    canvas = Image.new("RGBA", (cw, ch), INK)
    canvas.alpha_composite(caption(title, sub, size=(cw, band), scale=scale), (0, 0))
    canvas.alpha_composite(shot, ((cw - sw) // 2, band))
    canvas.convert("RGB").save(STORE / out, optimize=True)

TABLET_SHOTS = [
    ("01-hosting.png", "Your tablet is the server", "OpenAI- and Anthropic-compatible APIs, running on-device with ExecuTorch"),
    ("02-chat.png", "Try any model in Chat", "Prefill and decode speeds on every reply"),
    ("03-library.png", "Your models and the catalog, side by side", "Ready-made ExecuTorch exports from Qwen, Meta, Liquid AI and more"),
    ("04-activity.png", "See every request, compare every model", "Speeds, phases and device state, kept across restarts"),
    ("05-settings.png", "You decide who connects", "This tablet only, or your network with API keys"),
]

# Video captions, by the scene changes in the recording (seconds). screenrecord writes frames
# only when the screen changes, so read these from the video, not the recorder's clock:
#   ffmpeg -i fgs-raw.mp4 -vf "scale=320:-1,select='gt(scene,0.05)',metadata=print" -f null -
SEGMENTS = [
    (0.0, 6.1, "ExecuServe hosts AI models on this phone", "Tap Start to begin serving"),
    (6.1, 16.5, "Hosting runs as a foreground service", "With an ongoing notification and a Stop action"),
    (16.5, 34.3, "Another app connects to it", "Here, Chrome on this phone, with an API key"),
    (34.3, 42.2, "ExecuServe is in the background", "The service keeps the model loaded and answers"),
    (42.2, 99.0, "Every request is logged", "Stop hosting from the app or the notification"),
]

def video():
    raw = VIDEO / "fgs-raw.mp4"
    sw, sh = screen_size()
    VIDEO.joinpath("parts").mkdir(exist_ok=True)
    bg = Image.new("RGBA", (W, H), INK); bg.save(VIDEO / "parts/bg.png")
    corners_mask((sw, sh)).save(VIDEO / "parts/corners.png")
    inputs, chain = ["-loop", "1", "-i", str(VIDEO / "parts/bg.png"), "-i", str(raw), "-loop", "1", "-i", str(VIDEO / "parts/corners.png")], []
    for i, (a, b, t, s) in enumerate(SEGMENTS):
        p = VIDEO / f"parts/cap{i}.png"; caption(t, s).save(p)
        inputs += ["-loop", "1", "-i", str(p)]
    chain.append(f"[1:v]fps=30,crop=1280:{CROP_BOTTOM}:0:0,scale={sw}:{sh}:flags=lanczos,tpad=stop_mode=clone:stop_duration=3[scr]")
    chain.append(f"[0:v][scr]overlay=({W}-{sw})/2:{BAND}:shortest=1[v0]")
    chain.append(f"[v0][2:v]overlay=({W}-{sw})/2:{BAND}:shortest=1[v1]")
    last = "v1"
    for i, (a, b, _, _) in enumerate(SEGMENTS):
        chain.append(f"[{last}][{3 + i}:v]overlay=0:0:shortest=1:enable='between(t,{a},{b})'[c{i}]")
        last = f"c{i}"
    chain.append(f"[{last}]fade=t=in:st=0:d=0.4,format=yuv420p[out]")
    cmd = ["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", ";".join(chain), "-map", "[out]",
           "-r", "30", "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-movflags", "+faststart", str(VIDEO / "execuserve-foreground-service.mp4")]
    subprocess.run(cmd, check=True)

if __name__ == "__main__":
    STORE.mkdir(exist_ok=True)
    what = sys.argv[1:] or ["stills", "tablets", "video"]
    if "stills" in what:
        for raw, out, t, s in SHOTS: still(raw, out, t, s)
    if "tablets" in what:
        for inches in ("7", "10"):
            if (PLAY / f"tablet{inches}").is_dir():
                for raw, t, s in TABLET_SHOTS:
                    tablet_still(f"tablet{inches}", raw, f"tablet{inches}-{raw}", t, s)
    if "video" in what:
        video()
