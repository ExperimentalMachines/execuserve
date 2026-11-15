# ExecuTorch 1.6.0, 2026-11-13

ExecuServe moves from ExecuTorch 1.5.1 to 1.6.0 (tagged 2026-11-12). This page records what
changed upstream that reaches the app, what changed here, how it was checked and what was not.
The two adversarial reviews are in [2026-11-13-executorch-1.6.0/codex/](2026-11-13-executorch-1.6.0/codex/).

## What 1.6.0 changes for this app

Compared tag to tag (v1.5.1 3b60683, v1.6.0 4776f71):

- **Unchanged:** the Android Java/Kotlin API (javap of both AARs: identical signatures), every
  JNI file but an include path (`jni_layer_llama.cpp` byte for byte), the generic text runner,
  Qualcomm's LLM runner (`examples/qualcomm/oss_scripts/llama/runner`, byte for byte), MediaTek's
  backend and runner, the XNNPACK submodule (so the KleidiAI pin), the QNN custom-protocol header
  that .pte files carry, NDK r26c for QNN builds.
- **`LlmGenerationConfig`'s default temperature** is now -1 ("the module's own") instead of 0.8.
  The app sets temperature on every call, so nothing changes.
- **The threadpool** caps its size by CPU affinity on Linux other than Android
  (`!defined(__ANDROID__)`); phones keep the same thread counts.
- **The .pte schema** gains an optional `DelegateCall.scratch`, and the XNNPACK schema an
  appended `XNNApproxGelu`; both additive, so 1.4.0 and 1.5.1 files parse.
- **The tokenizer** (submodule 61a10ac to 3fc1df7) normalizes NFC with real Unicode 17 data
  instead of an approximation: different tokens only for text that is not already NFC, under
  tokenizers that declare NFC (the Qwen family).
- **Vulkan** refuses a graph with non-constant bool buffer tensors on a device without 8-bit
  storage buffers, with a new message.
- **Qualcomm:** upstream now pins QAIRT 2.51 (from 2.37) and adds Flexible Context Binaries and
  HTP graph splitting, both behind SDK version guards.
- **MediaTek:** the release notes call it "temporarily unsupported pending a Python 3.11+
  NeuroPilot converter". That is the exporter; the runtime code is unchanged.
- **Not on Maven Central yet.** Upstream's release workflow put the AARs on its S3 bucket on
  the day; Maven follows days later (1.5.1: 23 September, then 1 October).

## What changed here

- **ExecuTorch 1.6.0-execuserve.1.** `tools/executorch` builds v1.6.0 with the same patches.
  `patch_qnn.py` and the MediaTek patch apply unchanged; the patched Qualcomm runner is
  byte-identical to 1.5.1's. PyTorch for the build's code generators moves to 2.15.0, the
  tag's pin.
- **QAIRT stays at 2.37** for the QNN backend's headers, with Qualcomm's `qnn-runtime` 2.39 HTP
  drivers in the app, so this change moves ExecuTorch and not the Qualcomm SDK. The context
  binaries already exported were compiled with 2.37; a 2.51 binary is not promised to load on
  2.39 drivers. ExecuPack keeps QNN exports on 1.5.1 and QAIRT 2.37 for the same reason.
- **The AAR's base is pinned.** `assemble.sh` takes upstream's S3 build of the tag (the Gradle
  debug variant; its classes differ from Maven's only in Kotlin's suffix for internal names)
  by URL and SHA-256, and the POM from Maven's 1.5.1, whose dependencies are 1.6.0's. Moving to
  Maven's file later is a new version, not a silent swap.
- **A second abort in Qualcomm's runner is now an error** (found in review). The patch already
  turned the runner's own window check into an error, but the prompt processor keeps one
  prefill block free and asserted that separately: a prompt ending in positions 3,969 to 4,095
  of a 4,096 window at AR-128 killed the process. It now returns `InvalidArgument` with a
  "Prompt exceeds KV cache capacity" line, which the app already reports as a full window.
  This was in the 1.5.1 build too.
