"""Build ExecuTorch v1.5.1's Android native libraries with XNNPACK, Vulkan and QNN on Modal,
with patch_qnn.py applied, the way scripts/build_android_library.sh builds them (its native
half; the Java classes do not change and come from the published AAR).

    modal run --detach build_aar_libs.py
    modal volume get execuserve-executorch aar-libs/v1.5.1-execuserve ./out
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess

import modal

TAG = "v1.5.1"
QAIRT = "2.37.0.250724"
NDK = "r26c"
VULKAN_SDK = "1.4.341.1"
# XNNPACK's KleidiAI pin at the v1.5.1 tag (backends/xnnpack/third-party/XNNPACK/cmake/DownloadKleidiAI.cmake).
KLEIDIAI = "b87ef9c94f45f11c81a6b1fdaed1b2b45ea58c0c"
HERE = pathlib.Path(__file__).parent

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("git", "cmake", "ninja-build", "build-essential", "unzip", "wget", "curl", "xz-utils")
    .run_commands(
        "pip install --upgrade pip",
        "pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu",
        "pip install pyyaml ruamel.yaml zstd certifi tomli setuptools wheel requests tqdm",
        # ExecuTorch 1.5's CMake uses $<BUILD_LOCAL_INTERFACE:...>, which needs CMake 3.26;
        # Debian bookworm's is 3.25. These land in /usr/local/bin, ahead of /usr/bin.
        "pip install 'cmake>=3.29,<4' ninja && cmake --version | head -1",
        f"curl -sSfL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-{NDK}-linux.zip"
        f" && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip",
        f"curl -sSfL -o /tmp/qairt.zip https://softwarecenter.qualcomm.com/api/download/software/sdks/"
        f"Qualcomm_AI_Runtime_Community/All/{QAIRT}/v{QAIRT}.zip"
        f" && unzip -q /tmp/qairt.zip -d /opt/qairt-zip && rm /tmp/qairt.zip"
        f" && ls /opt/qairt-zip/qairt/{QAIRT}/include/QNN | head -3",
        # The Vulkan shaders need LunarG's glslc; the NDK's lacks GL_EXT_integer_dot_product.
        # Same source and version floor as ExecuTorch's CI (.ci/scripts/setup-vulkan-linux-deps.sh).
        f"curl -sSfL --retry 3 -o /tmp/vk.tar.xz https://sdk.lunarg.com/sdk/download/{VULKAN_SDK}/linux/"
        f"vulkansdk-linux-x86_64-{VULKAN_SDK}.tar.xz && mkdir -p /opt/vulkansdk"
        f" && tar -C /opt/vulkansdk -xJf /tmp/vk.tar.xz && rm /tmp/vk.tar.xz"
        f" && /opt/vulkansdk/{VULKAN_SDK}/x86_64/bin/glslc --version | head -1",
        # XNNPACK fetches KleidiAI from gitlab.arm.com at configure time, which has answered 502
        # and 504; ARM's GitHub mirror serves the same commit (XNNPACK's pin), handed over as
        # KLEIDIAI_SOURCE_DIR.
        f"curl -sSfL --retry 3 -o /tmp/kai.zip https://github.com/ARM-software/kleidiai/archive/{KLEIDIAI}.zip"
        f" && unzip -q /tmp/kai.zip -d /opt && mv /opt/kleidiai-{KLEIDIAI} /opt/kleidiai && rm /tmp/kai.zip",
    )
    .env({"PATH": f"/opt/vulkansdk/{VULKAN_SDK}/x86_64/bin:/usr/local/bin:/usr/bin:/bin"})
    .add_local_file(HERE / "patch_qnn.py", "/root/patch_qnn.py", copy=True)
)
app = modal.App("execuserve-executorch-android", image=image)
volume = modal.Volume.from_name("execuserve-executorch", create_if_missing=True)


@app.function(cpu=(16.0, 16.0), memory=(32 * 1024, 48 * 1024), timeout=3 * 3600, volumes={"/vol": volume}, retries=0)
def build() -> list[str]:
    src = pathlib.Path("/root/executorch")
    run = lambda cmd, **kw: subprocess.run(cmd, check=True, **kw)  # noqa: E731
    run(["git", "clone", "--depth", "1", "--branch", TAG, "--recurse-submodules", "--shallow-submodules",
         "https://github.com/pytorch/executorch.git", str(src)])
    run(["python", "/root/patch_qnn.py", str(src)])
    ndk = f"/opt/android-ndk-{NDK}"
    out = "cmake-out-android-arm64-v8a"
    configure = ["cmake", ".", f"-DCMAKE_INSTALL_PREFIX={out}",
         f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
         "-DPYTHON_EXECUTABLE=python", "--preset", "android-arm64-v8a", "-DANDROID_PLATFORM=android-26",
         "-DEXECUTORCH_BUILD_EXTENSION_LLM=ON", "-DEXECUTORCH_BUILD_EXTENSION_LLM_RUNNER=ON",
         "-DEXECUTORCH_BUILD_EXTENSION_ASR_RUNNER=ON", "-DEXECUTORCH_BUILD_EXTENSION_TRAINING=ON",
         "-DEXECUTORCH_BUILD_LLAMA_JNI=ON", "-DEXECUTORCH_BUILD_NEURON=OFF",
         "-DEXECUTORCH_BUILD_QNN=ON", f"-DQNN_SDK_ROOT=/opt/qairt-zip/qairt/{QAIRT}",
         "-DEXECUTORCH_BUILD_VULKAN=ON", "-DXNNPACK_ENABLE_ARM_SME2=ON", "-DFLATCC_ALLOW_WERROR=OFF",
         "-DSUPPORT_REGEX_LOOKAHEAD=ON", "-DCMAKE_BUILD_TYPE=Release", "-DKLEIDIAI_SOURCE_DIR=/opt/kleidiai", f"-B{out}"]
    # Configure downloads third-party sources (KleidiAI from gitlab.arm.com answered 504 once);
    # a transient failure there is retried rather than costing the build.
    for attempt in range(3):
        if subprocess.run(configure, cwd=src).returncode == 0:
            break
        print(f"==> configure failed (attempt {attempt + 1}); retrying", flush=True)
    else:
        raise RuntimeError("cmake configure failed three times")
    run(["cmake", "--build", out, "-j", "16", "--target", "install", "--config", "Release"], cwd=src)
    dest = pathlib.Path("/vol/aar-libs/v1.5.1-execuserve/arm64-v8a")
    dest.mkdir(parents=True, exist_ok=True)
    strip = f"{ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    built = []
    jni = sorted((src / out / "extension/android").glob("*.so"))
    pairs = [(jni[0], "libexecutorch.so"),
             (src / out / "lib/executorch/backends/qualcomm/libqnn_executorch_backend.so", "libqnn_executorch_backend.so")]
    for path, name in pairs:
        run([strip, "--strip-unneeded", "-o", str(dest / name), str(path)])
        built.append(f"{name} {(dest / name).stat().st_size}")
    shutil.copy2("/root/patch_qnn.py", dest.parent / "patch_qnn.py")
    (dest.parent / "SOURCE.txt").write_text(
        f"pytorch/executorch {TAG} + patch_qnn.py; NDK {NDK}; QAIRT {QAIRT} headers; XNNPACK, Vulkan, QNN, LLM JNI\n")
    volume.commit()
    print("==> built", built, flush=True)
    return built


@app.local_entrypoint()
def main():
    print(build.remote())
