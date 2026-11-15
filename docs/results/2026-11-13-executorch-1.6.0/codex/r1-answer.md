I found **two upgrade-specific problems and one serious carried defect in the QNN patch**. I found no native/Java signature mismatch in the supplied artifact. This review was read-only; nothing was changed, built, installed, or executed on a device.

Upstream references below are relative to `../up/v1.6.0/` unless another version is specified.

1. **P1 — Long QNN prompts can still abort the server process. Carried defect; confirmed from code and binary.**

   The patch replaces the runner’s fatal check with a recoverable error, but leaves a stricter fatal check inside the prompt processor.

   For a hybrid model with a 4,096-token context and AR-128 prefill, an end position of 4,000 passes the patched runner check (`end_pos < 4096`) but fails the processor’s requirement (`end_pos <= 4096 − 128`). `ET_CHECK_MSG` aborts the process; Kotlin cannot turn this into `ContextOverflow`.

   This affects QNN users sending long prompts or continuing conversations near the window limit. The app’s approximate character check does not enforce this native limit.

   Evidence:

   - [patch_qnn.py:73](tools/executorch/patch_qnn.py:73) replaces only the outer check; its added prefill check at line 128 has the same gap.
   - `examples/qualcomm/oss_scripts/llama/runner/prompt_processor.cpp:252–259` retains the fatal `context_len − ar_len` check.
   - `runtime/platform/assert.h:28–42` makes this an unconditional abort.
   - `llvm-strings ../native/v1.6.0-execuserve/arm64-v8a/libexecutorch.so` contains both the patched overflow message and `"The sequence length exceeds the maximum limit that the prompt processor can handle."`

   This already existed in the 1.5.1 patch. The claim that oversized prompts now return errors remains false. Fix the deeper check and enforce the actual prefill capacity.

2. **P2 — A later assembly can replace `1.6.0-execuserve.1` with different contents. Confirmed script behavior; future Maven bytes unavailable.**

   Today assembly selects S3 and the 1.5.1 POM. Once Maven publishes 1.6.0, the same command automatically selects Maven’s AAR and POM while retaining the same custom artifact version and overwriting its files.

   That changes the source of Java classes, x86_64 libraries, and packaging metadata without changing the dependency coordinate. Developers and cached builds can consequently use different artifacts called `1.6.0-execuserve.1`.

   A transient Maven failure also selects S3 whenever `POM_FROM` remains set: the fallback does not distinguish “not published” from other download failures.

   Evidence:

   - [assemble.sh:30](tools/executorch/assemble.sh:30): fixed destination version.
   - Lines 38–51: automatic Maven/S3 selection.
   - Lines 63–80: unconditional replacement.
   - `.github/workflows/android-release-artifacts.yml:84–89`: upstream explicitly rebuilds for Maven without replacing the existing S3 artifact.
   - The supplied 1.5.1 Maven/S3 classes already differ in the internal `toJniArrays$executorch_android_release` versus `_debug` name.

   Pin the base artifact and digest for each custom revision, and require a new revision when changing them. The current companion checksum verifies the downloaded S3 file; it does not make this assembly reproducible.

3. **P2 — A new Vulkan capability rejection bypasses the app’s refusal record. Confirmed conditional code path; affected published models not established.**

   Version 1.6.0 now rejects nonconstant BOOL buffer tensors on devices lacking 8-bit storage-buffer support with:

   > Vulkan bool buffer tensors require 8-bit storage buffer support

   The app’s incompatibility matcher recognizes none of that message. Such a failure becomes an ordinary model-load error, without recording the GPU refusal or providing the intended CPU guidance. GPU variants remain offered.

   This affects Vulkan-capable phones lacking that feature **when the selected export contains these tensors**. I could not establish that combination from the supplied model-version inventory.

   Evidence:

   - `backends/vulkan/runtime/VulkanBackend.cpp:663–681`.
   - [VulkanSupport.kt:76](android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/VulkanSupport.kt:76): exhaustive matcher list.
   - [ExecuTorchRuntime.kt:241](android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/ExecuTorchRuntime.kt:241): refusal handling.
   - The new message is present in the supplied `libexecutorch.so`.

   Recognize this specific capability failure; matching every `NotSupported` error would be too broad.

The other requested areas produced these results:

- **JNI and the S3 debug variant: no serious mismatch found.** I compared classfile descriptors against the native registration tables: all **31 fbjni registrations** matched. The remaining **11 conventional JNI methods**—five ASR and six image-processing methods—have matching declarations and exported functions. I also checked the Tensor/EValue fields and factories, exception factory, LLM/ASR callbacks, and MediaTek bridge methods. Relevant registration sites are `extension/android/jni/jni_layer.cpp:640`, `jni_layer_llama.cpp:602`, and the supplied `../aars/x-s3-1.6.0-vulkan/javap.txt`. The AAR’s consumer rules retain these JNI-facing members. Its manifest does not set `android:debuggable`; “debug variant” alone does not demonstrate a production failure.

