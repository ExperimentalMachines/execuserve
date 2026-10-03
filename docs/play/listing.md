# Play Store listing

The text and assets for the store listing, in the order the Play Console asks for them. The
screenshots and the video were captured on a POCO X8 Pro Max running the release build;
`tools/play` makes them again (see [Remaking the assets](#remaking-the-assets)).

## Text

**App name** (27 of 30)

```
ExecuServe: Local AI Server
```

**Short description** (72 of 80)

```
Serve on-device AI models to your apps through OpenAI and Anthropic APIs
```

**Full description**

```
ExecuServe turns your Android phone into a local AI server. It keeps compiled ExecuTorch models loaded on the phone and answers the OpenAI and Anthropic APIs over HTTP, so apps on the phone, or on your network if you allow it, can use them like any hosted model.

SERVE
• OpenAI Chat Completions and Responses, and Anthropic Messages, with streaming and tool calls
• Several models kept ready at once, sharing one queue
• Keeps serving in the background, with an ongoing notification and a Stop action
• This phone only by default; your network only when you choose it, always with API keys

CHAT
• Talk to any installed model from the Chat tab, through the same API your apps use
• Turn thinking on or off for models that support it
• Prefill and decode speeds on every reply

CONNECT
• Base URLs, model IDs and keys to copy, or to scan as QR codes
• A browser chat built in, so a laptop or tablet can talk to the phone's models with nothing installed

KNOW WHAT HAPPENED
• Every request with its phases, speeds and device state, kept across restarts
• Like-for-like comparison of models, a benchmark, and CSV export

MODELS
• Download ready-made ExecuTorch exports from Qwen, Meta, Liquid AI and others, published by Experimental Machines on Hugging Face
• Or copy your own .pte model and tokenizer to the phone from a computer

PRIVATE BY DESIGN
• Replies are generated on the phone. There is no account, no analytics and no ExecuServe server.
• Over the internet, the app contacts only Hugging Face: for the catalog, model downloads and publishers' pictures.
• Every reply has "Report this reply"; you see the whole report before anything is sent.

AI models can make mistakes and may produce inaccurate or offensive content. Replies come from the model you choose, not from ExecuServe.

Requires Android 12 or newer on a 64-bit Arm phone, and free memory for the models you keep loaded: about each model's file size.

ExecuServe is open source under the Apache 2.0 licence: github.com/ExperimentalMachines/execuserve

An independent project by Experimental Machines. Not affiliated with, endorsed by or sponsored by the PyTorch Foundation or Meta.
```

**Category** Tools · **Website** `https://experimentalmachines.org/execuserve/` ·
**Privacy policy** `https://experimentalmachines.org/execuserve/privacy/`

## Graphics

| Asset | File | Size |
|---|---|---|
| App icon | [../brand/play-icon.png](../brand/play-icon.png) | 512 × 512 |
| Feature graphic | [../brand/feature-graphic.png](../brand/feature-graphic.png) | 1024 × 500 |
| Phone screenshots, in order | [screenshots/](screenshots/) | 1080 × 1920 each, 8 of 8 |

1. Your phone is the server (Hosting)
2. Try any model in Chat
3. Connect any app (Connect)
4. Download open models (catalog)
5. See every request (Activity)
6. Private by design (Chat, dark theme)
7. You decide who connects (Settings)
8. Report any reply (the report dialog)

## Foreground service video

[execuserve-foreground-service.mp4](execuserve-foreground-service.mp4), 50 seconds, 1080 ×
1920, captioned. Play asks for a link: upload it to YouTube as unlisted and paste that URL
into App content → Foreground service permissions, under `specialUse`. What it shows:

| Time | Caption | On screen |
|---|---|---|
| 0:00 | ExecuServe hosts AI models on this phone | Hosting, stopped |
| 0:06 | Hosting runs as a foreground service | Start, then Working, then Serving |
| 0:16 | Another app connects to it | Chrome opens the browser chat; the key goes into a password field |
| 0:34 | ExecuServe is in the background | The request is sent and the reply streams back |
| 0:42 | Every request is logged | Back in ExecuServe, the request in Activity |

## Remaking the assets

With the phone attached over adb (set `ANDROID_SERIAL` if an emulator is listed too), the
release build installed and a model served:

- Turn on demo mode for a clean status bar:
  `adb shell settings put global sysui_demo_allowed 1`, then
  `adb shell am broadcast -a com.android.systemui.demo -e command enter`, and the `clock`,
  `battery`, `network` and `notifications` commands. Turn it off afterwards with
  `-e command exit`.
- Capture each screen to `build/play/raw/` with `python3 tools/play/ui.py shot <file>`, using
  `tap` to move between tabs. The names `compose.py` expects are in its `SHOTS` table.
- Record the video with `tools/play/record.py`, whose docstring has the steps.
- Compose with `python3 tools/play/compose.py`, check the results, and copy them here.

Show prompts whose answers can't be wrong in a store image: a haiku or a list of names,
not a technical explanation a small model may get wrong. The release build's catalog
already leaves out the `heretic` (abliterated) variants.
