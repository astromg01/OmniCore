#!/usr/bin/env bash
set -Eeuo pipefail

SRC="${1:-build/third_party/armsx2}"
DEST="${2:-app/src/main/jniLibs/arm64-v8a}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
ROOT="$SRC/platforms/android"
GRADLE="$ROOT/gradlew"
GRADLE_APP="$ROOT/app/build.gradle.kts"

[[ -d "$SRC/.git" ]] || { echo "Missing ARMSX2 clone: $SRC" >&2; exit 1; }
[[ "$(git -C "$SRC" rev-parse HEAD)" == "$PIN" ]] || { echo "Unexpected ARMSX2 source revision" >&2; exit 1; }
test -s "$SRC/pcsx2/GS/OmniVisibilityTelemetry.h"
grep -Fq 'getOmniVisibilitySnapshot' "$SRC/platforms/android/app/src/main/cpp/native-lib.cpp"
grep -Fq 'OmniVisibilityTelemetry::RecordCull' "$SRC/pcsx2/GS/GSState.cpp"

# Alpha 6 Visibility v1 only needs the patched emucore. The official nightly
# import remains responsible for Java/resources and the dependent native
# libraries. Building an APK here would unnecessarily run AndroidX AAR metadata
# checks even though none of those Java/Kotlin dependencies participate in the
# emucore ABI.
git -C "$SRC" submodule update --init --recursive --depth=1
chmod +x "$GRADLE"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

# sdkmanager may close stdin early after consuming its answer. With global
# pipefail enabled that makes `yes | sdkmanager` look like a failure because
# `yes` exits on SIGPIPE even when sdkmanager itself succeeded. Always inspect
# sdkmanager's own PIPESTATUS entry instead.
install_sdk_packages() {
  local rc
  set +o pipefail
  yes | "$SDKMANAGER" "$@" >/dev/null 2>&1
  rc=${PIPESTATUS[1]}
  set -o pipefail
  return "$rc"
}

if [[ -n "$SDK" && -x "$SDKMANAGER" ]]; then
  # The hosted runner exposes API 36. That is sufficient for configuring the
  # Android native toolchain. We deliberately do NOT package the upstream app,
  # so AndroidX libraries whose AAR metadata asks for compileSdk 37 are outside
  # this source-overlay build path.
  install_sdk_packages 'platforms;android-36' 'ndk;28.2.13676358' 'cmake;3.31.6'
fi

if [[ ! -d "$SDK/platforms/android-37" ]]; then
  python3 - "$GRADLE_APP" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
s = s.replace("compileSdk = 37", "compileSdk = 36")
s = s.replace("targetSdk = 37", "targetSdk = 36")
p.write_text(s)
print("PCSX2 source overlay: native-only API 36 configuration enabled; AndroidX packaging skipped")
PY
fi

if command -v rustup >/dev/null 2>&1; then
  rustup target add aarch64-linux-android >/dev/null 2>&1 || true
fi

SHADERC_SYNC="$ROOT/app/src/main/cpp/3rdparty/shaderc/utils/git-sync-deps"
if [[ -f "$SHADERC_SYNC" ]]; then
  python3 "$SHADERC_SYNC"
fi

mkdir -p "$DEST"
PROF="$ROOT/pgo/armsx2.profdata"
PGO_ARGS=(-Parmsx2.pgo=none)
if [[ -s "$PROF" ]]; then
  PROF_ABS="$(cd "$(dirname "$PROF")" && pwd)/$(basename "$PROF")"
  PGO_ARGS=(-Parmsx2.pgo=optimize "-Parmsx2.pgoProfile=$PROF_ABS")
  echo "PCSX2 source overlay: PGO optimize using $PROF_ABS"
else
  echo "PCSX2 source overlay: no bundled PGO profile, using normal release LTO" >&2
fi

build_core() {
  local page="$1"
  local name="$2"
  local out="$DEST/lib${name}.so"
  local built=""

  echo "=== OmniCore patched PCSX2 native-only core: $name page=$page ==="
  rm -rf "$ROOT/app/.cxx" "$ROOT/app/build/intermediates/cxx"

  # Build ONLY the external-native variant. assemblePlayRelease also launches
  # checkPlayReleaseAarMetadata; current AndroidX 1.19/2.11 metadata requires
  # compileSdk 37 and made #26 fail after the C++ build had already started.
  # The native task has no dependency on those AAR metadata checks.
  "$GRADLE" -p "$ROOT" :app:externalNativeBuildPlayRelease \
    "-Parmsx2.hostPageSize=$page" \
    "-Parmsx2.nativeLibName=$name" \
    "${PGO_ARGS[@]}" \
    --no-daemon --stacktrace

  built="$(find "$ROOT/app/build/intermediates/cxx" "$ROOT/app/.cxx" \
    -type f -path '*/arm64-v8a/*' -name "lib${name}.so" -print -quit 2>/dev/null || true)"
  [[ -n "$built" && -s "$built" ]] || {
    echo "Native task completed but lib${name}.so was not found" >&2
    exit 1
  }
  cp "$built" "$out"
  test -s "$out"
  local size
  size="$(stat -c%s "$out")"
  (( size > 10000000 )) || { echo "Patched $name unexpectedly small: $size" >&2; exit 1; }
  echo "PCSX2 source overlay: copied $name from $built ($size bytes)"
}

build_core 0x1000 emucore_4k
build_core 0x4000 emucore_16k

READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$READELF"
for core in "$DEST/libemucore_4k.so" "$DEST/libemucore_16k.so"; do
  table="$($READELF -Ws "$core")"
  grep -Fq 'Java_kr_co_iefriends_pcsx2_NativeApp_initialize' <<< "$table"
  grep -Fq 'Java_kr_co_iefriends_pcsx2_NativeApp_runVMThread' <<< "$table"
  grep -Fq 'Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot' <<< "$table"
  while IFS= read -r dep; do
    [[ -z "$dep" ]] && continue
    case "$dep" in
      libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libz.so|libEGL.so|libGLESv2.so|libvulkan.so|libOpenSLES.so|libaaudio.so|libjnigraphics.so|libnativewindow.so|libstdc++.so) continue ;;
    esac
    test -s "$DEST/$dep" || { echo "Patched core dependency missing: $dep" >&2; exit 1; }
  done < <("$READELF" -d "$core" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done

echo 'OMNICORE_PCSX2_ALPHA6_24_SOURCE_BUILD_OK cores=4k+16k visibility_jni=1 native_only=1 pgo_or_lto=1'
