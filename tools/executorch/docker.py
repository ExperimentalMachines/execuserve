"""Runs build_libs.py and build_mediatek.py on any x86_64 Linux host with Docker, not on Modal.

The Dockerfile is written from the two scripts' own version constants, with their Modal
images' steps, so both routes build the same thing. Each build is the scripts' own function,
called with Modal's `.local()`; /vol is a directory on the host instead of a Modal volume.
The two run one after the other, since both clone into /root/executorch. Needs 16 vCPU and
about 64 GB of memory (each build runs make -j16), and about 100 GB of disk.

    python3 tools/executorch/docker.py context /tmp/ctx     # write the build context
    # on the Linux host, with the context copied over:
    sudo docker build -t execuserve-native /tmp/ctx
    sudo docker run --rm -v /srv/vol:/vol execuserve-native /root/run.sh build_libs
    sudo docker run --rm -v /srv/vol:/vol execuserve-native /root/run.sh build_mediatek

Then /srv/vol/aar-libs/v1.5.1-execuserve and /srv/vol/mediatek-libs/v1.5.1-execuserve are what
assemble.sh takes. Importing the scripts needs the `modal` package (no account).
"""

from __future__ import annotations

import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE))

import build_libs as libs  # noqa: E402
import build_mediatek as mtk  # noqa: E402

MODAL_VERSION = "1.6.1"

RUN_SH = """#!/bin/bash
# Runs one build's function in this container: run.sh build_libs | build_mediatek
set -euo pipefail
cd /root/tools
python -u -c "
import types, $1 as job
job.volume = types.SimpleNamespace(commit=lambda: None)  # /vol is a bind mount here
print(job.build.local())
"
"""


def dockerfile() -> str:
    assert libs.TAG == mtk.TAG and libs.NDK == mtk.NDK and libs.KLEIDIAI == mtk.KLEIDIAI
    return f"""# The Modal images of build_libs.py and build_mediatek.py as one image (debian_slim, Python 3.11).
FROM python:3.11-slim-bookworm
RUN apt-get update && apt-get install -y git cmake ninja-build build-essential unzip wget curl xz-utils && rm -rf /var/lib/apt/lists/*
RUN pip install --upgrade pip
RUN pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
RUN pip install pyyaml ruamel.yaml zstd certifi tomli setuptools wheel requests tqdm
RUN pip install 'cmake>=3.29,<4' ninja && cmake --version | head -1
RUN curl -sSfL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-{libs.NDK}-linux.zip && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip
RUN curl -sSfL -o /tmp/qairt.zip https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/{libs.QAIRT}/v{libs.QAIRT}.zip && unzip -q /tmp/qairt.zip -d /opt/qairt-zip && rm /tmp/qairt.zip && ls /opt/qairt-zip/qairt/{libs.QAIRT}/include/QNN | head -3
RUN curl -sSfL --retry 3 -o /tmp/vk.tar.xz https://sdk.lunarg.com/sdk/download/{libs.VULKAN_SDK}/linux/vulkansdk-linux-x86_64-{libs.VULKAN_SDK}.tar.xz && mkdir -p /opt/vulkansdk && tar -C /opt/vulkansdk -xJf /tmp/vk.tar.xz && rm /tmp/vk.tar.xz && /opt/vulkansdk/{libs.VULKAN_SDK}/x86_64/bin/glslc --version | head -1
RUN curl -sSfL --retry 3 -o /tmp/kai.zip https://github.com/ARM-software/kleidiai/archive/{libs.KLEIDIAI}.zip && unzip -q /tmp/kai.zip -d /opt && mv /opt/kleidiai-{libs.KLEIDIAI} /opt/kleidiai && rm /tmp/kai.zip
RUN curl -sSfL --retry 3 -o /tmp/sdk.tar.gz {mtk.SDK_URL} && echo '{mtk.SDK_SHA256}  /tmp/sdk.tar.gz' | sha256sum -c - && mkdir -p /opt/neuropilot && tar -C /opt/neuropilot -xzf /tmp/sdk.tar.gz && rm /tmp/sdk.tar.gz
# Only so the scripts import; build.local() runs each function here and nothing calls Modal.
RUN pip install modal=={MODAL_VERSION}
ENV PATH=/opt/vulkansdk/{libs.VULKAN_SDK}/x86_64/bin:/usr/local/bin:/usr/bin:/bin
COPY executorch /root/tools
COPY executorch/patch_qnn.py /root/patch_qnn.py
COPY executorch/mediatek /root/mediatek
COPY run.sh /root/run.sh
"""


def context(out: pathlib.Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    shutil.copytree(HERE, out / "executorch", dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__"))
    (out / "Dockerfile").write_text(dockerfile())
    (out / "run.sh").write_text(RUN_SH)
    (out / "run.sh").chmod(0o755)
    print(f"Build context in {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "context":
        sys.exit(__doc__)
    context(pathlib.Path(sys.argv[2]))
