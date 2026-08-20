#!/usr/bin/env bash
set -Eeuo pipefail

SRC="${1:-build/third_party/armsx2}"
WORK="${2:-build/ps2-pcsx2}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
TAG="nightly-20260819"
ASSET="ARMSX2-nightly-20260819-7f0ae7a6c6-Android-arm64.apk"
EXPECTED_SHA256="deb7a553a1d5fe795b2f4ca7a5b8e70fc4bce9536fbf4a1a340e3ac0aee30126"
URL="https://github.com/ARMSX2/ARMSX2/releases/download/${TAG}/${ASSET}"
DIAG="/tmp/ps2-alpha6-build-diagnostic.txt"
LOG="${RUNNER_TEMP:-/tmp}/alpha6-pcsx2-import.log"

on_error() {
  local rc=$?
  {
    echo 'failure_stage=pcsx2-armsx2-official-nightly-import'
    echo "exit_code=$rc"
    echo "source_pin=$PIN"
    echo "binary_tag=$TAG"
    echo "binary_asset=$ASSET"
    if [[ -s "$LOG" ]]; then
      echo '--- last 220 import lines ---'
      tail -n 220 "$LOG"
    fi
  } > "$DIAG"
  exit "$rc"
}
trap on_error ERR
exec > >(tee -a "$LOG") 2>&1

if [[ "$(git -C "$SRC" rev-parse HEAD)" != "$PIN" ]]; then
  echo "Unexpected ARMSX2 source revision" >&2
  exit 1
fi
test -s "$SRC/COPYING.GPLv3"

rm -rf "$WORK"
mkdir -p "$WORK"
APK="$WORK/$ASSET"
STAGE="$WORK/stage"
LIST="$WORK/upstream-apk-listing.txt"

echo "Downloading official ARMSX2 nightly: $URL"
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 30 \
  --output "$APK" "$URL"
test -s "$APK"
echo "$EXPECTED_SHA256  $APK" | sha256sum -c -
unzip -tq "$APK" >/dev/null
sha256sum "$APK" | tee "$WORK/upstream-apk.sha256"
unzip -l "$APK" > "$LIST"

grep -Fq 'lib/arm64-v8a/libemucore_4k.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libemucore_16k.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libEGL_angle.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libGLESv2_angle.so' "$LIST"
grep -Fq 'assets/resources/' "$LIST"

rm -rf "$STAGE"
mkdir -p "$STAGE"
unzip -q "$APK" 'lib/arm64-v8a/*.so' 'assets/resources/*' -d "$STAGE"

for core in libemucore_4k.so libemucore_16k.so; do
  test -s "$STAGE/lib/arm64-v8a/$core"
  size="$(stat -c%s "$STAGE/lib/arm64-v8a/$core")"
  if (( size <= 10000000 )); then
    echo "$core is unexpectedly small ($size bytes)" >&2
    exit 1
  fi
done

JNI_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
rm -f app/src/main/jniLibs/arm64-v8a/libPlay.so app/src/main/jniLibs/armeabi-v7a/libPlay.so

READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$READELF"

is_system_soname() {
  case "$1" in
    libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libz.so|libstdc++.so|libEGL.so|libGLESv1_CM.so|libGLESv2.so|libGLESv3.so|libvulkan.so|libOpenSLES.so|libaaudio.so|libamidi.so|libjnigraphics.so|libnativewindow.so|libmediandk.so|libcamera2ndk.so|libbinder_ndk.so|libneuralnetworks.so|libsync.so)
      return 0 ;;
    *) return 1 ;;
  esac
}

is_gradle_packaged_soname() {
  case "$1" in
    libandroidx.graphics.path.so) return 0 ;;
    *) return 1 ;;
  esac
}

is_omnicore_owned_soname() {
  case "$1" in
    libomnicore_runtime.so|libomnicore_n64_runtime.so|libomnicore_ps2_runtime.so|libpcsx_rearmed_libretro.so|libmupen64plus_next_libretro.so)
      return 0 ;;
    *) return 1 ;;
  esac
}

upstream_native="$WORK/upstream-native-payload.txt"
packaged_native="$WORK/packaged-armsx2-native-payload.txt"
find "$STAGE/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | sort -u > "$upstream_native"
test -s "$upstream_native"
: > "$packaged_native"

while IFS= read -r lib; do
  [[ -z "$lib" ]] && continue
  if is_omnicore_owned_soname "$lib"; then
    echo "Refusing ARMSX2/OmniCore native library collision: $lib" >&2
    exit 1
  fi
  if is_gradle_packaged_soname "$lib"; then
    echo "Keeping Gradle-provided native dependency instead of duplicate upstream copy: $lib"
    continue
  fi
  cp -f "$STAGE/lib/arm64-v8a/$lib" "$JNI_DIR/$lib"
  echo "$lib" >> "$packaged_native"
done < "$upstream_native"
sort -u -o "$packaged_native" "$packaged_native"

for required in libemucore_4k.so libemucore_16k.so libc++_shared.so liblibrashader_capi.so libEGL_angle.so libGLESv2_angle.so; do
  test -s "$JNI_DIR/$required"
done

