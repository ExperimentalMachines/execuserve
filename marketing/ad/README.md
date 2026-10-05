# The launch video

A 51-second, 1920×1080 ad for ExecuServe, aimed at developers, built entirely from code: real
recordings from a phone serving a model, one HTML page that animates them frame by frame, and an
original track synthesised in Python. Its structure follows the
[PengePassportPH ad](https://github.com/alpharomercoma/penge-passport-ph/tree/main/marketing/ad).

The finished video is [`out/execuserve-ad-16x9.mp4`](out/execuserve-ad-16x9.mp4) (60 fps, H.264
and AAC, -14 LUFS), with a cover image in [`out/thumbnail.png`](out/thumbnail.png). The rest of
`out/` and all of `frames/` are generated and not committed. Upload copy is in
[`youtube.txt`](youtube.txt).

## Build it

```sh
cd marketing/ad
npm install
./build.sh   # out/execuserve-ad-16x9.mp4 and out/thumbnail.png
```

Needs Node 22+, [uv](https://docs.astral.sh/uv/) and ffmpeg. About 4 minutes on an M-series Mac.
A rebuild rewrites the committed video and thumbnail; commit them again with the change.

## How it fits together

| File | What it does |
| --- | --- |
| `ad.html` | The whole ad as one page. `window.renderAt(t)` draws the frame at `t` seconds and never reads the clock, so every frame renders the same every time. Scenes cut on the music's bar lines: `bar(n, beat)` at 124 BPM. |
| `music.py` | The track: 124 BPM techno in D minor, 25 bars and a tail, synthesised with numpy and scipy (no samples, nothing licensed). Seeded. Each recorded token gets a soft blip at the moment it arrived. |
| `record.py` | Real footage from a phone serving a model, through the official SDKs: `/v1/models`, a streamed reply with each token's arrival time and the server's timings (OpenAI SDK), a reply through the Anthropic SDK, and a cold first turn against a warm follow-up on a ~2,200-token conversation, with the server's own counts proving the first turn was cold and loaded nothing. |
| `capture.cjs` | The phone's own browser chat, driven by Playwright on a laptop through the forwarded port: a real question, the reply streaming in, frame by frame with timestamps. The key is typed into the page's password field and never drawn. |
| `data.cjs` | Bundles the recordings into `assets/data.js` (a `file://` page cannot fetch JSON). |
| `qr.py` | The end card's QR code, for the project page. |
| `render.cjs` | Screenshots `renderAt` frame by frame in parallel Playwright pages. |
| `build.sh` | Data, music, a 120 fps render, each pair of frames blended into one at 60 fps (motion blur), music set to -14 LUFS, H.264 and AAC. |
| `stills.cjs`, `sheet.sh` | Review: stills at chosen times (`node stills.cjs 3 12 27`) and 2×2 contact sheets. |

## Scenes

| Scene | Bars | What it shows |
| --- | --- | --- |
| The hook | 0–2 | An OpenAI client; `base_url` is selected and retyped to `http://127.0.0.1:8080/v1` (the phone, forwarded over adb), a click per character. "Keep your SDK. Point it at your phone." |
| The reveal | 2–4 | A wire from that URL to a phone serving Qwen3 1.7B. "That endpoint is a phone." A riser, then half a beat of silence. |
| The drop | 4–6 | The mark builds itself: lid, faces, six pins, then the ember die with an isometric burst. Free, Open source, Apache-2.0. |
| The API | 6–9 | "A server, not a chat app." The routes, then the project's compatibility suites, run with the official SDKs: 16/16, 8/8, 28/28. |
| Live | 9–12 | The recorded reply streaming at the moment each token arrived, the server's figures, the Anthropic SDK's reply, then the phone's browser chat streaming. |
| The cache | 12–14 | A 2,195-token conversation: its cold first turn 19.38 s to the first token, its follow-up 0.50 s with 2,202 tokens reused from the KV cache, 39× sooner. |
| Clients | 14–16 | Half time. Clients the README documents (not codex: its prompts outgrow a phone export's window), curl, and the phone's Connect QR. |
| Residency | 16–18 | Three models as three marks; one die lit at a time, for the one compute lane. |
| Guarantees | 18–20 | API keys by default, serving with the screen off (20 of 20 in a 10-minute battery soak), inference on the phone, Kotlin Multiplatform. |
| Open source | 20–22 | `git clone` and `./gradlew verify`, with the README's description of what it runs; no build output is simulated. |
| End card | 22–end | The mark and the brand kit's wordmark, the project page and its QR code, the source, the independence note and the Linux Foundation trademark. |

## Where each claim comes from

- The routes, the SDK suite counts (16/16, 8/8, 28/28), three resident models on one lane, keys
  by default including loopback, the screen-off soak, and the iOS build: the
  [README](../../README.md)'s "What makes it different" and "HTTP API".
- The streamed reply, its figures (44 tokens, prefill 99 tok/s, decode 29.2 tok/s), the
  Anthropic reply and the cache timings (19.38 s cold, 0.50 s warm, 2,202 tokens reused): `assets/*.json`, recorded by `record.py` from Qwen3
  1.7B on a POCO X8 Pro Max. The ad reads them from there, so a new recording changes the ad.
- The browser chat: `assets/web/`, captured by `capture.cjs` from the same phone.
- The phone screens: the Play Store captures in `docs/play` (the release build, demo-mode
  status bar).

## Rules for anything made here

- Real or nothing: every number on screen is read from a recording or quoted from the README.
  Re-record before changing a claim.
- No other company's logos; product names only to say what ExecuServe works with. The end card
  says the project is independent and that PyTorch and ExecuTorch are Linux Foundation trademarks.
- `data.cjs` checks the recordings still fit their scenes, and the render fails on any page or
  image error, so a changed recording cannot quietly break the video.
- No key is ever drawn: `capture.cjs` types it into a password field, and `record.py` reads it
  from the environment.
