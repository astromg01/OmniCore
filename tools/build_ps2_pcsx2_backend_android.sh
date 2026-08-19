#!/usr/bin/env bash
set -euo pipefail

SRC="${1:-build/third_party/armsx2}"
WORK="${2:-build/ps2-pcsx2}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
ANDROID_PROJECT="$SRC/platforms/android"
APK_OUT="$ANDROID_PROJECT/app/build/outputs/apk/github/release/app-github-release.apk"

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must be set}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

if [[ "$(git -C "$SRC" rev-parse HEAD)" != "$PIN" ]]; then
  echo "Unexpected ARMSX2 source revision" >&2
  exit 1
fi

test -x "$ANDROID_PROJECT/gradlew"
rm -rf "$WORK"
mkdir -p "$WORK"

# ARMSX2 release falls back to Android's debug signing config when no private
# release keystore is supplied. Ensure the standard local debug key exists so
# the upstream packaging task is deterministic on a fresh CI runner.
mkdir -p "$HOME/.android"
if [[ ! -f "$HOME/.android/debug.keystore" ]]; then
  keytool -genkeypair -v \
    -keystore "$HOME/.android/debug.keystore" \
    -storepass android -alias androiddebugkey -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

build_variant() {
  local page_size="$1"
  local lib_name="$2"
  local copy_to="$3"

  "$ANDROID_PROJECT/gradlew" -p "$ANDROID_PROJECT" :app:cleanCxx --no-daemon
  "$ANDROID_PROJECT/gradlew" -p "$ANDROID_PROJECT" :app:assembleGithubRelease --no-daemon \
    -Parmsx2.hostPageSize="$page_size" \
    -Parmsx2.nativeLibName="$lib_name" \
    -Parmsx2.applicationId="com.omnicore.pcsx2.buildcore"

  test -s "$APK_OUT"
  unzip -l "$APK_OUT" | grep -Fq "lib/arm64-v8a/lib${lib_name}.so"
  cp -f "$APK_OUT" "$copy_to"
}

echo "=== PCSX2/ARMSX2 4 KB core ==="
build_variant "0x1000" "emucore_4k" "$WORK/armsx2-4k.apk"

echo "=== PCSX2/ARMSX2 16 KB core ==="
build_variant "0x4000" "emucore_16k" "$WORK/armsx2-16k.apk"

STAGE="$WORK/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/base" "$STAGE/lib/arm64-v8a"
unzip -q "$WORK/armsx2-4k.apk" -d "$STAGE/base"

# Keep the support libraries from upstream's universal baseline and package
# both page-size-specific emucore variants. The shim chooses at runtime.
find "$STAGE/base/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -exec cp -f {} "$STAGE/lib/arm64-v8a/" \;
unzip -p "$WORK/armsx2-16k.apk" "lib/arm64-v8a/libemucore_16k.so" > "$STAGE/lib/arm64-v8a/libemucore_16k.so"
test -s "$STAGE/lib/arm64-v8a/libemucore_4k.so"
test -s "$STAGE/lib/arm64-v8a/libemucore_16k.so"

JNI_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
find "$STAGE/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -exec cp -f {} "$JNI_DIR/" \;

# PCSX2 runtime resources are generated into the upstream APK from its canonical
# bin/resources tree. Stage that exact built payload under a namespaced asset
# root; Pcsx2PS2Backend copies it to <DataRoot>/resources on first run.
RESOURCE_SRC="$STAGE/base/assets/resources"
RESOURCE_DST="app/src/main/assets/pcsx2/resources"
test -d "$RESOURCE_SRC"
rm -rf "app/src/main/assets/pcsx2"
mkdir -p "$RESOURCE_DST"
cp -a "$RESOURCE_SRC/." "$RESOURCE_DST/"

test -n "$(find "$RESOURCE_DST" -type f -print -quit)"

printf 'OMNICORE_PCSX2_BUILD_OK pin=%s libs=%s resources=%s\n' \
  "$PIN" \
  "$(find "$STAGE/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' | wc -l)" \
  "$(find "$RESOURCE_DST" -type f | wc -l)"