- **Vulkan's new refusal is recognized**, so a phone that gives it stops being offered GPU files
  instead of failing each load.
- **The GPU and NPU refusal records follow the runtime version** from the version catalog
  instead of a literal "1.5.1", so 1.6.0 gets a fresh attempt on phones that refused 1.5.1.

## How it was checked

- **Builds.** Both native builds ran on Modal from a fresh clone of the tag. Every library is
  16 KB aligned (the build stops otherwise). The strings of the patched fixes are present in
  `libexecutorch.so` and the abort strings are gone. Every file in the AAR was traced to its
  source: our five arm64 libraries by SHA-256, upstream's classes and x86_64 library byte for
  byte.
- **`./gradlew verify`** passed: ktlint, detekt, Android lint, all tests, iOS compile, warnings
  as errors.
- **On an emulator,** the 16 KB-page Android 16 arm64 image that runs the same arm64 library a
  phone gets. The release APK of this tree was built twice: once against 1.5.1-execuserve.4
  (the baseline) and once against 1.6.0-execuserve.1. Each ran the same pass over the two
  CPU exports installed there: Qwen3-1.7B (exported with 1.5.1) and LFM2.5-1.2B-Instruct
  (exported with 1.4.0). The files are in [emulator/](2026-11-13-executorch-1.6.0/emulator/).

| | 1.5.1 | 1.6.0 |
|---|---|---|
| OpenAI SDK suite, each model | 16/16 | 16/16 |
| Anthropic SDK suite, each model | 8/8 | 8/8 |
| Edge cases | 27/28 | 27/28, the same one flagged ("metrics need a key and name no client", a known gap) |
| Greedy probes, LFM2.5-1.2B (1.4.0 file) | | 12 of 12 byte-identical |
| Greedy probes, Qwen3-1.7B | | 9 of 12 identical; 3 differ, all coherent |
| Sequence cache (bench.py), tokens reused on turns 2 and 3 | Qwen3 2,290 and 2,324; LFM2.5 2,088 and 2,117 | the same |
| Overflow, stop string, `max_tokens`, streaming = non-streaming, same answer after a reset | pass | pass |
| Native crashes in logcat | none | none |

- **Greedy text is not bit-exact, because of one line.** 1.6.0's attention softmax uses an
  approximate `exp` (`exp_u20()`, `extension/llm/custom_ops/op_sdpa_impl.h:596`). OpenWeights'
  session rebuilt 1.6.0 with only that line back to `exp()` and got 1.5.1's replies on all 100
  GSM8K questions of the run below, so it is the whole difference on the CPU path; the
  prefill change that skips masked keys changes nothing visible. Reverting it is the way back
  to 1.5.1's exact output if that is ever wanted.
  Qwen3 gave a different haiku and a different first reply in the two-turn probe (so a
  different second turn), and the 256-token answers of both models end a few tokens apart.
  ExecuPack's session measured the same on the Python runtime (macOS arm64, a 2k window each).
  All 20 published CPU files load and run on 1.6.0 with the same methods and metadata. Of the
  16 with a gate reference, 15 keep their 1.5.1 verdict and agreement count, and Phi-4-mini
  goes from 7/8 to 8/8. Qwen2.5-Math-1.5B's "capital of France" smoke answer flips to
  nonsense on 1.6.0, but on a near tie (0.256 against 0.240 at the eighth token), and its gate
  still passes 3/3. On the five 1.4.0 CPU files, the first token of each gate answer on 1.6.0
  matches the 1.4.0 runtime's on 79 of 80 rows; 1.5.1 matched on 78. OpenWeights' session scored an
  LFM2.5-1.2B XNNPACK export (Software Mansion's, through OpenWeights' own 1.5.1 and 1.6.0
  builds) on the same emulator, greedy, 30 questions per set: GSM8K 18 vs 17, IFEval 20 vs 20,
  BFCL 27 vs 27. Replies were identical for 1, 4 and 26 of the 30. A net change of one in 90,
  within seven discordant pairs, is no measured quality difference. Two runs of 1.5.1 against
  itself were word for word identical, so the divergence is 1.6.0's. On this app's own
  LFM2.5-1.2B export (`8da4w-gptq-4k`), the first 30 GSM8K questions went 20 to 16, all four
  changes losses. A fresh 100 (seed 11, no overlap, 1.5.1 and 1.6.0 run in ABBA halves) went
  67 to 65: 8 gained and 10 lost, sign test p = 0.82, with 4 of 100 replies identical. Pooled,
  87 against 81 of 130 (p = 0.29), so the first 4 to 0 was chance. 1.6.0 rewords nearly every
  long reply on the CPU path, and accuracy moves by no more than noise.
