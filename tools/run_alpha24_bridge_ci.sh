#!/usr/bin/env bash
set -euo pipefail

BRANCH="agent/0.10.0-n64-foundation"
VERSION="0.10.23"
TAG="v0.10.23-n64-alpha24"

record_failure() {
  rc=$?
  if [ "$rc" -eq 0 ]; then return; fi
  set +e
  git reset --hard HEAD
  mkdir -p .ci
  printf 'run_id=%s\ncommit=%s\nstatus=failure\nversion=0.10.23-alpha24\n' "$GITHUB_RUN_ID" "$(git rev-parse HEAD)" > .ci/n64-foundation-failure.txt
  git config user.name "Mauricio.dev"
  git config user.email "antoniomauriciorodriguesalves@gmail.com"
  git add .ci/n64-foundation-failure.txt
  git commit -m "Record N64 Alpha 24 validation failure [skip ci]" || true
  git push origin HEAD:"$BRANCH" || true
  exit "$rc"
}
trap record_failure EXIT

if grep -q 'versionName = "0.10.22"' app/build.gradle.kts; then
  python3 tools/agent_01023_alpha24.py
  ALPHA24_MIGRATED=1
elif grep -q 'versionName = "0.10.23"' app/build.gradle.kts; then
  ALPHA24_MIGRATED=0
else
  echo "Unsupported OmniCore source version for Alpha 24" >&2
  exit 1
fi

HOST="app/src/main/cpp/n64/n64_libretro_host.cpp"
HEADER="app/src/main/cpp/n64/n64_libretro_host.h"
BRIDGE="app/src/main/cpp/n64/n64_native_bridge.cpp"
KBRIDGE="app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
ACTIVITY="app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
CMAKE="app/src/main/cpp/CMakeLists.txt"

# Version + AudioBackend Auto invariants
grep -q 'versionCode = 39' app/build.gradle.kts
grep -q 'versionName = "0.10.23"' app/build.gradle.kts
grep -q '#include <SLES/OpenSLES.h>' "$HOST"
grep -q '#include <SLES/OpenSLES_Android.h>' "$HOST"
grep -q 'find_library(opensles-lib OpenSLES)' "$CMAKE"
grep -q '${opensles-lib}' "$CMAKE"
grep -q 'AudioBackend Auto' "$HOST"
grep -q 'AAUDIO_SHARING_MODE_SHARED, AudioBackend::AAUDIO_SHARED' "$HOST"
grep -q 'AAUDIO_SHARING_MODE_EXCLUSIVE, AudioBackend::AAUDIO_EXCLUSIVE' "$HOST"
grep -q 'openOpenSLES' "$HOST"
grep -q 'AudioHealthWatch switching' "$HOST"
grep -q 'SL_IID_ANDROIDSIMPLEBUFFERQUEUE' "$HOST"
grep -q 'kOpenSlQueueBuffers = 4' "$HOST"
grep -q 'audioBackendMode_' "$HEADER"
grep -q 'audioBackendMode' "$KBRIDGE"
grep -q 'NewFloatArray(24)' "$BRIDGE"
grep -q 'SetFloatArrayRegion(result, 0, 24, values)' "$BRIDGE"
grep -q 'AudioBackend Auto host v20' "$BRIDGE"
grep -q '3 -> "OpenSL"' "$ACTIVITY"
grep -q 'GLES3 + Audio Auto' "$ACTIVITY"

# Startup/smoothing system remains intact.
grep -q 'StartupAudioGate' "$HOST"
grep -q 'SmoothAudioResampler' "$HOST"
grep -q 'SyncSlew' "$HOST"
grep -q 'ElasticAudioBridge' "$HOST" || true
grep -q 'audioPrimeStableFrames' "$HOST"
grep -q 'outputSampleRate \* 90 / 1000' "$HOST"
grep -q 'outputSampleRate \* 120 / 1000' "$HOST"
grep -q 'resampleNextOutputPos' "$HOST"
grep -q 'audioSyncScaleSmoothed' "$HOST"

# RacingComfort v2 must restore full rim range and stabilize horizontal touch
# steering. The old accidental *0.96 cap must be gone.
grep -q 'RacingComfort v2' "$HOST"
grep -q 'kRacingCenterGain = 0.78f' "$HOST"
grep -q 'outputY \*= 0.30f' "$HOST"
! grep -q 'userSensitivity \* 0.96f' "$HOST"

# Core/performance/compatibility invariants.
! grep -q 'runSmartPrecompile' "$HOST"
! grep -q 'glFinish();' "$HOST"
grep -q 'X15("1.5x", "1,5×", 15)' app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt
grep -q 'framebufferEmulation = true' app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt
grep -q 'internalResolution = requested.internalResolution' app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt
grep -q 'threadedRenderer = requested.threadedRenderer' app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt
grep -q 'PIN="f275caf4b2bfa1e6d1c51636746ea793f3d80320"' tools/fetch_n64_core.sh

