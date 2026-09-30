# First run on a phone: POCO X8 Pro Max, 2026-09-29

POCO X8 Pro Max (`2602BPC18G`), MediaTek Dimensity 9500s (MT6991), 11.5 GB RAM, Android 16,
HyperOS 3.0 (`OS3.0.303.0.WPLMIXM`). ExecuServe 0.1.0 release build (R8), installed over
wireless debugging with `tools/execuserve install`. Unplugged throughout, battery 48% at the
start, thermal status 0 unless stated.

Model: `LFM2.5-1.2B-Instruct-8da4w-gptq-4k` from
`experimentalmachines/LFM2.5-1.2B-Instruct-ExecuTorch`, copied on the phone from an
OpenWeights test folder; SHA-256 `99517d41…` equals the published file. The 2.6B gptq 4k was
copied the same way (`54fe0084…`, also equal).

## The API, from the Mac over Wi-Fi

`tools/compat/openai_sdk_check.py` against the phone's Wi-Fi address, no adb forward
in the path: **15 of 15**. Tool calls came back in LFM2.5's pythonic syntax and parsed; a
tool loop's follow-up reused 100 of 124 prompt tokens on both Chat Completions and
Responses. LFM2.5-1.2B has no reasoning mode and answered "Is 17 prime?" with "No": the
check tests the protocol, not the model.

Server start including the model load: 2.2 s.

## Speed

`tools/compat/bench.py`: a 2,077-token system prompt, then two follow-ups in the same
conversation, then a short prompt with 256 tokens out. Temperature 0, reasoning off.

| | Screen off (dozing) | Screen on |
|---|---|---|
| Turn 1, cold, 2,077 tokens | prefill 7,721 ms (269 tok/s), wall 8.36 s | 7,008 ms (296 tok/s), 7.54 s |
| Turn 2, 2,088 of 2,106 cached | prefill 105 ms, wall **0.47 s** | 107 ms, 0.48 s |
| Turn 3, 2,117 of 2,139 cached, 68 out | 125 ms, wall 2.28 s | 135 ms, 2.38 s |
| 23-token prompt, 256 out | decode 43.5 tok/s | 42.1 tok/s |

- The second turn of a long conversation answers in under half a second instead of eight:
  the sequence cache plus the reply ledger, on silicon.
- Screen-off and screen-on are within about 10% of each other with the foreground service
  running; the ROM is not throttling it.
- Decode of 42 to 44 tok/s is in line with OpenWeights' finding that ExecuTorch decodes 1.2
  to 1.6 times faster than llama.cpp (whose Q4_0 build of the same model decoded 28 to 33
  tok/s on this chip in OpenWeights' notes). Different harnesses, so a consistency check,
  not a benchmark.

## The background

HyperOS would not enter **deep** Doze on command (`force-idle deep` stops at `INACTIVE`; its
own power manager overrides AOSP's). **Light** Doze could be forced, and its network block
is the same firewall chain.

| Condition | Result |
|---|---|
| Screen off, light Doze, Doze whitelist and appops exemptions applied | 200 in 0.19 to 0.23 s |
| Screen off, light Doze, **no exemptions at all** (appops at their true defaults) | 200 in 0.41 to 0.64 s; `netpolicy`: `blocked=DOZE…, allowed=FOREGROUND…, effective=NONE`, `procState=FGS` |
| Battery usage set to **Restricted** | Android stopped the foreground service 62 ms after the setting (`FGS stop call for: 10461`), HyperOS's freezer then disabled the wake lock (`reason: greeze`), and a request timed out after 13.8 s |

So on HyperOS the foreground service alone keeps the server reachable with the screen off
and the network in Doze; the whitelist is not what does it. HyperOS's own `PolicyMaker`
logs the app as exempt every five seconds (`HasForegroundService … reason=fgservice`).

The failure mode is the Restricted battery setting, which strips the foreground state
silently. The app now checks `ActivityManager.isBackgroundRestricted()`: the console says so
with a button to the setting, and the service posts one notification if it happens while
serving. (The first time, this was triggered by a test step of mine: resetting
`RUN_ANY_IN_BACKGROUND` to `default`, which Android treats as restricted. `cmd appops reset`
restores the real default, `allow`.)

## Soak

`tools/compat/soak.py`, a request every 30 s for 10 minutes, screen off, unplugged, **no
Doze whitelist and appops at their defaults**: **20 of 20 answered**, median 0.29 s, worst
0.64 s. The phone entered Doze and app standby on its own during the run (`netpolicy`:
`blocked=DOZE|APP_STANDBY|APP_BACKGROUND, allowed=FOREGROUND, effective=NONE`), the service
stayed foreground throughout, the freezer logged nothing against it, and the battery went
from 48% to 47% with the model resident and the wake lock held.

Tailscale did not answer during the soak; the phone's Tailscale app is known (from earlier
notes) to suspend with the screen off while the LAN address keeps working. That is the VPN
app's behaviour, not the server's.

## Downloading on the phone

`tools/execuserve pull experimentalmachines/Qwen3-1.7B-ExecuTorch Qwen3-1.7B-8da4w-gptq-4k.pte`
asks the app to download over the phone's own network with the same code the Models screen
uses. 1.29 GB in about 100 s, with the phone **locked and dozing**, pinned to revision
`7fb16c28…` and checked against SHA-256 `40d36063…` before it was installed.

## Qwen3-1.7B-8da4w-gptq-4k

SDK suite: 15 of 15 (after one test fix: a reasoning stream cut by `max_output_tokens`
correctly ends with `response.incomplete`, which the SDK's `stream().get_final_response()`
helper does not accept; OpenAI's own API behaves the same). Reasoning came back as a
`reasoning` item and in `reasoning_content`; "Is 17 prime?" was answered correctly after
about 1,000 characters of thought. The tool loop's follow-up reused 257 of 284 prompt
tokens.

| Screen off | |
|---|---|
| Turn 1, cold, 2,279 tokens | prefill 16,687 ms (137 tok/s), wall 17.86 s |
| Turn 2, 2,290 of 2,313 cached | prefill 313 ms, wall **1.46 s** |
| Turn 3, 2,324 cached, 208 out | decode 9.5 tok/s, wall 22.3 s |
| 26-token prompt, 213 out | decode 25.1 tok/s |

Qwen3's decode falls from 25 tok/s on a short context to about 10 tok/s at 2.3k tokens,
where LFM2.5 held 32 to 34 tok/s: every one of Qwen3's 28 layers attends over the whole
context, while LFM2.5 is mostly short convolutions. For long conversations on this phone
LFM2.5 is the faster model by a wide margin.

## Not tested here

- The console on the phone: the phone locked itself with a secure lock screen partway
  through, and nothing here should get past that.
- Deep Doze specifically (HyperOS would not enter it on command), heat under sustained load,
  and hours-long soaks.