- **QAIRT 2.37 headers with the 2.39 runtime: no newly unguarded API requirement found on the legacy HTP context path.** The new FCB calls are guarded by `QnnSdkCompatibility.h:14–23`; graph splitting has a separate HTP-version guard in `backends/qualcomm/runtime/backends/htp/host/HtpContextCustomConfig.cpp:44–64`. Those guards use API versions, which must not be confused with QAIRT product-version numbers. Old compiler specs lack `target_options` and follow the existing context-binary path. `QnnCustomProtocol.h/.cpp` are unchanged between tags, including the magic and layout. This is evidence against a newly introduced format break, **not proof that every context binary loads on Qualcomm’s driver**. This build also lacks newer FCB capability despite reporting ExecuTorch 1.6.0.

- **Old PTE schemas and metadata: no concrete loading regression identified.** The supplied 1.4.0 program schema differs through additive delegate-scratch fields; absent scratch is handled in `runtime/executor/method.cpp:1166`. XNNPACK adds an appended operator union member; the Vulkan serialization schema is unchanged between 1.5.1 and 1.6.0. I found no removed names among the previously present operator-name strings, or among the old Vulkan registrations. That does not prove complete kernel compatibility for every export. The app’s `get_max_context_len`, `get_max_seq_len`, and `get_state_reset_at_zero` handling remains intact at [ExecuTorchRuntime.kt:63](android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/ExecuTorchRuntime.kt:63). The supplied 1.4.0 material is insufficient for a full historical Vulkan/QNN payload audit.

- **The 1.4.0 catalog entries visibly change status. Confirmed, but not a demonstrated runtime failure.** The inventory contains **15 affected backend configs: five XNNPACK and ten MediaTek**. [HfCatalog.kt:232](shared/catalog/src/commonMain/kotlin/org/experimentalmachines/execuserve/catalog/HfCatalog.kt:232) now returns `runtimeMismatch=true` for them. On applicable catalog rows, users see “Exported for ExecuTorch 1.4.0; this app runs 1.6.0.” [ModelsScreen.kt:373](android/app/src/main/kotlin/org/experimentalmachines/execuserve/app/ui/ModelsScreen.kt:373) still permits downloading. Also, upstream’s compatibility policy explicitly excludes guarantees for custom operators; the version window alone cannot certify these LLM exports.

- **Runner behavior: no new stop/reset/prefill regression established.** The generic text runner, prefiller, and relevant LLM JNI implementation are unchanged between tags. The generation-builder temperature default changes from `0.8` to `-1`, meaning “inherit the module temperature”; ExecuServe explicitly supplies temperature, so its requests are unaffected. Android streaming still uses `utf8_check_validity` at `jni_layer_llama.cpp:259–272`; the newer text-stream helper does not establish an Android UTF-8 fix. The QNN patch remains necessary. Its stop flag is still cleared when decoding starts, after the first prefill callback; the app compensates by reissuing stop on subsequent callbacks at `Engine.kt:699–702`.

- **Thread counts, numerical behavior, and memory need separate treatment.** The new affinity cap excludes Android (`extension/threadpool/threadpool.cpp:143`), and same-count pool resets remain no-ops. XNNPACK’s submodule commit is unchanged. Nevertheless, SDPA changes its softmax implementation and reduction work (`extension/llm/custom_ops/op_sdpa_impl.h:596,1134`), and Vulkan changes Mali int4 dispatch and GELU handling. Identical output, latency, and peak memory should not be assumed from unchanged serialization. I found no definite new memory defect in the inspected paths.

- **MediaTek: no serious upgrade-specific break found.** Its backend and runner sources are unchanged between these tags. The carried patch passes read-only `git apply --check` against 1.6.0, and the supplied JNI entry points match the Kotlin bridge. The proprietary allocator’s 4-KB alignment remains an existing limitation; the app already restricts packaging it. NPU-to-CPU state transfer still requires device verification.

- **Current assembly contents and POM: no immediate packaging defect found.** The packaged classes/resources/x86_64 library match the supplied S3 base, and the five replacement arm64 libraries match the supplied build outputs. The S3 SHA-256 matches its companion digest. Dependency declarations are unchanged between tags, so the rewritten 1.5.1 POM is consistent with this source snapshot; its coordinates and metadata-marker removal are correct.

- **Refusal keys and comments:** changing to `ExecuTorchRuntime.VERSION` correctly gives 1.6.0 a fresh attempt. It still excludes the custom artifact revision and driver version, so a later `1.6.0-execuserve.2` repair would retain a `refused:1.6.0` record. Minor inaccuracies remain: [README.md:16](README.md:16) says 1.5; [build_libs.py:25](tools/executorch/build_libs.py:25) overlooks graph splitting when describing disabled features.

Two source-level gaps remain independently of device testing: the tokenizer revision changed from `61a10ac…` to `3fc1df7…`, and TorchAO changed from `5f2baf9…` to `b7ac3aa…`; their submodule contents were not supplied. I therefore cannot fully audit tokenizer/BOS/EOS changes or packed-weight kernel compatibility.

Without devices and representative published PTEs, I could not determine actual QNN driver acceptance, MediaTek cache handoff/reset correctness, Vulkan shader correctness on Adreno/Mali, multilingual streaming behavior, or changes in latency and peak memory. No successful model execution is claimed.