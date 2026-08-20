#!/usr/bin/env bash
set -Eeuo pipefail

SRC="${1:-build/third_party/armsx2}"
WORK="${2:-build/ps2-pcsx2}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
TAG="nightly-20260819"
ASSET="ARMSX2-nightly-20260819-7f0ae7a6c6-Android-arm64.apk"
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

# The exact pinned revision has an official ARMSX2 nightly Android asset built by
# upstream's own dual-core (4K + 16K) pipeline. Upstream forms that universal APK
# from a complete 4K APK and replaces only the emucore entries, so OmniCore must
# preserve the complete ARM64 native runtime payload too. DT_NEEDED alone cannot
# discover helpers loaded later with dlopen (ANGLE/runtime hooks).
echo "Downloading official ARMSX2 nightly: $URL"
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 30 \
  --output "$APK" "$URL"
test -s "$APK"
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

# Android/NDK platform libraries are supplied by the OS and must never be
# vendored. libc++_shared.so is intentionally NOT here: it belongs to the app
# payload and must match the official ARMSX2 build.
is_system_soname() {
  case "$1" in
    libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libz.so|libstdc++.so|libEGL.so|libGLESv1_CM.so|libGLESv2.so|libGLESv3.so|libvulkan.so|libOpenSLES.so|libaaudio.so|libamidi.so|libjnigraphics.so|libnativewindow.so|libmediandk.so|libcamera2ndk.so|libbinder_ndk.so|libneuralnetworks.so|libsync.so)
      return 0 ;;
    *)
      return 1 ;;
  esac
}

# OmniCore already receives this AndroidX native library from its Gradle
# dependency graph. Copying the upstream copy into src/main/jniLibs would create
# a duplicate packaging input; the final APK still contains the dependency copy.
is_gradle_packaged_soname() {
  case "$1" in
    libandroidx.graphics.path.so) return 0 ;;
    *) return 1 ;;
  esac
}

# These are OmniCore-owned runtimes built earlier in the workflow. A future
# upstream asset must never silently overwrite one of them.
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

# Mirror the complete native payload from the official nightly. This is the key
# difference from attempts 6/7, which copied only the recursive DT_NEEDED graph
# and therefore omitted dynamically loaded helpers present in upstream's base APK.
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

# Keep recursive linker validation as a guard, but not as the payload-selection
# mechanism. Dynamic dlopen helpers are now present even when they do not appear
# in an emucore DT_NEEDED table.
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

# SDL's native Android glue resolves org.libsdl.app.* classes from JNI while
# libemucore is being loaded. Attempt #8 proved that importing only the native
# payload is insufficient: the device hit ClassNotFoundException for SDLActivity
# before PCSX2 could initialize. Import the complete, pinned SDL Java bridge from
# the exact same ARMSX2 revision so Java/native ABI stays matched.
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

# Keep a useful real-device linker diagnostic, but do not rewrite newer
# diagnostics added by subsequent Alpha 6 runtime work. This step must be
# idempotent so later attempts can change the message without breaking imports.
BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")
old = 'NativeApp.hasNoNativeBinary -> "Pinned PCSX2 emucore is not packaged for this page size."'
new = 'NativeApp.hasNoNativeBinary -> "A6#9 ${NativeApp.nativeLoadDiagnostic()} ABI=${Build.SUPPORTED_ABIS.joinToString("/")}"'
if old in text:
    text = text.replace(old, new)
elif 'NativeApp.nativeLoadDiagnostic()' not in text:
    raise SystemExit("PCSX2 backend loader diagnostic anchor not found")
p.write_text(text, encoding="utf-8")
PY
grep -Fq 'NativeApp.nativeLoadDiagnostic()' "$BACKEND"

# Alpha 6 device-test regression guard. Attempt #13 changed EE cycle rate live
# whenever FPS dipped. ARMSX2 applies that through VMManager::ApplySettings(),
# which reconfigures CPU/JIT state; workload bursts therefore caused the governor
# itself to add hitches. Keep EE rate/skip at PCSX2 defaults for the whole session.
# The same device test also exposed Partial texture preloading, while the pinned
# core's own default is Full. Force Full so texture residency work happens up
# front instead of during entity/effect-heavy gameplay. VU program caching is
# enabled for repeat-run microprogram compilation stutter reduction on ARM64.
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

old_preload = 'runCatching { NativeApp.renderPreloading(1) }'
if old_preload in text:
    text = text.replace(old_preload, 'runCatching { NativeApp.renderPreloading(2) }', 1)
elif 'runCatching { NativeApp.renderPreloading(2) }' not in text:
    raise SystemExit('PS2 Full texture-preloading anchor not found')

fastmem = '        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableFastmem", "bool", "true")'
vu_cache = '        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableVUProgramCache", "bool", "true")'
if vu_cache not in text:
    if fastmem not in text:
        raise SystemExit('PS2 recompiler settings anchor not found')
    text = text.replace(fastmem, fastmem + '\n' + vu_cache, 1)

# The governor can remain compiled for telemetry/reference, but it must not be
# started until we have a non-JIT-invalidating adaptation strategy.
text = text.replace(
    '            startAdaptiveGovernor()\n            PS2Backend.BootResult.Started(id, activeRenderer)',
    '            PS2Backend.BootResult.Started(id, activeRenderer)',
    1,
)
if '            startAdaptiveGovernor()\n            PS2Backend.BootResult.Started(id, activeRenderer)' in text:
    raise SystemExit('PS2 adaptive governor is still armed at boot')

# Avoid a redundant post-boot cycle-skip write. ARMSX2 routes it through a full
# ApplySettings() even when writing zero, so doing it after the VM starts creates
# a needless JIT/settings transition. Pre-boot settings already pin it to zero.
text = text.replace(
    '        runCatching { NativeApp.speedhackEecycleskip(0) }\n        runCatching { NativeApp.setInstantVU1(true) }',
    '        runCatching { NativeApp.setInstantVU1(true) }',
    1,
)

p.write_text(text, encoding="utf-8")
PY

grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
if grep -Fq '            startAdaptiveGovernor()' "$BACKEND"; then
  echo 'Adaptive EE-cycle governor is still armed' >&2
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
