# The launch video

A 56-second, 1920×1080 ad for ExecuServe, built from code and real recordings: the phones' own
screen recordings, another device using a phone over Wi-Fi, one HTML page that animates them
frame by frame, and an original track synthesised in Python. Its pipeline follows the
[PengePassportPH ad](https://github.com/alpharomercoma/penge-passport-ph/tree/main/marketing/ad).

The finished video is [`out/execuserve-ad-16x9.mp4`](out/execuserve-ad-16x9.mp4) (60 fps, H.264
and AAC, -14 LUFS), with a cover image in [`out/thumbnail.png`](out/thumbnail.png). The rest of
`out/` and all of `frames/` are generated and not committed. Upload copy is in
[`youtube.txt`](youtube.txt).

## The idea

Your phone has a chip built for AI, and most apps never use it. ExecuServe runs a model on
whichever processor your phone has (the CPU on any phone, the GPU through Vulkan, the NPU on
Snapdragon and MediaTek Dimensity chips) and serves it to every app on the phone and every
device on your network. The ad shows the processors, what the NPU buys on one phone (measured),
the phone becoming a server, a document read on a Snapdragon's NPU in real time, and a MediaTek
phone answering another device with its screen off. The ember (the mark's die) is the chip at
work: it goes cold in the hook, and glows above the phone wherever the model is running.

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
| `music.py` | The track: 112 BPM warm electro in F major, 25 bars and a tail, synthesised with numpy and scipy (no samples, nothing licensed). Seeded. Its moments are the story's: the ember motif falling away as the chip goes cold, a hit on the logo and on each processor, a bell as each chart bar stops, the groove landing on Serving, a tick while the NPU reads, a hush when the screen goes off, a bell when the answer still arrives. |
| `browser.cjs` | Another device using the phone: Playwright opens the chat page the phone serves at its Wi-Fi address, connects (the key goes into a password field), asks one question and films it as 2× screenshots, encoded to `assets/clips/<name>.mp4` with the moments it connected, sent, got its first text and finished. With `ANDROID_SERIAL` set it also logs the phone's screen and lock state before and after the take. |
| `assets/clips/start.mp4` | The Snapdragon's own screen recording, portrait, on release 0.1.0 (64): Start hosting tapped, then "Loading qwen3-1.7b-qnn into memory" and Ready for requests in real time, 0.64 s later. `start.json` holds the moments and the button's position. |
| `assets/clips/npu-sm8850-chat.mp4` | The Snapdragon 8 Elite Gen 5's own screen recording (Qualcomm Device Cloud) on release 0.1.0 (64): meeting notes typed into the app's Chat and summarised on the NPU, with the reply's own figures (1172→27 tok/s, 3.2 s). Its JSON has the prompt, the reply, the figures, and when Send was tapped and the reply finished, read from the status dot. |
| `assets/clips/npu-poco-asleep.mp4` | `browser.cjs` on another device asking the POCO's MediaTek NPU build (release 0.1.0 (64)) at its Wi-Fi address, with the phone locked and dozing before and after. `assets/phone-serving.png` is the POCO's Hosting tab just before, with that model in memory and its last request's figures. |
| `assets/clips/cpu-sm8850-chat.mp4` | Not in the ad: the same Chat take on the Snapdragon's CPU, kept for the comparison in `docs/results/2026-10-06-npu.md`. |
| `clips.sh` | Turns the clips into frames under `frames/clips/` for the page. |
| `data.cjs` | Bundles the recordings into `assets/data.js` and refuses ones that no longer fit their scenes: marks out of order, clips too short, a screen-off take without the phone's locked state. |
| `qr.py` | The end card's QR code, for the project page. |
| `render.cjs` | Screenshots `renderAt` frame by frame in parallel Playwright pages; fails on any page error. |
| `build.sh` | Clips, data, music, a 120 fps render, each pair of frames blended into one at 60 fps (motion blur), music set to -14 LUFS, H.264 and AAC. |
| `stills.cjs`, `sheet.sh` | Review: stills at chosen times (`node stills.cjs 3 12 27`) and 2×2 contact sheets. |

## Scenes

| Scene | Bars | Seconds | What it shows |
| --- | --- | --- | --- |
| Hook | 0–3 | 0–6.4 | The chip mark, its die glowing. "Your phone has a chip built for AI." "Most apps never get to use it.": the die goes cold. |
| Logo | 3–5 | 6.4–10.7 | The mark builds on the beat. "An AI server for any Android phone." Free, Open source. |
| Processors | 5–7 | 10.7–15.0 | "It runs on the chip you have." A card a beat: CPU (XNNPACK, every Android phone), GPU (Vulkan), NPU (Qualcomm QNN, Snapdragon), NPU (MediaTek NeuroPilot, Dimensity). |
| Chart | 7–9 | 15.0–19.3 | Time to the first token on a 700-token prompt on one Snapdragon 8 Elite Gen 5: CPU 2.0 s, GPU 1.0 s, NPU 0.41 s, each bar growing for as long as it took. |
| Tap Start | 9–11 | 19.3–23.6 | The Snapdragon: Start hosting, its NPU build loading, Ready for requests, in real time, landing on the groove. "Now it's a server. For the apps on the phone, and every device on your network." The ember lights. |
| On the NPU | 11–15 | 23.6–32.1 | The Snapdragon's Chat summarising meeting notes on its NPU, in real time, and the app's own figures for that reply. |
| Every app | 15–20 | 32.1–42.9 | Another device's browser at the POCO's Wi-Fi address; the phone's screen goes off, and the answer comes from its MediaTek NPU. The clients the README documents. |
| Without | 20–22 | 42.9–47.1 | No cloud. No account. No subscription. Just your phone's own chip. |
| End card | 22–end | 47.1–56.2 | Mark and wordmark, "CPU, GPU, and Snapdragon and MediaTek NPUs", the project page and its QR code, the source, the independence and trademark note. |

## Where each claim comes from

- Everything on a device screen is a recording: the phones' own screen recordings (the POCO X8
  Pro Max, and the Snapdragon 8 Elite Gen 5 on Qualcomm Device Cloud), and the POCO's chat page
  in a browser over Wi-Fi.
- The processors: the app ships ExecuTorch with XNNPACK, Vulkan, QNN and MediaTek's runtime, and
  offers a phone the NPU builds compiled for its own chip (`docs/results/2026-10-06-npu.md`).
  Both NPUs answered through ExecuServe on these phones.
- The chart and the NPU figures: `docs/results/2026-10-06-npu.md` (Qwen3-1.7B at 4k, the same
  harness on all three processors; the NPU take's footer is the app's own measurement).
- The screen-off take: `assets/clips/npu-poco-asleep.json` holds the phone's state read over adb
  before and after it, locked and dozing both times, and the Wi-Fi address it was asked at.
- The client names: the README's HTTP API and Clients sections. "No cloud, no account, no
  subscription": the README and the privacy policy (models run on the phone; the catalog
  downloads from Hugging Face).
- Nothing is sped up: every take plays at its recorded pace.

## Rules for anything made here

- Real or nothing: re-record rather than edit a claim, and keep each clip's JSON with it.
- No key is ever drawn: `browser.cjs` reads it from the environment and types it into a
  password field.
- No other company's logos; product names only to say what ExecuServe works with. The end card
  says the project is independent and that PyTorch and ExecuTorch are Linux Foundation
  trademarks.