- **Accented Latin text now reaches Qwen models.** Asked to repeat "ñandú café naïve",
  Qwen3 on 1.5.1 read a 23-token prompt and answered "nandu cafe naive"; on 1.6.0 the prompt is
  25 tokens and the answer is exact, in all four runs. 1.5.1's tokenizer stripped the accents
  before the model saw them; 1.6.0's NFC fix is the change. LFM2.5 was right on both.
- **Speed: no gross change, and nothing finer can be said here.** Four interleaved runs
  (1.5.1, 1.6.0, 1.6.0, 1.5.1) under a host load of 10 to 18 varied 2 to 10 times between runs
  of the same runtime. OpenWeights' session, with the Mac quiet, measured no difference on
  Qwen3-1.7B, and found that the emulator's two Android crashes that night were watchdog kills
  from host CPU starvation, on both runtimes.

### On a phone, 2026-11-15

The POCO X8 Pro Max (Dimensity 9500s, Mali-G925, HyperOS 3 on Android 16), over USB. The
baseline is the Play release on it, 0.1.1 (74) on 1.5.1. The candidate is the release APK of
0.1.2 (77) built from `939ea4f`, installed after the Play copy was removed. The bundle Play
serves is CI's build of the same commit. Three exports, the same files on both
(SHA-256 checked): LFM2.5-1.2B-Instruct and LFM2.5-2.6B on the CPU at a 32k window, and
Qwen3-1.7B on the GPU (`vulkan/…-gptq-4k`, exported with 1.5.1). Thermal status stayed
none throughout. The files, scripts and a side-by-side are in
[phone/](2026-11-13-executorch-1.6.0/phone/).

| | 1.5.1 | 1.6.0 |
|---|---|---|
| OpenAI SDK suite, LFM2.5-1.2B (CPU) and Qwen3-1.7B (GPU) | 16/16, 16/16 | 16/16, 16/16 |
| Anthropic SDK suite, the same two | 8/8, 8/8 | 8/8, 8/8 |
| Edge cases, with the interleaved-model probe | 27/28 | 27/28, the same one flagged |
| Greedy probes identical | | LFM2.5-1.2B 10 of 11 (only the 6,000-word filler prompt differs), LFM2.5-2.6B 11 of 11, Qwen3-1.7B on the GPU 7 of 10 (a different haiku; the two-turn probe drops an emoji) |
| Tokens reused on turns 2 and 3 | 2,088 and 2,117; 2,076 on turn 3 for LFM2.5-2.6B; 2,290 and 2,324 | the same |
| "ñandú café naïve", Qwen3-1.7B on the GPU | 23 tokens, "nandu cafe naive" | 25 tokens, "ñandú café naïve" |
| Native crashes | none | none |

Speed, from the server's timings, one run each (cold 2k-token prompt, then a short prompt
with 256 tokens out):

