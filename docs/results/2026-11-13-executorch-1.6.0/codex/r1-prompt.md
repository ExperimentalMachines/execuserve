You are an adversarial reviewer. Work read-only: do not change, build or install anything.
Your job is to find what this upgrade breaks, not to confirm that it works.

## What changed

ExecuServe is an Android app that serves LLMs over HTTP from the
phone through ExecuTorch. It is being moved from ExecuTorch 1.5.1 to 1.6.0 (tagged upstream on
2026-11-12). The uncommitted change is in `changes.diff` in your working directory, plus one
new untracked directory:

    android/executorch/maven/org/experimentalmachines/executorch/executorch-android/1.6.0-execuserve.1/

which holds the AAR and POM that `tools/executorch/assemble.sh` produced. The 1.5.1 AAR it
replaces is beside it (1.5.1-execuserve.4/).

How the AAR is made: `tools/executorch/build_libs.py` builds arm64-v8a `libexecutorch.so` and
`libqnn_executorch_backend.so` from the v1.6.0 tag with `tools/executorch/patch_qnn.py`
applied; `build_mediatek.py` builds the MediaTek libraries with `mediatek/executorch-pd.patch`;
`assemble.sh` takes Java classes, x86_64 libraries and the manifest from upstream's
executorch-android-vulkan AAR and swaps in ours. 1.6.0 is not on Maven Central yet, so the base
came from upstream's S3 release bucket
(https://ossci-android.s3.amazonaws.com/executorch/release/1.6.0-vulkan/executorch.aar) and the
POM from Maven's 1.5.1 (env POM_FROM=1.5.1).

Deliberate choices you should challenge, not accept:
- The QNN backend is still compiled against QAIRT 2.37 headers, and the app still ships
  Qualcomm's `com.qualcomm.qti:qnn-runtime:2.39.0` HTP drivers, although upstream 1.6.0 pins
  QAIRT 2.51.
- The Java/Kotlin classes come from an S3 AAR that is Gradle's debug variant, not Maven's
  release variant.
- The POM is 1.5.1's with the version rewritten.
- `QnnSupport`/`VulkanSupport` now key their "this phone refused the delegate" record on
  `ExecuTorchRuntime.VERSION` (from the version catalog) instead of a literal "1.5.1".

## Material

- Upstream trees (shallow, no submodules): `../up/v1.5.1`, `../up/v1.6.0`; a few v1.4.0 files
  in `../up/v1.4.0`.
- Extracted upstream AARs: `../aars/x-mvn-1.5.1-vulkan`, `../aars/x-s3-1.5.1-vulkan`,
  `../aars/x-s3-1.6.0-vulkan` (each has `classes.jar`, `cls/` unpacked, `javap.txt`, `jni/`).
- Our built native libraries: `../native/v1.6.0-execuserve/arm64-v8a/` and
  `../native/mtkdl/v1.6.0-execuserve/arm64-v8a/`, with the Modal build log at
  `../build-libs.log` and `../build-mtk.log`.
- Published exports and the ExecuTorch version each was made with:
  `../hf-runtime-versions.txt` (15 are 1.4.0, the rest 1.5.1).
- Tools: JDK at /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home (javap),
  NDK llvm tools at
  /opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/bin
  (llvm-readelf, llvm-nm, llvm-objdump, llvm-strings).
- The app's runtime binding:
  android/executorch/src/main/kotlin/org/experimentalmachines/execuserve/executorch/

You have no network access.

## What to look for

Work from the code and binaries, and cite file:line or the command you ran. In particular:

1. Native/Java mismatch. Every `native` method and every Java/Kotlin method the native code
   calls back (fbjni `registerNatives`, `javaClassStatic()->getMethod`, field lookups) must
   exist with the same signature on both sides: our 1.6.0 `libexecutorch.so` against the
   classes in the S3 1.6.0 AAR. A mismatch crashes at load or at first call.
2. The QNN path with 2.37 headers on 1.6.0 source and a 2.39 runtime. Does any 1.6.0 runtime
   code assume a newer QNN API or behaviour without a guard? Did any context-binary or
   custom-protocol format the .pte files carry change between 1.5.1 and 1.6.0, so that a
   1.5.1-exported QNN .pte would no longer load?
3. Old .pte files on the 1.6.0 runtime: 1.5.1 and 1.4.0 exports for XNNPACK, Vulkan, QNN and
   MediaTek. Schema, delegate payload versions, operator/kernel registration, metadata method
   names the app reads (see `ExecuTorchRuntime.kt`, e.g. `get_max_context_len`,
   `get_state_reset_at_zero`), and the app's own `runtimeMismatch` rule
   (shared/catalog/.../HfCatalog.kt) and what users will now see.
4. Behaviour changes in the generic text runner, the JNI layer, the tokenizer, the threadpool,
   XNNPACK and Vulkan between 1.5.1 and 1.6.0 that change what ExecuServe gets: stop, reset,
   prefill, BOS/EOS, temperature defaults, UTF-8 streaming, thread counts, memory.
5. patch_qnn.py on 1.6.0: does it still fix what it says, and does anything in 1.6.0 make one
   of its edits wrong or redundant?
6. The MediaTek patch and runner on 1.6.0.
7. assemble.sh: correctness of the S3 fallback, the digest check, the POM rewrite, and what
   happens when Maven publishes 1.6.0 later (will a rerun produce a different artifact under
   the same version?).
8. Anything else in the diff: comments that now say something false, versions left behind,
   the refusal-record key change.

For each finding give: what breaks, for whom, how sure you are (confirmed from code/binary,
or inferred), and the evidence. Rank by severity. Say plainly if you find nothing serious in an
area, and list what you could not determine without a device.
