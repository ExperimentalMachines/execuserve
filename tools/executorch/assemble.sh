#!/usr/bin/env bash
# Packages the libraries build_libs.py produced as the Maven artifact the app resolves
# (gradle/libs.versions.toml: org.experimentalmachines.executorch:executorch-android).
#
#   tools/executorch/assemble.sh <dir holding arm64-v8a/libexecutorch.so and libqnn_executorch_backend.so> \
#       [<dir holding arm64-v8a/libexecutorch_pd_jni.so, libneuron_backend.so, libneuron_buffer_allocator.so>]
#
# The second directory is build_mediatek.py's output: MediaTek's NPU runtime, a separate
# library the app loads only on a phone whose NeuroPilot adapter loads.
#
# The Java and Kotlin API is unchanged by the patches, so the classes, the x86_64 libraries (for
# the emulator, CPU and GPU only) and the POM's dependencies come from ExecuTorch's published
# executorch-android-vulkan AAR at the same release; only arm64-v8a's libexecutorch.so is
# replaced and the QNN backend library added.
set -euo pipefail
LIBS="${1:?directory with arm64-v8a/*.so}"
MTK_LIBS="${2:-}"
RELEASE=1.5.1
# A rebuilt binary is a new version: Gradle and anyone holding the old file must not mistake one for the other.
VERSION="$RELEASE-execuserve.2"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/android/executorch/maven/org/experimentalmachines/executorch/executorch-android/$VERSION"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

base="https://repo1.maven.org/maven2/org/pytorch/executorch-android-vulkan/$RELEASE/executorch-android-vulkan-$RELEASE"
curl -fsSL "$base.aar" -o "$WORK/upstream.aar"
curl -fsSL "$base.pom" -o "$WORK/upstream.pom"
mkdir -p "$WORK/aar" && (cd "$WORK/aar" && unzip -q ../upstream.aar)
for lib in libexecutorch.so libqnn_executorch_backend.so; do
  [ -f "$LIBS/arm64-v8a/$lib" ] || { echo "missing $LIBS/arm64-v8a/$lib" >&2; exit 1; }
  cp "$LIBS/arm64-v8a/$lib" "$WORK/aar/jni/arm64-v8a/$lib"
done
if [ -n "$MTK_LIBS" ]; then
  for lib in libexecutorch_pd_jni.so libneuron_backend.so libneuron_buffer_allocator.so; do
    [ -f "$MTK_LIBS/arm64-v8a/$lib" ] || { echo "missing $MTK_LIBS/arm64-v8a/$lib" >&2; exit 1; }
    cp "$MTK_LIBS/arm64-v8a/$lib" "$WORK/aar/jni/arm64-v8a/$lib"
  done
fi
mkdir -p "$DEST"
# A fresh archive, moved into place: zip into an existing one would keep entries this build
# no longer has (the MediaTek libraries of an earlier run, say).
(cd "$WORK/aar" && zip -q -r -X "$WORK/out.aar" .)
mv "$WORK/out.aar" "$DEST/executorch-android-$VERSION.aar"
# Our coordinates, upstream's dependencies. The Gradle-metadata marker goes: this artifact
# publishes no .module file, and Gradle would otherwise look for one.
python3 - "$WORK/upstream.pom" "$DEST/executorch-android-$VERSION.pom" "$RELEASE" "$VERSION" <<'PY2'
import sys
src, dst, release, version = sys.argv[1:5]
pom = src and open(src).read()
pom = "\n".join(l for l in pom.splitlines() if "published-with-gradle-metadata" not in l) + "\n"
for old, new in (("<groupId>org.pytorch</groupId>", "<groupId>org.experimentalmachines.executorch</groupId>"),
                 ("<artifactId>executorch-android-vulkan</artifactId>", "<artifactId>executorch-android</artifactId>"),
                 (f"<version>{release}</version>", f"<version>{version}</version>")):
    assert old in pom, old
    pom = pom.replace(old, new, 1)
open(dst, "w").write(pom)
PY2
grep -q "<artifactId>executorch-android</artifactId>" "$DEST/executorch-android-$VERSION.pom"
ls -la "$DEST"