# OpenSL must only be linked into the N64 target, not the PS1 target.
python3 - <<'PY'
from pathlib import Path
text = Path('app/src/main/cpp/CMakeLists.txt').read_text(encoding='utf-8')
ps1 = text[text.index('target_link_libraries(\n    omnicore_runtime'):text.index('# Nintendo 64 runtime')]
n64 = text[text.index('target_link_libraries(\n    omnicore_n64_runtime'):]
if '${opensles-lib}' in ps1:
    raise SystemExit('OpenSL leaked into PS1 target')
if '${opensles-lib}' not in n64:
    raise SystemExit('OpenSL missing from N64 target')
host = Path('app/src/main/cpp/n64/n64_libretro_host.cpp').read_text(encoding='utf-8')
shared = host.index('AAUDIO_SHARING_MODE_SHARED, AudioBackend::AAUDIO_SHARED')
exclusive = host.index('AAUDIO_SHARING_MODE_EXCLUSIVE, AudioBackend::AAUDIO_EXCLUSIVE')
opensl = host.index('return openOpenSLES(requestedBursts);', shared)
if not (shared < exclusive < opensl):
    raise SystemExit('AudioBackend Auto order is not Shared -> Exclusive -> OpenSL')
if host.count('AAudioStreamBuilder_setErrorCallback') != 1:
    raise SystemExit('AAudio error callback validation failed')
PY

CHANGED="$(git status --porcelain | sed -E 's/^.. //')"
if grep -Eiq '(^|/)(ps1|pcsx)' <<< "$CHANGED"; then
  echo "Alpha 24 attempted to modify PS1-owned source:" >&2
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

# Compile Kotlin + native runtime before expensive core builds.
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

if [ "$ALPHA24_MIGRATED" = "1" ]; then
  rm -f tools/agent_01023_alpha24.py
  git config user.name "Mauricio.dev"
  git config user.email "antoniomauriciorodriguesalves@gmail.com"
  git add -u app tools/agent_01023_alpha24.py
  git add app/src/main/cpp/CMakeLists.txt
  git diff --cached --check
  git commit -m "Apply OmniCore 0.10.23 N64 Alpha 24 AudioBackend Auto [skip ci]"
  git push origin HEAD:"$BRANCH"
fi

TITLE="OmniCore v0.10.23 N64 Alpha 24"
NOTES="Twenty-fourth Nintendo 64 device-test alpha and final N64 audio compatibility expansion before the PS2 development track. AudioBackend Auto no longer assumes that a successfully opened AAudio stream is automatically the best backend for every Android vendor driver. OmniCore now prefers AAudio Shared Low-Latency, tries AAudio Exclusive only as a secondary modern path, and includes a native OpenSL ES four-buffer compatibility fallback for devices or drivers where AAudio is unavailable. AudioHealthWatch observes real hard underruns, AAudio xruns and asynchronous stream errors; if a running AAudio session proves unhealthy it can downgrade once to OpenSL, reprime through StartupAudioGate and continue without backend oscillation. SmoothAudioResampler, SyncSlew, ElasticAudioBridge and TransitionAudioShield remain common to both backends. Telemetry now reports the active backend as AA-SH, AA-EX or OpenSL. RacingComfort v2 also fixes the Mario Kart left-stick profile: the old 0.96 maximum-range cap is removed, center response is softened continuously while full steering remains reachable, and tiny vertical touch noise is suppressed only during dominant horizontal steering. Protected 1.5x resolution, framebuffer compatibility, PrecisionGovernor v2.1, DirectPresenter, Smart Analog/Kirby, WarmCache and PS1 isolation remain unchanged. ROMs, BIOS, firmware, keys and games are not included."
OUT="build/OmniCore-v0.10.23-n64-alpha24-debug.apk"
HASH="build/OmniCore-v0.10.23-n64-alpha24-debug.sha256"
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
printf 'run_id=%s\ncommit=%s\nstatus=success\nversion=0.10.23\ntag=v0.10.23-n64-alpha24\n' "$GITHUB_RUN_ID" "$(git rev-parse HEAD)" > .ci/n64-foundation-success.txt
rm -f .ci/n64-foundation-failure.txt
git config user.name "Mauricio.dev"
git config user.email "antoniomauriciorodriguesalves@gmail.com"
git add .ci/n64-foundation-success.txt
git add -u .ci/n64-foundation-failure.txt 2>/dev/null || true
git commit -m "Record N64 Alpha 24 validation success [skip ci]" || true
git push origin HEAD:"$BRANCH"

trap - EXIT
echo "Alpha 24 AudioBackend Auto validation finished successfully"
