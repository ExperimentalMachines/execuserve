"""Build the MediaTek NPU runtime (NPU prefill, CPU decode) for ExecuTorch v1.5.1 on Modal.

The runner and its JNI layer are OpenWeights' (tools/npu there), carried in mediatek/: the
release/1.4 patch they were cut against applies to v1.5.1 unchanged, and the JNI entry points
are renamed to this app's NeuroPilotBridge. The steps are OpenWeights' build_pd_libs.sh,
including its check that the MediaTek project linked the archives just built, not stale ones.

    modal run --detach build_mediatek.py
    modal volume get execuserve-executorch mediatek-libs/v1.5.1-execuserve ./out

Produces libexecutorch_pd_jni.so, libneuron_backend.so and MediaTek's
libneuron_buffer_allocator.so (from the NeuroPilot Express SDK, which permits shipping it in
object form inside an application). The NeuroPilot adapter itself is the phone's own copy.
"""

from __future__ import annotations

import filecmp
import pathlib
import shutil
import subprocess

import modal

TAG = "v1.5.1"
NDK = "r26c"
# execupack's config/versions.env: NeuroPilot Express SDK 8.0.8, checked against this digest.
SDK_URL = "https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/b1c485de-fca8-4b20-836a-8b33152a3609.gz"
SDK_SHA256 = "42155c3b25e9f9af1bef55534d0700835f32564a289e8cb1e44f0b2433ddab48"
KLEIDIAI = "b87ef9c94f45f11c81a6b1fdaed1b2b45ea58c0c"
HERE = pathlib.Path(__file__).parent
JNI_CLASS = "NeuroPilotBridge"

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("git", "build-essential", "unzip", "wget", "curl", "xz-utils")
    .run_commands(
        "pip install --upgrade pip",
        "pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu",
        "pip install pyyaml ruamel.yaml zstd certifi tomli setuptools wheel requests tqdm",
        "pip install 'cmake>=3.29,<4' ninja && cmake --version | head -1",
        f"curl -sSfL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-{NDK}-linux.zip"
        f" && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip",
        f"curl -sSfL --retry 3 -o /tmp/sdk.tar.gz {SDK_URL}"
        f" && echo '{SDK_SHA256}  /tmp/sdk.tar.gz' | sha256sum -c -"
        f" && mkdir -p /opt/neuropilot && tar -C /opt/neuropilot -xzf /tmp/sdk.tar.gz && rm /tmp/sdk.tar.gz",
        f"curl -sSfL --retry 3 -o /tmp/kai.zip https://github.com/ARM-software/kleidiai/archive/{KLEIDIAI}.zip"
        f" && unzip -q /tmp/kai.zip -d /opt && mv /opt/kleidiai-{KLEIDIAI} /opt/kleidiai && rm /tmp/kai.zip",
    )
    .add_local_dir(HERE / "mediatek", "/root/mediatek", copy=True)
)
app = modal.App("execuserve-executorch-mediatek", image=image)
volume = modal.Volume.from_name("execuserve-executorch", create_if_missing=True)


def find_one(root: pathlib.Path, name: str) -> pathlib.Path:
    hits = sorted(p for p in root.rglob(name) if "arm64" in str(p) or name.endswith(".h"))
    if not hits:
        hits = sorted(root.rglob(name))
    if not hits:
        raise RuntimeError(f"{name} not found under {root}")
    print(f"==> {name}: {hits[0]}", flush=True)
    return hits[0]