while IFS= read -r lib; do
  [[ -z "$lib" ]] && continue
  src_lib="$JNI_DIR/$lib"
  while IFS= read -r dep; do
    [[ -z "$dep" ]] && continue
    if is_system_soname "$dep" || is_gradle_packaged_soname "$dep"; then
      continue
    fi
    if [[ ! -s "$JNI_DIR/$dep" ]]; then
      echo "Final ARMSX2 native payload unresolved: $lib -> $dep" >&2
      exit 1
    fi
  done < <("$READELF" -d "$src_lib" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done < "$packaged_native"

# SDL JNI classes must come from the exact same pinned revision as emucore.
SDL_JAVA_SRC="$SRC/platforms/android/app/src/main/java/org/libsdl/app"
SDL_JAVA_DST="app/src/main/java/org/libsdl/app"
test -s "$SDL_JAVA_SRC/SDLActivity.java"
test -s "$SDL_JAVA_SRC/SDL.java"
test -s "$SDL_JAVA_SRC/SDLAudioManager.java"
test -s "$SDL_JAVA_SRC/SDLControllerManager.java"
rm -rf "$SDL_JAVA_DST"
mkdir -p "$SDL_JAVA_DST"
cp -a "$SDL_JAVA_SRC/." "$SDL_JAVA_DST/"
SDL_JAVA_COUNT="$(find "$SDL_JAVA_DST" -maxdepth 1 -type f -name '*.java' | wc -l | tr -d ' ')"
if (( SDL_JAVA_COUNT < 8 )); then
  echo "Pinned SDL Android Java bridge looks incomplete ($SDL_JAVA_COUNT files)" >&2
  exit 1
fi
grep -Fq 'public class SDLActivity extends Activity' "$SDL_JAVA_DST/SDLActivity.java"
grep -Fq 'static public void setupJNI()' "$SDL_JAVA_DST/SDL.java"

# Alpha 6 #19: runtime policy now lives permanently in source. The importer is
# intentionally read-only with respect to Pcsx2PS2Backend; CI only asserts the
# safety/performance contract. This removes the accumulated source-rewrite debt
# from attempts #14-#18.
BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
test -s "$BACKEND"
grep -Fq 'NativeApp.nativeLoadDiagnostic()' "$BACKEND"
grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
grep -Fq 'CoalesceRenderPasses' "$BACKEND"
grep -Fq 'SkipDuplicateFrames' "$BACKEND"
grep -Fq 'GSBackThreadMode' "$BACKEND"
grep -Fq 'startPressureProfiler(request.imagePath)' "$BACKEND"
grep -Fq 'PS2NativeBridge.samplePcsx2Performance()' "$BACKEND"
grep -Fq 'PERF_PROFILE_GS' "$BACKEND"
grep -Fq 'PERF_PROFILE_GPU' "$BACKEND"
grep -Fq 'speedPercent' "$BACKEND"
grep -Fq 'frameSpike' "$BACKEND"
grep -Fq 'EnableThreadPinning' "$BACKEND"
if grep -Fq 'startAdaptiveGovernor' "$BACKEND"; then
  echo 'Old adaptive EE-cycle governor still exists in permanent PS2 source' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecyclerate(' "$BACKEND"; then
  echo 'Live EE cycle-rate mutation reintroduced into PS2 backend' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecycleskip(' "$BACKEND"; then
  echo 'Live EE cycle-skip mutation reintroduced into PS2 backend' >&2
  exit 1
fi

RESOURCE_SRC="$STAGE/assets/resources"
RESOURCE_DST="app/src/main/assets/pcsx2/resources"
test -d "$RESOURCE_SRC"
rm -rf app/src/main/assets/pcsx2
mkdir -p "$RESOURCE_DST"
cp -a "$RESOURCE_SRC/." "$RESOURCE_DST/"
test -n "$(find "$RESOURCE_DST" -type f -print -quit)"

LICENSE_DIR="app/src/main/assets/licenses"
mkdir -p "$LICENSE_DIR"
install -m 0644 "$SRC/COPYING.GPLv3" "$LICENSE_DIR/GPL-3.0-PCSX2-ARMSX2.txt"
test -s "$LICENSE_DIR/GPL-3.0-PCSX2-ARMSX2.txt"

rm -f "$DIAG"
printf 'OMNICORE_PCSX2_IMPORT_OK pin=%s tag=%s upstream_native=%s packaged_native=%s sdl_java=%s resources=%s sha256=%s\n' \
  "$PIN" "$TAG" \
  "$(wc -l < "$upstream_native" | tr -d ' ')" \
  "$(wc -l < "$packaged_native" | tr -d ' ')" \
  "$SDL_JAVA_COUNT" \
  "$(find "$RESOURCE_DST" -type f | wc -l)" \
  "$(cut -d' ' -f1 "$WORK/upstream-apk.sha256")"
echo 'Official ARMSX2 ARM64 native payload:'
cat "$upstream_native"
echo 'Copied ARMSX2 ARM64 native payload:'
cat "$packaged_native"
echo "Copied pinned SDL Android Java bridge: $SDL_JAVA_COUNT files"
echo 'OMNICORE_PCSX2_ALPHA6_19_BASE_OK permanent_policy=1 source_rewrites=0'
