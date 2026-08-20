#!/usr/bin/env bash
set -Eeuo pipefail

SRC="${1:-build/third_party/armsx2}"
DEST="${2:-app/src/main/jniLibs/arm64-v8a}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
ROOT="$SRC/platforms/android"
GRADLE="$ROOT/gradlew"
APK="$ROOT/app/build/outputs/apk/play/release/app-play-release.apk"
GRADLE_APP="$ROOT/app/build.gradle.kts"

[[ -d "$SRC/.git" ]] || { echo "Missing ARMSX2 clone: $SRC" >&2; exit 1; }
[[ "$(git -C "$SRC" rev-parse HEAD)" == "$PIN" ]] || { echo "Unexpected ARMSX2 source revision" >&2; exit 1; }
test -s "$SRC/pcsx2/GS/OmniVisibilityTelemetry.h"
grep -Fq 'getOmniVisibilitySnapshot' "$SRC/platforms/android/app/src/main/cpp/native-lib.cpp"
grep -Fq 'OmniVisibilityTelemetry::RecordCull' "$SRC/pcsx2/GS/GSState.cpp"

# The import path historically consumed the official nightly binary. Alpha 6
# #24 keeps that APK as the source of resources/dependent libraries, but now
# rebuilds ONLY the two emucore variants from the exact pinned source after the
# OmniCore visibility instrumentation is applied.
git -C "$SRC" submodule update --init --recursive --depth=1
chmod +x "$GRADLE"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -n "$SDK" && -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  # GitHub's current Android image carries API 36 but does not yet expose
  # platforms;android-37 through sdkmanager. The wrapper application's target
  # level is irrelevant to the native emucore ABI, so build the pinned source
  # against API 36 when 37 is unavailable instead of blocking Visibility v1.
  if ! yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" \
      'platforms;android-37' 'ndk;28.2.13676358' 'cmake;3.31.6' >/dev/null 2>&1; then
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" \
      'platforms;android-36' 'ndk;28.2.13676358' 'cmake;3.31.6' >/dev/null
  fi
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
print("PCSX2 source overlay: API 36 wrapper fallback enabled; native ABI unchanged")
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

  echo "=== OmniCore patched PCSX2 source core: $name page=$page ==="
  rm -rf "$ROOT/app/.cxx" "$ROOT/app/build/intermediates/cxx"
  rm -f "$APK"

  "$GRADLE" -p "$ROOT" :app:assemblePlayRelease \
    "-Parmsx2.hostPageSize=$page" \
    "-Parmsx2.nativeLibName=$name" \
    "${PGO_ARGS[@]}" \
    --no-daemon --stacktrace

  test -s "$APK"
  unzip -p "$APK" "lib/arm64-v8a/lib${name}.so" > "$out"
  test -s "$out"
  local size
  size="$(stat -c%s "$out")"
  (( size > 10000000 )) || { echo "Patched $name unexpectedly small: $size" >&2; exit 1; }
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

echo 'OMNICORE_PCSX2_ALPHA6_24_SOURCE_BUILD_OK cores=4k+16k visibility_jni=1 pgo_or_lto=1'
