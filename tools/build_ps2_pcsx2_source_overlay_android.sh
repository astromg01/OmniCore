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
# Alpha 6 #30 keeps the Visibility v2 ABI for compatibility, but retires the
# per-primitive counters from the release hot path after the device study.
grep -Fq 'OmniVisibilityTelemetry::RecordFastCull' "$SRC/pcsx2/GS/GSState.cpp"
grep -Fq 'OmniVisibilityTelemetry::RecordLegacyCull' "$SRC/pcsx2/GS/GSState.cpp"
grep -Fq 'inline void RecordFastCull(bool) {}' "$SRC/pcsx2/GS/OmniVisibilityTelemetry.h"
grep -Fq 'inline void RecordLegacyCull(bool) {}' "$SRC/pcsx2/GS/OmniVisibilityTelemetry.h"
grep -Fq 'source=omnicore-gs-visibility-retired' "$SRC/platforms/android/app/src/main/cpp/native-lib.cpp"

# Alpha 6 only needs the patched emucore. The official nightly import remains
# responsible for Java/resources and dependent native libraries. Building the
# upstream APK would unnecessarily run AndroidX AAR metadata checks.
git -C "$SRC" submodule update --init --recursive --depth=1
chmod +x "$GRADLE"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

install_sdk_packages() {
  local rc
  set +o pipefail
  yes | "$SDKMANAGER" "$@" >/dev/null 2>&1
  rc=${PIPESTATUS[1]}
  set -o pipefail
  return "$rc"
}

if [[ -n "$SDK" && -x "$SDKMANAGER" ]]; then
  # #30 follows the current ARMSX2 release toolchain. Upstream records a
  # measurable Android performance gain from NDK 29, while the ABI/device floor
  # is still controlled by minSdk and -march rather than the NDK version.
  install_sdk_packages 'platforms;android-36' 'ndk;29.0.14206865' 'cmake;3.31.6'
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

  echo "=== OmniCore patched PCSX2 native-only core: $name page=$page NDK29 armv8-a+outline-atomics ==="
  rm -rf "$ROOT/app/.cxx" "$ROOT/app/build/intermediates/cxx"

  # One universally safe ARMv8.0 binary is retained for Alpha 6. Outline
  # atomics lets modern ARM cores dispatch to LSE without making the APK SIGILL
  # on older A53-class devices. PGO + LTO remain enabled as before.
  "$GRADLE" -p "$ROOT" :app:externalNativeBuildPlayRelease \
    "-Parmsx2.hostPageSize=$page" \
    "-Parmsx2.nativeLibName=$name" \
    "-Parmsx2.ndkVersion=29.0.14206865" \
    "-Parmsx2.march=armv8-a" \
    "-Parmsx2.marchExtra=-moutline-atomics" \
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
      libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libz.so|libstdc++.so|libEGL.so|libGLESv1_CM.so|libGLESv2.so|libGLESv3.so|libvulkan.so|libOpenSLES.so|libaaudio.so|libamidi.so|libjnigraphics.so|libnativewindow.so|libmediandk.so|libcamera2ndk.so|libbinder_ndk.so|libneuralnetworks.so|libsync.so) continue ;;
    esac
    test -s "$DEST/$dep" || { echo "Patched core dependency missing: $dep" >&2; exit 1; }
  done < <("$READELF" -d "$core" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done

echo 'OMNICORE_PCSX2_ALPHA6_30_SOURCE_BUILD_OK cores=4k+16k ndk29=1 armv8_baseline=1 outline_atomics=1 pgo_or_lto=1 visibility_hotpath_retired=1'
