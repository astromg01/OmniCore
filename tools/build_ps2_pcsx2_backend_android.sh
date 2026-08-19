#!/usr/bin/env bash
set -Eeuo pipefail

SRC="${1:-build/third_party/armsx2}"
WORK="${2:-build/ps2-pcsx2}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
ANDROID_PROJECT="$SRC/platforms/android"
APP_GRADLE="$ANDROID_PROJECT/app/build.gradle.kts"
APK_OUT="$ANDROID_PROJECT/app/build/outputs/apk/github/release/app-github-release.apk"
DIAG="/tmp/ps2-alpha6-build-diagnostic.txt"
LOG="${RUNNER_TEMP:-/tmp}/alpha6-pcsx2-native.log"

on_error() {
  local rc=$?
  {
    echo 'failure_stage=pcsx2-armsx2-native-build'
    echo "exit_code=$rc"
    echo "source_pin=$PIN"
    echo 'upstream_compile_sdk=37'
    echo 'upstream_cmake=3.31.6'
    if [[ -s "$LOG" ]]; then
      echo '--- last 260 build lines ---'
      tail -n 260 "$LOG"
    fi
  } > "$DIAG"
  exit "$rc"
}
trap on_error ERR
exec > >(tee -a "$LOG") 2>&1

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must be set}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

if [[ "$(git -C "$SRC" rev-parse HEAD)" != "$PIN" ]]; then
  echo "Unexpected ARMSX2 source revision" >&2
  exit 1
fi

test -x "$ANDROID_PROJECT/gradlew"
test -s "$SRC/COPYING.GPLv3"
test -s "$APP_GRADLE"

# Keep the pinned ARMSX2 Android frontend on its native toolchain contract.
# This snapshot uses compile/target SDK 37, NDK 28.2 and CMake 3.31.6.
# The previous Android-36 source rewrite could make modern AndroidX metadata
# reject the build before CMake even started, so do not down-port compileSdk.
grep -Fq 'compileSdk = 37' "$APP_GRADLE"
grep -Fq 'targetSdk = 37' "$APP_GRADLE"

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
test -x "$SDKMANAGER"
if [[ ! -d "$ANDROID_SDK_ROOT/platforms/android-37" || ! -d "$ANDROID_SDK_ROOT/cmake/3.31.6" ]]; then
  yes | "$SDKMANAGER" 'platforms;android-37' 'cmake;3.31.6' >/dev/null || true
fi
test -d "$ANDROID_SDK_ROOT/platforms/android-37"
test -d "$ANDROID_SDK_ROOT/cmake/3.31.6"

# Match the pinned upstream Android CI prerequisites. shaderc's SPIRV stack is
# fetched by its sync script, and librashader needs the Android Rust std target.
if command -v rustup >/dev/null 2>&1; then
  rustup target add aarch64-linux-android
fi
(
  cd "$ANDROID_PROJECT"
  python3 app/src/main/cpp/3rdparty/shaderc/utils/git-sync-deps
)

rm -rf "$WORK"
mkdir -p "$WORK"

# ARMSX2 release falls back to Android's debug signing config when no private
# release keystore is supplied. Ensure the standard local debug key exists so
# the upstream intermediate APK can be assembled on a fresh CI runner.
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

  # Force a clean native configuration between the 4K and 16K variants without
  # relying on a non-upstream Gradle task such as :app:cleanCxx.
  rm -rf "$ANDROID_PROJECT/app/.cxx" "$ANDROID_PROJECT/app/build"

  "$ANDROID_PROJECT/gradlew" -p "$ANDROID_PROJECT" :app:assembleGithubRelease \
    --stacktrace --no-daemon \
    -Parmsx2.hostPageSize="$page_size" \
    -Parmsx2.nativeLibName="$lib_name"

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

# Mirror upstream's universal-page layout: keep support libraries from the 4K
# build, then add the page-size-specific 16K emucore. NativeApp selects at load.
find "$STAGE/base/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -exec cp -f {} "$STAGE/lib/arm64-v8a/" \;
unzip -p "$WORK/armsx2-16k.apk" "lib/arm64-v8a/libemucore_16k.so" > "$STAGE/lib/arm64-v8a/libemucore_16k.so"
test -s "$STAGE/lib/arm64-v8a/libemucore_4k.so"
test -s "$STAGE/lib/arm64-v8a/libemucore_16k.so"

JNI_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
rm -f app/src/main/jniLibs/arm64-v8a/libPlay.so app/src/main/jniLibs/armeabi-v7a/libPlay.so
find "$STAGE/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -exec cp -f {} "$JNI_DIR/" \;

RESOURCE_SRC="$STAGE/base/assets/resources"
RESOURCE_DST="app/src/main/assets/pcsx2/resources"
test -d "$RESOURCE_SRC"
rm -rf "app/src/main/assets/pcsx2"
mkdir -p "$RESOURCE_DST"
cp -a "$RESOURCE_SRC/." "$RESOURCE_DST/"
test -n "$(find "$RESOURCE_DST" -type f -print -quit)"

LICENSE_DIR="app/src/main/assets/licenses"
mkdir -p "$LICENSE_DIR"
install -m 0644 "$SRC/COPYING.GPLv3" "$LICENSE_DIR/GPL-3.0-PCSX2-ARMSX2.txt"
test -s "$LICENSE_DIR/GPL-3.0-PCSX2-ARMSX2.txt"

rm -f "$DIAG"
printf 'OMNICORE_PCSX2_BUILD_OK pin=%s sdk=37 cmake=3.31.6 libs=%s resources=%s\n' \
  "$PIN" \
  "$(find "$STAGE/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' | wc -l)" \
  "$(find "$RESOURCE_DST" -type f | wc -l)"
