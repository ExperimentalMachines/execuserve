**The reported long-prompt abort is fixed in the packaged AAR. The assembly pin and Vulkan matcher also work. I found another P1 in QNN, plus several carried defects round 1 missed.**

Below, `Q/` means `../up/patchtest2/v1.6.0/examples/qualcomm/oss_scripts/llama/runner/`; `U/` means `../up/v1.6.0/`.

1. **P1 — Switching QNN models can write past a heap buffer. High confidence; confirmed source and binary, no device reproduction.**

   `Q/decoder_runner.h:63` declares `static std::vector<float> logits_f(vocab_size)`. It is allocated once, but lines 73–82 write using **each subsequent model’s vocabulary size**, without resizing.

   Generate with a smaller-vocabulary QNN model, then a larger one in the same process: the second model writes beyond that allocation. Unloading the first model or allowing only one resident does not reset a function-static buffer. Ordinary short prompts suffice.

   Local upstream configs illustrate the size difference: SmolLM2-135M has 49,152 entries (`U/examples/models/smollm2/135M_config.json:11`), Qwen3-0.6B has 151,936 (`U/examples/models/qwen3/config/0_6b_config.json:12`). These are source configs, not verification of downloaded PTE shapes.

   The rebuilt binary retains the one-time initialization guard and unchecked writes. This defect also exists in 1.5.1. Make the buffer instance-owned and size it for the current vocabulary.

2. **P2 — A first-token end marker leaves the engine’s cached conversation inconsistent with native KV state. High confidence, conditional on that output.**

   `Q/runner.cpp:527` delivers the prefill-generated token before entering the token generator. If that token is the end marker, the engine requests stop. [patch_qnn.py:62](tools/executorch/patch_qnn.py:62) then clears that request at decode entry.

   The generator consumes the end marker into KV before the next callback reissues stop (`U/examples/qualcomm/oss_scripts/llama/runner/token_generator.cpp:299`, `:322`). Meanwhile, [Pipeline.kt:92](shared/engine/src/commonMain/kotlin/org/experimentalmachines/execuserve/engine/Pipeline.kt:92) ignores the extra callback, and [Engine.kt:737](shared/engine/src/commonMain/kotlin/org/experimentalmachines/execuserve/engine/Engine.kt:737) still considers the ending clean and reusable. The next continuation re-feeds an end marker already cached.

   Round 1’s “reissuing stop compensates” conclusion was incomplete. Clear the flag before the first callback, preserving subsequent stop requests through decode entry.

3. **P2 — Prefill silently discards execution failures. High confidence in code; no prompt-triggered delegate failure demonstrated.**

   `Q/prompt_processor.cpp:346` discards `decoder_runner_->step(...)`’s `Result`. It then updates KV at line 360 and samples the output buffer at line 377. The decoder explicitly returns execution errors (`Q/decoder_runner.cpp:72`).

   A failed prefill block can therefore advance cache state and produce a nominally successful response from stale or unreliable outputs. The app never receives the error needed to evict the session. Check and propagate the result before updating KV. This is separate from—and predates—the new capacity guard.

4. **P2 — Old overflow logs can misclassify a later unrelated failure. High confidence in the conditional path.**

   Android retains 16 process-wide log entries (`U/extension/android/jni/log.cpp:21`); reading them does not clear them (`U/extension/android/jni/jni_layer.cpp:544`). The exception factory appends that history, and [ExecuTorchRuntime.kt:342](android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/ExecuTorchRuntime.kt:342) classifies overflow solely by substring.

   If a subsequent failure occurs while the previous overflow message remains in the appended tail, it becomes `ContextOverflow`. [Engine.kt:704](shared/engine/src/commonMain/kotlin/org/experimentalmachines/execuserve/engine/Engine.kt:704) then bypasses normal failure eviction; after emitted tokens, it can report a truncated completion instead of the runtime failure. Classification needs a diagnostic associated with the current call.

