#!/usr/bin/env bash
set -euo pipefail

BRANCH="agent/0.10.0-n64-foundation"
VERSION="0.10.20"
TAG="v0.10.20-n64-alpha21"

record_failure() {
  rc=$?
  if [ "$rc" -eq 0 ]; then return; fi
  set +e
  git reset --hard HEAD
  mkdir -p .ci
  printf 'run_id=%s\ncommit=%s\nstatus=failure\nversion=0.10.20-alpha21\n' "$GITHUB_RUN_ID" "$(git rev-parse HEAD)" > .ci/n64-foundation-failure.txt
  git config user.name "Mauricio.dev"
  git config user.email "antoniomauriciorodriguesalves@gmail.com"
  git add .ci/n64-foundation-failure.txt
  git commit -m "Record N64 Alpha 21 validation failure [skip ci]" || true
  git push origin HEAD:"$BRANCH" || true
  exit "$rc"
}
trap record_failure EXIT

if grep -q 'versionName = "0.10.19"' app/build.gradle.kts; then
  python3 tools/agent_01020_alpha21.py
  ALPHA21_MIGRATED=1
elif grep -q 'versionName = "0.10.20"' app/build.gradle.kts; then
  ALPHA21_MIGRATED=0
else
  echo "Unsupported OmniCore source version for Alpha 21" >&2
  exit 1
fi

grep -q 'versionCode = 36' app/build.gradle.kts
grep -q 'versionName = "0.10.20"' app/build.gradle.kts
grep -q 'ElasticAudioBridge' app/src/main/cpp/n64/n64_native_bridge.cpp
grep -q 'TransitionAudioShield' app/src/main/cpp/n64/n64_native_bridge.cpp
grep -q 'RacingComfort' app/src/main/cpp/n64/n64_native_bridge.cpp
grep -q 'audioRescues' app/src/main/cpp/n64/n64_libretro_host.h
grep -q 'audioRescues_' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'callbackHistory' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'minimumElasticFrames' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'noteUnderrun' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'consecutiveStarvedCallbacks' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'NewFloatArray(23)' app/src/main/cpp/n64/n64_native_bridge.cpp
grep -q 'audioRescues = raw.getOrElse(22)' app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt
grep -q 'rescues ' app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt
! grep -q 'std::fill(output + count, output + samples, 0)' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'armTransitionAudioShield' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'std::try_to_lock' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'suddenMicroSpike' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'cruiseRelaxed' app/src/main/cpp/n64/n64_libretro_host.cpp
! grep -q 'runSmartPrecompile' app/src/main/cpp/n64/n64_libretro_host.cpp
! grep -q 'glFinish();' app/src/main/cpp/n64/n64_libretro_host.cpp
grep -q 'X15("1.5x", "1,5×", 15)' app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt
grep -q 'framebufferEmulation = true' app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt
grep -q 'internalResolution = requested.internalResolution' app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt
grep -q 'threadedRenderer = requested.threadedRenderer' app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt
grep -q 'PIN="f275caf4b2bfa1e6d1c51636746ea793f3d80320"' tools/fetch_n64_core.sh
CHANGED="$(git status --porcelain | sed -E 's/^.. //')"
if grep -Eiq '(^|/)(ps1|pcsx)' <<< "$CHANGED"; then
  echo "Alpha 21 attempted to modify PS1-owned source:" >&2
  grep -Ei '(^|/)(ps1|pcsx)' <<< "$CHANGED" >&2
  exit 1
fi
git diff --check

export JAVA_HOME="$JAVA_HOME_17_X64"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/28.2.13676358"
test -d "$ANDROID_SDK_ROOT/platforms/android-36"
test -x "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner"
test -x "$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign"
test -x "$ANDROID_NDK_HOME/ndk-build"

mkdir -p build/signing
base64 -d config/omnicore-dev.keystore.b64 > build/signing/omnicore-dev.jks
chmod 600 build/signing/omnicore-dev.jks
export OMNICORE_DEV_KEYSTORE="$PWD/build/signing/omnicore-dev.jks"
while IFS='=' read -r key value; do
  export "$key=$value"
done < <(python3 - <<'PY'
from pathlib import Path
import re
text = Path('.github/workflows/android.yml').read_text(encoding='utf-8')
for key in ('OMNICORE_DEV_STORE_PASSWORD', 'OMNICORE_DEV_KEY_ALIAS', 'OMNICORE_DEV_KEY_PASSWORD'):
    match = re.search(r'echo "' + re.escape(key) + r'=([^\"]+)" >> "\$GITHUB_ENV"', text)
    if not match:
        raise SystemExit(f'missing existing DEV signing field: {key}')
    print(f'{key}={match.group(1)}')
PY
)

