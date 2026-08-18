#!/usr/bin/env bash
set -euo pipefail

BRANCH="agent/0.10.0-n64-foundation"
VERSION="0.10.21"
TAG="v0.10.21-n64-alpha22"

record_failure() {
  rc=$?
  if [ "$rc" -eq 0 ]; then return; fi
  set +e
  git reset --hard HEAD
  mkdir -p .ci
  printf 'run_id=%s\ncommit=%s\nstatus=failure\nversion=0.10.21-alpha22\n' "$GITHUB_RUN_ID" "$(git rev-parse HEAD)" > .ci/n64-foundation-failure.txt
  git config user.name "Mauricio.dev"
  git config user.email "antoniomauriciorodriguesalves@gmail.com"
  git add .ci/n64-foundation-failure.txt
  git commit -m "Record N64 Alpha 22 validation failure [skip ci]" || true
  git push origin HEAD:"$BRANCH" || true
  exit "$rc"
}
trap record_failure EXIT

if grep -q 'versionName = "0.10.20"' app/build.gradle.kts; then
  python3 tools/agent_01021_alpha22.py
  ALPHA22_MIGRATED=1
elif grep -q 'versionName = "0.10.21"' app/build.gradle.kts; then
  ALPHA22_MIGRATED=0
else
  echo "Unsupported OmniCore source version for Alpha 22" >&2
  exit 1
fi

HOST="app/src/main/cpp/n64/n64_libretro_host.cpp"
BRIDGE="app/src/main/cpp/n64/n64_native_bridge.cpp"

grep -q 'versionCode = 37' app/build.gradle.kts
grep -q 'versionName = "0.10.21"' app/build.gradle.kts
grep -q 'StartupAudioGate' "$HOST"
grep -q 'audioPrimeStableFrames' "$HOST"
grep -q 'outputSampleRate \* 90 / 1000' "$HOST"
grep -q 'outputSampleRate \* 120 / 1000' "$HOST"
grep -q 'deviceSafetyFrames' "$HOST"
grep -q 'StartupAudioGate opened with' "$HOST"
grep -q 'impl_->startAudioIfReady();' "$HOST"
test "$(grep -c 'startAudioIfReady();' "$HOST")" -eq 1
python3 - <<'PY'
from pathlib import Path
text = Path('app/src/main/cpp/n64/n64_libretro_host.cpp').read_text(encoding='utf-8')
run = text.index('        impl_->core.run();')
start = text.index('        impl_->startAudioIfReady();')
after = text.index('        const auto afterRun', run)
if not (run < start < after):
    raise SystemExit('StartupAudioGate is not evaluated at the libretro frame boundary')
push = text.index('    void pushAudio(')
adapt = text.index('    void adaptAudio(', push)
if 'startAudioIfReady();' in text[push:adapt]:
    raise SystemExit('AAudio start still occurs inside pushAudio')
PY

grep -q 'OmniCore N64 Runtime 0.10.21' "$BRIDGE"
grep -q 'AAudio host v18' "$BRIDGE"
grep -q 'ElasticAudioBridge' "$HOST"
grep -q 'TransitionAudioShield' "$HOST"
grep -q 'RacingComfort' "$HOST"
grep -q 'audioRescues_' "$HOST"
grep -q 'std::try_to_lock' "$HOST"
! grep -q 'runSmartPrecompile' "$HOST"
! grep -q 'glFinish();' "$HOST"
grep -q 'X15("1.5x", "1,5×", 15)' app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt
grep -q 'framebufferEmulation = true' app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt
grep -q 'internalResolution = requested.internalResolution' app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt
grep -q 'threadedRenderer = requested.threadedRenderer' app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt
grep -q 'PIN="f275caf4b2bfa1e6d1c51636746ea793f3d80320"' tools/fetch_n64_core.sh

CHANGED="$(git status --porcelain | sed -E 's/^.. //')"
if grep -Eiq '(^|/)(ps1|pcsx)' <<< "$CHANGED"; then
  echo "Alpha 22 attempted to modify PS1-owned source:" >&2
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

if [ "$ALPHA22_MIGRATED" = "1" ]; then
  rm -f tools/agent_01021_alpha22.py
  git config user.name "Mauricio.dev"
  git config user.email "antoniomauriciorodriguesalves@gmail.com"
  git add -u app tools/agent_01021_alpha22.py
  git diff --cached --check
  git commit -m "Apply OmniCore 0.10.21 N64 Alpha 22 StartupAudioGate [skip ci]"
  git push origin HEAD:"$BRANCH"
fi

TITLE="OmniCore v0.10.21 N64 Alpha 22"
NOTES="Twenty-second Nintendo 64 device-test alpha. StartupAudioGate targets evidence that most remaining audio damage is accumulated at game startup and early menus rather than during steady gameplay. Alpha 21 could start AAudio around a 50 ms PCM reserve while TransitionAudioShield immediately targeted roughly 76 ms. Alpha 22 primes a bounded 90-120 ms startup reserve based on the real device buffer and only starts the AAudio real-time consumer at a complete libretro frame boundary after two safe frame-boundary checks. AAudio no longer starts from inside pushAudio mid-batch. Pause/load-state reprimes use the same gate. Once started, normal ElasticAudioBridge, TransitionAudioShield and adaptive low-latency behavior continue unchanged. Protected 1.5x resolution, framebuffer compatibility, RacingComfort, Smart Analog/Kirby, DirectPresenter, PrecisionGovernor v2.1, WarmCache and PS1 isolation remain. ROMs, BIOS, firmware, keys and games are not included."
OUT="build/OmniCore-v0.10.21-n64-alpha22-debug.apk"
HASH="build/OmniCore-v0.10.21-n64-alpha22-debug.sha256"
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
printf 'run_id=%s\ncommit=%s\nstatus=success\nversion=0.10.21\ntag=v0.10.21-n64-alpha22\n' "$GITHUB_RUN_ID" "$(git rev-parse HEAD)" > .ci/n64-foundation-success.txt
rm -f .ci/n64-foundation-failure.txt
git config user.name "Mauricio.dev"
git config user.email "antoniomauriciorodriguesalves@gmail.com"
git add .ci/n64-foundation-success.txt
git add -u .ci/n64-foundation-failure.txt 2>/dev/null || true
git commit -m "Record N64 Alpha 22 validation success [skip ci]" || true
git push origin HEAD:"$BRANCH"

trap - EXIT
echo "Alpha 22 bridge validation finished successfully"