@app.function(cpu=(16.0, 16.0), memory=(32 * 1024, 48 * 1024), timeout=3 * 3600, volumes={"/vol": volume}, retries=0)
def build() -> list[str]:
    src = pathlib.Path("/root/executorch")
    run = lambda cmd, **kw: subprocess.run(cmd, check=True, **kw)  # noqa: E731
    run(["git", "clone", "--depth", "1", "--branch", TAG, "--recurse-submodules", "--shallow-submodules",
         "https://github.com/pytorch/executorch.git", str(src)])
    run(["git", "apply", "/root/mediatek/executorch-pd.patch"], cwd=src)
    sdk = pathlib.Path("/opt/neuropilot")
    shutil.copy2(find_one(sdk, "NeuronAdapter.h"), src / "backends/mediatek/runtime/include/api/NeuronAdapter.h")
    allocator = find_one(sdk, "libneuron_buffer_allocator.so")
    for source in ("mtk_pd_disaggregated_jni.cpp", "mtk_pd_disaggregated_runner.cpp", "cpu_forward_bench.cpp"):
        shutil.copy2(f"/root/mediatek/{source}", src / "examples/mediatek/executor_runner" / source)

    ndk = f"/opt/android-ndk-{NDK}"
    out = src / "cmake-android-out"
    sub = out / "examples/mediatek"
    toolchain = f"{ndk}/build/cmake/android.toolchain.cmake"
    common = [f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26",
              "-DCMAKE_BUILD_TYPE=Release", "-G", "Ninja"]
    configure = ["cmake", "-S", str(src), "-B", str(out), *common,
                 f"-DEXECUTORCH_BUILD_PRESET_FILE={src}/tools/cmake/preset/llm.cmake",
                 "-DEXECUTORCH_BUILD_NEURON=ON", f"-DCMAKE_INSTALL_PREFIX={out}", "-DKLEIDIAI_SOURCE_DIR=/opt/kleidiai"]
    for attempt in range(3):
        if subprocess.run(configure).returncode == 0:
            break
        print(f"==> configure failed (attempt {attempt + 1}); retrying", flush=True)
    else:
        raise RuntimeError("cmake configure failed three times")
    run(["cmake", "--build", str(out), "--target", "install", "-j", "16"])
    # Where the package config is installed moved between releases (1.4: lib/cmake/ExecuTorch);
    # find it rather than assume.
    config = next((p for p in out.rglob("*") if p.name.lower() in ("executorch-config.cmake", "executorchconfig.cmake")
                   and "_deps" not in str(p)), None)
    if config is None:
        raise RuntimeError("no executorch-config.cmake under the install tree")
    gflags = next((p for p in out.rglob("*") if p.name.lower() in ("gflags-config.cmake", "gflagsconfig.cmake")), None)
    if gflags is None:
        raise RuntimeError("no gflags-config.cmake under the install tree")
    print(f"==> executorch config: {config}; gflags: {gflags}", flush=True)
    # The NDK toolchain confines find_package to its sysroot; these packages are this build's.
    run(["cmake", "-S", str(src / "examples/mediatek"), "-B", str(sub), *common,
         f"-Dexecutorch_DIR={config.parent}", f"-Dgflags_DIR={gflags.parent}",
         "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"])
    run(["cmake", "--build", str(sub), "--target", "executorch_pd_jni", "-j", "16"])

    # OpenWeights' check: the MediaTek project links the parent's installed archives, so they
    # must be the ones this build just wrote (an unoptimised XNNPACK decoded at a quarter speed).
    for archive in ("backends/xnnpack/third-party/XNNPACK/libXNNPACK.a", "backends/xnnpack/libxnnpack_backend.a",
                    "extension/llm/custom_ops/libcustom_ops.a", "kernels/optimized/libcpublas.a", "libexecutorch_core.a"):
        built, installed = out / archive, out / "lib" / pathlib.Path(archive).name
        if not filecmp.cmp(built, installed, shallow=False):
            raise RuntimeError(f"installed {installed.name} differs from the one just built")

    dest = pathlib.Path("/vol/mediatek-libs/v1.5.1-execuserve/arm64-v8a")
    dest.mkdir(parents=True, exist_ok=True)
    strip = f"{ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    built = []
    for path, name in ((sub / "libexecutorch_pd_jni.so", "libexecutorch_pd_jni.so"),
                       (out / "backends/mediatek/libneuron_backend.so", "libneuron_backend.so")):
        run([strip, "--strip-unneeded", "-o", str(dest / name), str(path)])
        built.append(f"{name} {(dest / name).stat().st_size}")
    shutil.copy2(allocator, dest / "libneuron_buffer_allocator.so")
    built.append(f"libneuron_buffer_allocator.so {(dest / 'libneuron_buffer_allocator.so').stat().st_size}")
    (dest.parent / "SOURCE.txt").write_text(
        f"pytorch/executorch {TAG} + mediatek/executorch-pd.patch (OpenWeights); NDK {NDK}; NeuroPilot SDK {SDK_SHA256[:12]}\n")
    volume.commit()
    print("==> built", built, flush=True)
    return built


@app.local_entrypoint()
def main():
    print(build.remote())
