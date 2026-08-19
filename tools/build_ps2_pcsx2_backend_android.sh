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

# Device-test diagnostics: the old UI collapsed every linker exception into a
# misleading page-size message. Patch the build workspace so the Alpha 6 toast
# reports the selected core, real page size, ABI list and exact linker exception.
BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")
old = 'NativeApp.hasNoNativeBinary -> "Pinned PCSX2 emucore is not packaged for this page size."'
new = 'NativeApp.hasNoNativeBinary -> "A6#8 ${NativeApp.nativeLoadDiagnostic()} ABI=${Build.SUPPORTED_ABIS.joinToString("/")}"'
if old not in text and 'A6#8 ${NativeApp.nativeLoadDiagnostic()}' not in text:
    raise SystemExit("PCSX2 backend loader diagnostic anchor not found")
text = text.replace(old, new)
p.write_text(text, encoding="utf-8")
PY
grep -Fq 'A6#8 ${NativeApp.nativeLoadDiagnostic()}' "$BACKEND"

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
printf 'OMNICORE_PCSX2_IMPORT_OK pin=%s tag=%s upstream_native=%s packaged_native=%s resources=%s sha256=%s\n' \
  "$PIN" "$TAG" \
  "$(wc -l < "$upstream_native" | tr -d ' ')" \
  "$(wc -l < "$packaged_native" | tr -d ' ')" \
  "$(find "$RESOURCE_DST" -type f | wc -l)" \
  "$(cut -d' ' -f1 "$WORK/upstream-apk.sha256")"
echo 'Official ARMSX2 ARM64 native payload:'
cat "$upstream_native"
echo 'Copied ARMSX2 ARM64 native payload:'
cat "$packaged_native"