5. **P2 — QNN ignores request temperature. High confidence; carried defect.**

   [ExecuTorchRuntime.kt:277](android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/ExecuTorchRuntime.kt:277) supplies it, but `Q/runner.cpp:436` never consumes `config.temperature`. The sampler receives only the constructor temperature (`Q/runner.cpp:228`, `Q/decoder_runner.cpp:29`), which the app supplies as `EngineConfig().defaultTemperature`, currently `0.7`.

   Thus an HTTP request specifying `temperature: 0` does not select greedy QNN sampling. Round 1’s temperature conclusion applies to the generic runner, not QNN.

The three requested fixes check out as follows:

- **P1’s specific capacity rejection is correct and preserves state.** `Q/prompt_processor.cpp:253` preserves the original boundary and overflow-safe addition: with context 4096 and AR-128, end position 3968 passes; 3969–4095 now return `InvalidArgument`; 4096 is rejected by the outer guard. Rejection precedes cache rearrangement at line 295, mask initialization and execution. Both callers propagate the error before advancing `cur_pos_` (`Q/runner.cpp:422`, `:519`). The stop flag is untouched; the next decode clears it, subject to finding 2.

  The engine clears `fedText` before generation, marks the session dirty, and resets before the next request after this overflow ([Engine.kt:624](shared/engine/src/commonMain/kotlin/org/experimentalmachines/execuserve/engine/Engine.kt:624), `:639`, `:684`). That recovery path is consistent.

- **Not every native prompt assertion has become an error.** `Q/runner.cpp:442` still aborts on an empty string, although HTTP raw-prompt validation rejects that at `Engine.kt:411`. Line 498 still aborts on an empty tokenization result; the supplied tokenizer submodule is empty, so I cannot establish an accepted HTTP text that produces it.

  More concretely, `Q/prompt_processor.cpp:250` still aborts on a continuation when `is_bert()` is true—defined as prefill AR equalling context length (`Q/prompt_processor.h:90`). A cached continuation or second prefill reaches this assertion for that model shape. **The path is confirmed; applicability to shipped PTEs is unverified.**

  For ordinary AR-128 prefill/AR-1 decode, I found no further length-triggered assertion in the token generator or KV manager: their attention-map size and current-AR checks follow values constructed/rearranged by those callers. Other remaining assertions concern model metadata, tensor types or output binding.

- **The new log reaches `asOverflow`.** The actual AAR’s exception factory, checked with `javap`, appends native logs to the exception message. Both `generate` and `prefillPrompt` throw through that factory (`U/extension/android/executorch_android/src/main/java/org/pytorch/executorch/extension/llm/LlmModule.kt:510`, `:820`). The new text matches `asOverflow` exactly and reaches the engine’s context-overflow handling. Finding 4 limits the classifier’s reliability across calls.

- **P2 assembly:** [assemble.sh:31](tools/executorch/assemble.sh:31) fixes the URL, digest and POM version. The local base AAR’s SHA-256 matches `c2c15d39…610d9f`; the packaged POM exactly matches the script’s transformation of the supplied 1.5.1 POM. `bash -n` passes. The automatic Maven/S3 switch is gone.

- **P3 Vulkan:** [VulkanSupport.kt:80](android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/VulkanSupport.kt:80) matches the exact diagnostic at `U/backends/vulkan/runtime/VulkanBackend.cpp:681`. The README version and graph-splitting comment are corrected. I found no new defect introduced by these targeted edits.

Archive comparison confirms the packaged `libexecutorch.so` exactly matches `native2`—SHA-256 `954d2d4a…86d73`—and contains the new guard diagnostic.

One evidence caveat: `patchtest2` contains duplicate stop methods/fields at `Q/token_generator.h:30` and `:97`; it is not an exact buildable snapshot. I applied the patch **in memory** against fresh upstream source to distinguish those duplicate edits from the intended patch. The recorded build used a fresh clone.

Everything was read-only and offline. No builds, installations or device execution were performed.