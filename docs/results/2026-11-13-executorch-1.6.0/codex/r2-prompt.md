You are an adversarial reviewer, second round. Work read-only: change, build and install nothing.

Round 1 (your earlier review, `round1.md` here) found three problems in ExecuServe's move from
ExecuTorch 1.5.1 to 1.6.0. They have been addressed in the working tree; the full uncommitted
diff is `changes.diff` here.

1. P1, QNN prompt processor abort: `tools/executorch/patch_qnn.py` gains an edit to
   `examples/qualcomm/oss_scripts/llama/runner/prompt_processor.cpp` that replaces the
   `context_len - ar_len` ET_CHECK_MSG with a logged `Error::InvalidArgument`. The rebuilt
   library is `../native2/v1.6.0-execuserve/arm64-v8a/libexecutorch.so` (build log
   `../build-libs-2.log`); a patched copy of the runner sources is in
   `../up/patchtest2/v1.6.0/examples/qualcomm/oss_scripts/llama/runner/`.
2. P2, assemble.sh: the base AAR is now pinned by URL and SHA-256, and POM_FROM is fixed in the
   script.
3. P3, Vulkan: `VulkanSupport.kt` matches "require 8-bit storage buffer support".

Also changed since round 1: README badge, a build_libs.py comment about graph splitting.

Check, citing file:line or a command:
- Is each fix correct and complete? For P1: is every abort reachable from the Android
  LlmModule path through the QNN runner (generate, prefill, the prompt processor, the token
  generator, the KV manager) on a too-long or edge-case prompt now an error, or does another
  ET_CHECK remain reachable with input a client controls (an HTTP request's text)? Does the
  error leave the runner's state (cur_pos_, KV cache, stop flag) consistent for the next
  request? Does the app (ExecuTorchRuntime.kt asOverflow, Engine) turn the new log line into
  a context overflow as intended?
- Did any fix introduce a new problem?
- Anything round 1 missed that you now see, ranked by severity, with how sure you are.

Material is as in round 1: ../up (v1.4.0 files, v1.5.1, v1.6.0), ../aars, ../native,
../native2, the JDK at /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home and the
NDK llvm tools at
/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/bin.
No network. Be plain about what is confirmed and what is inferred, and do not pad.
