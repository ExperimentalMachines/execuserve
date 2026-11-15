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
#
# The base is pinned, file and digest, so the same VERSION is always built from the same base;
# changing either is a new VERSION. Upstream's release workflow
# (.github/workflows/android-release-artifacts.yml) puts each flavor's AAR on S3 when a release
# is tagged and on Maven Central days later (1.5.1: 23 September and 1 October). 1.6.0 was not
# on Maven yet, so this base is the S3 build of the tag: Gradle's debug variant, whose classes
# differ from Maven's only in the suffix Kotlin gives internal names
# (toJniArrays$executorch_android_debug; javap shows nothing else). Its POM is POM_FROM's on
# Maven, an earlier release whose dependencies are the same: extension/android's build.gradle
# and gradle/libs.versions.toml declare the same ones at v1.5.1 and v1.6.0.
set -euo pipefail
LIBS="${1:?directory with arm64-v8a/*.so}"
MTK_LIBS="${2:-}"
RELEASE=1.6.0
# A rebuilt binary is a new version: Gradle and anyone holding the old file must not mistake one for the other.
VERSION="$RELEASE-execuserve.1"
BASE_URL="https://ossci-android.s3.amazonaws.com/executorch/release/$RELEASE-vulkan/executorch.aar"
BASE_SHA256=c2c15d39d6f33f01444671c1bfcec55b2c88b1f93de0c11f11b46ffece610d9f
POM_FROM=1.5.1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/android/executorch/maven/org/experimentalmachines/executorch/executorch-android/$VERSION"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

curl -fsSL "$BASE_URL" -o "$WORK/upstream.aar"
echo "$BASE_SHA256  $WORK/upstream.aar" | shasum -a 256 -c -
curl -fsSL "https://repo1.maven.org/maven2/org/pytorch/executorch-android-vulkan/$POM_FROM/executorch-android-vulkan-$POM_FROM.pom" \
  -o "$WORK/upstream.pom"
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
python3 - "$WORK/upstream.pom" "$DEST/executorch-android-$VERSION.pom" "$POM_FROM" "$VERSION" <<'PY2'
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
