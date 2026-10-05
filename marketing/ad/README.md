# The launch video

A 60-second, 1920×1080 ad for ExecuServe, built from code and real recordings: the app's own
screen recordings, a laptop using the phone over Wi-Fi, one HTML page that animates them frame
by frame, and an original track synthesised in Python. Its pipeline follows the
[PengePassportPH ad](https://github.com/alpharomercoma/penge-passport-ph/tree/main/marketing/ad).

The finished video is [`out/execuserve-ad-16x9.mp4`](out/execuserve-ad-16x9.mp4) (60 fps, H.264
and AAC, -14 LUFS), with a cover image in [`out/thumbnail.png`](out/thumbnail.png). The rest of
`out/` and all of `frames/` are generated and not committed. Upload copy is in
[`youtube.txt`](youtube.txt).

## The idea

Your phone runs the AI; your other devices use it. Other on-device apps keep the model inside one
chat screen, and desktop servers need a desktop. The ad shows the whole loop on real footage: pick
a model, tap Start, open it from a laptop, lock the phone and get an answer anyway, then point
your own code at it. The ember (the mark's die) stays above the phone wherever the model is
working; small dots carry the answer to the device that asked.

## Build it

```sh
cd marketing/ad
npm install
./build.sh   # out/execuserve-ad-16x9.mp4 and out/thumbnail.png
```

Needs Node 22+, [uv](https://docs.astral.sh/uv/) and ffmpeg. About 5 minutes on an M-series Mac.
A rebuild rewrites the committed video and thumbnail; commit them again with the change.

## How it fits together

| File | What it does |
| --- | --- |
| `ad.html` | The whole ad as one page. `window.renderAt(t)` draws the frame at `t` seconds, waits for each footage frame to decode and never reads the clock, so every frame renders the same every time. Scenes cut on the music's bar lines: `bar(n, beat)` at 112 BPM. |
| `music.py` | The track: 112 BPM warm electro in F major, 27 bars and a tail, synthesised with numpy and scipy (no samples, nothing licensed). Seeded. Its moments are the story's: the ember motif under the hook, a hit on the logo, the groove landing on Serving, a hush when the screen goes off, a bell when the answer still arrives. |
| `browser.cjs` | A laptop using the phone: Playwright opens the chat page the phone serves at its Wi-Fi address, connects (the key goes into a password field), asks one question and films it as 2× screenshots, encoded to `assets/clips/<name>.mp4` with the moments it connected, sent, got its first text and finished. With `ANDROID_SERIAL` set it also logs the phone's screen and lock state before and after the take. |
| `assets/clips/start.mp4` | The phone's own screen recording: Start, Working, Serving (the Play Store foreground-service take). |
| `assets/clips/download.mp4` | The catalog on the release build: Qwen3 1.7B expanded, its 4k export fetched, Installed. `download.json` says where each moment is and how the recording was joined. |
| `record.py` | The code scene's reply: a streamed request through the OpenAI SDK over Wi-Fi, each token's arrival time kept (`assets/stream.json`). |
| `clips.sh` | Turns the clips into frames under `frames/clips/` for the page. |
| `data.cjs` | Bundles the recordings into `assets/data.js` and refuses ones that no longer fit their scenes: marks out of order, clips too short, a screen-off take without the phone's locked state. |
| `qr.py` | The end card's QR code, for the project page. |
| `render.cjs` | Screenshots `renderAt` frame by frame in parallel Playwright pages; fails on any page error. |
| `build.sh` | Clips, data, music, a 120 fps render, each pair of frames blended into one at 60 fps (motion blur), music set to -14 LUFS, H.264 and AAC. |
| `stills.cjs`, `sheet.sh` | Review: stills at chosen times (`node stills.cjs 3 12 27`) and 2×2 contact sheets. |

## Scenes

| Scene | Bars | Seconds | What it shows |
| --- | --- | --- | --- |
| Hook | 0–3 | 0–6.4 | A reply streaming on a laptop. "This answer didn't come from the cloud." Then a ring round the phone: "It came from the phone next to it." |
| Logo | 3–5 | 6.4–10.7 | The mark builds on the beat. "Turn your Android phone into an AI server." Free, Open source. |
| Pick a model | 5–8 | 10.7–17.1 | The catalog: Qwen3 1.7B, a tap on its 4k export, the download (sped up), Installed. |
| Tap Start | 8–11 | 17.1–23.6 | Stopped, Working (sped up), Serving, landing on the groove. "Now it's a server." The ember lights. |
| Laptop | 11–15 | 23.6–32.1 | The phone's chat page in a laptop browser at its Wi-Fi address: connect, ask, the answer streams in, zoomed to read. |
| Screen off | 15–19 | 32.1–40.7 | The phone goes dark; the laptop asks again and the answer comes. Recorded with the phone locked and dozing (checked over adb before and after). |
| Your apps | 19–22 | 40.7–47.1 | The request `record.py` sent, through the OpenAI SDK, and the reply it got. Clients the README documents. |
| Without | 22–24 | 47.1–51.4 | No cloud inference. No account. No subscription. Just your phone. |
| End card | 24–end | 51.4–60.5 | Mark and wordmark, the project page and its QR code, the source, the independence and trademark note. |

## Where each claim comes from

- Everything on a device screen is a recording: the app on the release build (the phone's own
  screen recordings, and the catalog download on the same build in an emulator, since the phone
  was locked for the screen-off take), and the phone's chat page in a browser over Wi-Fi.
- The screen-off take: `assets/clips/asleep.json` holds the phone's state read over adb before
  and after it, locked and dozing both times. The README's "Built to stay up" covers the same
  behaviour and its limits on OEM ROMs.
- "Compatible with OpenAI and Anthropic clients" and the client names: the README's HTTP API
  and Clients sections. "No cloud inference, no account, no subscription": the README and the
  privacy policy (models run on the phone; the catalog downloads from Hugging Face).
- Sped-up footage says so on screen; the browser and SDK replies play at their recorded pace.

## Rules for anything made here

- Real or nothing: re-record rather than edit a claim, and keep each clip's JSON with it.
- No key is ever drawn: `browser.cjs` types it into a password field, `record.py` reads it from
  the environment.
- No other company's logos; product names only to say what ExecuServe works with. The end card
  says the project is independent and that PyTorch and ExecuTorch are Linux Foundation
  trademarks.