| | Prefill tok/s, 1.5.1 → 1.6.0 | Decode tok/s, long prompt | Decode tok/s, short prompt |
|---|---|---|---|
| LFM2.5-1.2B, CPU | 262 → 275 | 35.0 → 35.4 | 42.9 → 43.1 |
| LFM2.5-2.6B, CPU | 101 → 106 | 14.7 → 14.9 | 18.3 → 18.2 |
| Qwen3-1.7B, GPU | 134 → 138 | 9.9 → 9.3 | 18.4 → 18.7 |

No change beyond a few percent either way, which one run cannot separate from noise. During
the baseline the USB link reset once, in LFM2.5-2.6B's 6,000-word probe; the app kept
running, and that model's step was run again.

## What users will see

The catalog reads each export folder's `config.json`, and 15 folders say ExecuTorch 1.4.0:
ten of the thirteen MediaTek (`mtk/mt6991`) folders and five CPU builds (LFM2.5-1.2B-Instruct,
LFM2.5-2.6B, SmolLM2-360M, and both LFM2.5 heretic builds). Upstream promises a file only the
next minor release, so on 1.6.0 the catalog labels those rows "Exported for ExecuTorch 1.4.0;
this app runs 1.6.0". They still download. Counted by window instead, ExecuPack's session found
39 of the 48 MediaTek windows (145 of 178 chunks, in 12 repositories) are 1.4.0 builds, so two
folders labelled 1.5.1 (the LFM2.5 heretic ones) also hold 1.4.0 windows that the app does not
flag. ExecuPack's next `config.json` gives each variant its own `runtime_version`; reading that
first would label them exactly.

The rule is upstream's policy, not a measured failure: LFM2.5-1.2B, one of the fifteen, gave
byte-identical answers on 1.6.0 above. Every MediaTek chunk parses under 1.6.0's schema, and
MediaTek's backend and runner changed only build files between 1.5.1 and 1.6.0. That is
structure only, since no NeuroPilot runtime runs on a host.
Re-exporting them is a separate decision; MediaTek's exporter cannot run on 1.6.0 yet.

## Found in review and left for later

All five were in 1.5.1 as well; none comes from 1.6.0, and none could be tested without a
Snapdragon phone. Details and evidence in [codex/r2-answer.md](2026-11-13-executorch-1.6.0/codex/r2-answer.md).

- **Qualcomm runner, logits buffer:** `decoder_runner.h` sizes a function-static buffer by the
  first model's vocabulary; a second QNN model with a larger vocabulary in the same process
  writes past it. Only one QNN export exists today (Qwen3-1.7B, unpublished).
- **Qualcomm runner, temperature:** the runner samples at the constructor's temperature (the
  app's default, 0.7) and ignores the request's, so `temperature: 0` is not greedy on the NPU.
- **Qualcomm runner, an immediate end marker:** the stop flag is cleared when decoding starts,
  after the first token's callback; if that token ends the reply, the marker enters the KV
  cache while the engine's cached conversation does not record it.
- **Qualcomm prompt processor** ignores a failed block's result and carries on.
- **Overflow detection by log text:** the Android wrapper appends the last 16 runtime log lines,
  not only the current call's, so an overflow line can make a later unrelated failure look
  like a full window.

## Not done

- **The NPUs.** Qualcomm's and MediaTek's NPUs ran nothing on 1.6.0: there was no
  Snapdragon session, and Play builds leave MediaTek's runtime out. What stands for them: the
  QNN payload format did not change (schema and custom-protocol header byte for byte),
  Qualcomm's runner is the 1.5.1 one with one more error path, and the MediaTek backend and
  runner sources are unchanged. The first Snapdragon session should run the Qwen3-1.7B NPU
  export through `tools/compat` and a prompt ending in the last 128 positions of its window.
- **An Adreno GPU** on 1.6.0. Only the POCO's Mali ran Vulkan here.
- **Maven's AAR.** When upstream publishes 1.6.0 to Maven Central, moving the base to it is a
  new version (`1.6.0-execuserve.2`) with its own digest in `assemble.sh`.
