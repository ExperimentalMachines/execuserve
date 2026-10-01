# Testing on a phone

The steps used on the first physical phone, a POCO X8 Pro Max (MediaTek Dimensity 9500s,
12 GB, HyperOS 3 on Android 16), in the order that catches the most likely failure first.
They apply to any arm64 phone on Android 12 or later; the HyperOS notes mark what is
specific to Xiaomi's ROM. What that phone measured is in
[results/2026-09-29-poco-x8-pro-max.md](results/2026-09-29-poco-x8-pro-max.md).

## 0. Reach the phone

Wireless debugging works as well as a cable. HyperOS advertises it over mDNS only while
the Wireless debugging screen is open, and the port changes each time it is.

```sh
adb mdns services                    # or: dns-sd -B _adb-tls-connect._tcp local.
adb connect <ip>:<port>
export SER=<ip>:<port>
```

## 1. Install

HyperOS refuses `adb install` (`INSTALL_FAILED_USER_RESTRICTED`); the CLI pushes the APK
and installs it from the shell instead.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :android:app:assembleRelease
tools/execuserve -s $SER install android/app/build/outputs/apk/release/app-release.apk
tools/execuserve -s $SER unrestrict
```

`unrestrict` adds the app to the Doze whitelist, allows it to run in the background and,
on HyperOS, allows Autostart. Open the app once and allow notifications; the Server tab's
attention panel should then be empty, or name only what the ROM still needs.

## 2. A model

Either Models, then Browse the catalog on the phone (which also tests the downloader over
the phone's own network), or push an export from the computer. Reasonable first choices,
from [huggingface.co/experimentalmachines](https://huggingface.co/experimentalmachines):

| Model | Why |
|---|---|
| `Qwen3-1.7B-8da4w-gptq-4k` (1.3 GB) | reasoning and tool calls; the family the reply ledger was built for |
| `LFM2.5-1.2B-Instruct-8da4w-gptq-8k` (0.8 GB) | fast, with a long window |
| `LFM2.5-2.6B-8da4w-gptq-4k` | OpenWeights' pick for tool use |

```sh
tools/execuserve -s $SER --model qwen3-1.7b-8da4w-gptq-4k --network --port 8080
```

The CLI prints the base URL and the key it made for this device.

## 3. The API

```sh
B=http://<phone>:8080/v1 K=<key> M=qwen3-1.7b
uv run --with openai python tools/compat/openai_sdk_check.py $B $K $M
uv run --with anthropic python tools/compat/anthropic_sdk_check.py ${B%/v1} $K $M
uv run --with httpx python tools/compat/edge_cases.py $B $K $M
```

Expect 16 of 16, 8 of 8 and 28 of 28. Then compare the `timings` of a long prompt
(`tools/compat/bench.py`) against OpenWeights' figures for the same export: they share an
engine, so a difference in prefill or decode speed is a finding.

The cache probes explicitly disable thinking and require completed replies: a reply cut
off by its output budget cannot safely extend the sequence cache. Pass a second installed
model to `edge_cases.py` to exercise its interleaved-model probe; otherwise it is skipped.

## 4. From another machine

With `--network`, from the computer over Wi-Fi and then over Tailscale:

```sh
curl -s http://<wifi-ip>:8080/health
uv run --with openai python tools/compat/openai_sdk_check.py http://<tailscale-ip>:8080/v1 $K $M
```

A Tailscale address works as is. A MagicDNS name must first be added under Settings, Other
names for this phone, or the Host check refuses it (by design; see
[ARCHITECTURE.md](ARCHITECTURE.md)).

## 5. The background, which is the real test

Under forced Doze on the emulator, a foreground-service process kept its network and its
wake lock. OEM ROMs add their own freezers on top, so test unplugged:

```sh
uv run --with openai python tools/compat/soak.py http://<wifi-ip>:8080/v1 $K $M --minutes 45 --every 60
# start it, lock the phone, leave it
```

Then again after `adb shell dumpsys deviceidle force-idle` (HyperOS accepts only light
idle), and once more without the `unrestrict` step, to learn what the ROM does by default.
While it runs, `adb shell dumpsys netpolicy | grep <uid>` should show `allowed=FOREGROUND`.
Each run answers one question in `soak.csv`: did every request get an answer, and how much
slower was the first one after the screen went off.

## 6. Restart after a crash

```sh
adb shell am crash org.experimentalmachines.execuserve
curl -s http://<wifi-ip>:8080/health        # repeat for a few seconds
```

Android restarts a sticky foreground service, and the app resumes serving and says so on
the Server tab. On HyperOS this happens only with Autostart allowed: without it the POCO
stayed down; with it the server answered again within about two seconds. If you changed
Autostart for this test, set it back afterwards.

## 7. Heat

A long generation loop (the soak with `--every 1` and a long `max_tokens`) until the phone
reports `THERMAL_STATUS_SEVERE`: new requests should get `503` with `Retry-After`, the
console should say it paused because the phone is too hot, and admission should reopen
when it cools.

## Console layout

The shared frame has Compose measurement tests under Robolectric:

```sh
./gradlew :android:app:testDebugUnitTest
```

They cover both landscape rotations with asymmetric side insets, gesture navigation,
portrait and split-screen widths, enlarged text, tablets, light/dark themes, RTL, and
scrolling to the final navigation tab in a short window. Insets are applied once around
the whole frame; the two-column decision uses the width left after navigation and page
gutters, with additional room for enlarged text.

On a device, check every tab in both landscape rotations with gesture navigation and
three-button navigation. Repeat with larger text, in split screen, and with the keyboard
open in Settings or Try it. The header, cards and actions must stay inside the system bars
and camera cutout. A narrow landscape window may show one content column beside the rail;
scroll the rail if the final tab does not fit vertically.

## What to bring back

The three suites' output, `soak.csv` for each background run, the timings of one long
prompt, and anything the console showed that the terminal did not.

## Browser chat

Build `:jvm:devserver:installDist` and run its `execuserve-dev` binary with
`--port 8082 --token-ms 40`. This uses scripted replies for deterministic UI checks.
Then run `npm ci --prefix tools/web`, `npx --prefix tools/web playwright install chromium`,
and `npm test --prefix tools/web`. `CHAT_ARTIFACTS` selects the screenshot directory
(default `/tmp/execuserve-browser-checks`). The checks cover responsive layouts,
authentication, streaming, cancellation, safe model-text rendering, interrupted-stream
retry, and clearing credentials/history on reload. Set `CHAT_BROWSER=webkit` after
installing Playwright WebKit to repeat functional checks in that engine. Screenshots
are captured in Chromium because Playwright WebKit injects a screenshot stylesheet
that the app’s CSP correctly rejects.

For actual inference, install the APK, enable Your network, open the root browser-chat
address, connect with an API key, and send a prompt to an installed model. Verify a
follow-up and Stop on both portrait and landscape layouts. The JVM fixture does not
validate native inference.