gradle :app:externalNativeBuildDebug :app:compileDebugKotlin --stacktrace --no-daemon
chmod +x tools/fetch_ps1_core.sh tools/build_ps1_core_android.sh tools/fetch_n64_core.sh tools/build_n64_core_android.sh
./tools/fetch_ps1_core.sh
./tools/build_ps1_core_android.sh
./tools/fetch_n64_core.sh
./tools/build_n64_core_android.sh
for ABI in arm64-v8a armeabi-v7a; do
  test -f "app/src/main/jniLibs/$ABI/libpcsx_rearmed_libretro.so"
  test -f "app/src/main/jniLibs/$ABI/libmupen64plus_next_libretro.so"
done
mkdir -p build/source-artifacts
tar --exclude=.git -czf build/source-artifacts/pcsx-rearmed-da2cb8e-source.tar.gz -C third_party pcsx_rearmed
tar --exclude=.git -czf build/source-artifacts/mupen64plus-next-f275caf-source.tar.gz -C third_party mupen64plus_next

gradle :app:assembleDebug --stacktrace --no-daemon
APK="app/build/outputs/apk/debug/app-debug.apk"
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner"
EXPECTED="9490d1ad4666312d6dd9e5b2a20699583919ae80a5536882bcd3f9accc793547"
"$APKSIGNER" verify --print-certs "$APK"
ACTUAL="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
test "$ACTUAL" = "$EXPECTED"
CHECK_DIR="build/apk-alignment-check"
rm -rf "$CHECK_DIR" && mkdir -p "$CHECK_DIR"
unzip -q "$APK" -d "$CHECK_DIR"
READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
while IFS= read -r -d '' SO; do
  while IFS= read -r LOAD_LINE; do
    ALIGN_HEX="$(awk '{print $NF}' <<< "$LOAD_LINE")"
    [[ "$ALIGN_HEX" =~ ^0x[0-9A-Fa-f]+$ ]]
    (( ALIGN_HEX >= 0x4000 ))
  done < <("$READELF" -lW "$SO" | grep -E '^[[:space:]]*LOAD[[:space:]]')
done < <(find "$CHECK_DIR/lib" -type f -name '*.so' -print0)
"$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign" -v -c -P 16 4 "$APK"

if [ "$ALPHA21_MIGRATED" = "1" ]; then
  rm -f tools/agent_01020_alpha21.py
  git config user.name "Mauricio.dev"
  git config user.email "antoniomauriciorodriguesalves@gmail.com"
  git add -u app tools/agent_01020_alpha21.py
  git diff --cached --check
  git commit -m "Apply OmniCore 0.10.20 N64 Alpha 21 ElasticAudioBridge [skip ci]"
  git push origin HEAD:"$BRANCH"
fi

TITLE="OmniCore v0.10.20 N64 Alpha 21"
NOTES="Twenty-first Nintendo 64 device-test alpha. ElasticAudioBridge targets the remaining audible menu/startup cuts after Alpha 20 reduced but did not eliminate source starvation. The AAudio real-time callback no longer turns each short producer gap into silence. Bounded in-place interpolation absorbs shallow gaps while preserving a small PCM reserve; deeper short gaps use a fixed recent-output continuity tail. Telemetry separates rescued starvation events from hard underruns. TransitionAudioShield, RacingComfort, PrecisionGovernor v2.1, CruiseGuard, MicroBurstShield, NonBlockingTelemetry, CadencePolish, StarUI Smooth, Achievements v2, Smart Analog/Kirby, DirectPresenter/RenderBridge, passive 2 MiB WarmCache, protected 1.5x resolution and framebuffer compatibility remain. PS1 remains isolated and unchanged. ROMs, BIOS, firmware, keys and games are not included."
OUT="build/OmniCore-v0.10.20-n64-alpha21-debug.apk"
HASH="build/OmniCore-v0.10.20-n64-alpha21-debug.sha256"
cp "$APK" "$OUT"
sha256sum "$OUT" > "$HASH"
TARGET="$(git rev-parse HEAD)"
if gh release view "$TAG" >/dev/null 2>&1; then
  gh release edit "$TAG" --title "$TITLE" --notes "$NOTES" --prerelease --target "$TARGET"
else
  gh release create "$TAG" --target "$TARGET" --title "$TITLE" --notes "$NOTES" --prerelease
fi
gh release upload "$TAG" "$OUT" "$HASH" build/source-artifacts/pcsx-rearmed-da2cb8e-source.tar.gz build/source-artifacts/mupen64plus-next-f275caf-source.tar.gz --clobber

mkdir -p .ci
printf 'run_id=%s\ncommit=%s\nstatus=success\nversion=0.10.20\ntag=v0.10.20-n64-alpha21\n' "$GITHUB_RUN_ID" "$(git rev-parse HEAD)" > .ci/n64-foundation-success.txt
rm -f .ci/n64-foundation-failure.txt
git config user.name "Mauricio.dev"
git config user.email "antoniomauriciorodriguesalves@gmail.com"
git add .ci/n64-foundation-success.txt
git add -u .ci/n64-foundation-failure.txt 2>/dev/null || true
git commit -m "Record N64 Alpha 21 validation success [skip ci]" || true
git push origin HEAD:"$BRANCH"

trap - EXIT
echo "Alpha 21 bridge validation finished successfully"
