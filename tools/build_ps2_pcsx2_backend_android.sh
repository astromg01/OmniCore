#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #29: preserve the #23 visual-safe single-object GS baseline after the
# Vulkan Lockstep device test reproduced fog flashing. Keep source-built PCSX2
# Visibility telemetry, but correct v1's mixed two-stage cull denominator so
# CULL% and prim/s describe real primitive attempts. No fog/alpha removal,
# resolution reduction, cycle skipping or affinity pinning is allowed.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
PATCH21="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_21.py"
PATCH22="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_22.py"
PATCH23="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_23.py"
PATCH24="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_24.py"
PATCH29="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_29.py"
UPSTREAM_PATCH24="$(cd "$(dirname "$0")" && pwd)/patch_armsx2_alpha6_24_visibility.py"
UPSTREAM_PATCH29="$(cd "$(dirname "$0")" && pwd)/patch_armsx2_alpha6_29_visibility_v2.py"
SOURCE_BUILD="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_source_overlay_android.sh"
SRC="${1:-build/third_party/armsx2}"

chmod +x "$BASE" "$SOURCE_BUILD"
"$BASE" "$@"
python3 "$PATCH21"
python3 "$PATCH22"
python3 "$PATCH23"
python3 "$PATCH24"
python3 "$PATCH29"
python3 "$UPSTREAM_PATCH24" "$SRC"
python3 "$UPSTREAM_PATCH29" "$SRC"
"$SOURCE_BUILD" "$SRC" app/src/main/jniLibs/arm64-v8a

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
BRIDGE_KT="app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt"
FALLBACK_CPP="app/src/main/cpp/ps2/ps2_thread_perf_fallback.cpp"
CONSTANTS="app/src/main/java/com/omnicore/emulator/core/ps2/PS2PerformanceConstants.kt"
TELEMETRY="app/src/main/java/com/omnicore/emulator/core/ps2/PS2Backend.kt"
ACTIVITY="app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt"
NATIVE_APP="app/src/main/java/kr/co/iefriends/pcsx2/NativeApp.java"
VIS_HEADER="$SRC/pcsx2/GS/OmniVisibilityTelemetry.h"
GS_STATE="$SRC/pcsx2/GS/GSState.cpp"
UPSTREAM_NATIVE="$SRC/platforms/android/app/src/main/cpp/native-lib.cpp"

for f in "$BACKEND" "$BRIDGE_KT" "$FALLBACK_CPP" "$CONSTANTS" "$TELEMETRY" "$ACTIVITY" "$NATIVE_APP" "$VIS_HEADER" "$GS_STATE" "$UPSTREAM_NATIVE"; do
  test -s "$f"
done

# Permanent runtime safety/performance contract retained from #19/#20.
grep -Fq 'GSBackThreadMode' "$BACKEND"
grep -Fq 'CoalesceRenderPasses' "$BACKEND"
grep -Fq 'SkipDuplicateFrames' "$BACKEND"
grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
grep -Fq 'EnableThreadPinning' "$BACKEND"
grep -Fq 'PS2NativeBridge.samplePcsx2Performance(vmThreadTid)' "$BACKEND"
grep -Fq 'speedPercent' "$BACKEND"
grep -Fq 'frameSpike' "$BACKEND"
grep -Fq 'peak_frame_ms' "$BACKEND"

# #21/#22 device telemetry and parallel scoring remain intact.
grep -Fq 'vmThreadTid = Process.myTid()' "$BACKEND"
grep -Fq 'Process.THREAD_PRIORITY_DISPLAY' "$BACKEND"
grep -Fq 'val gsParallelMs = max(' "$BACKEND"
grep -Fq 'val gsParallelPct = max(' "$BACKEND"
grep -Fq 'PERF_PROFILE_BALANCED = 4' "$CONSTANTS"
grep -Fq 'PERF_PROFILE_BALANCED -> "BALANCED"' "$CONSTANTS"
grep -Fq 'ps2TidPriorities' "$BACKEND"

# #29 physical-device safety result: both Pipelined and Vulkan Lockstep caused
# intermittent fog flashing. Alpha 6 therefore keeps the GS back thread OFF.
grep -Fq 'val useGsPipeline = false' "$BACKEND"
grep -Fq 'val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED' "$BACKEND"
grep -Fq 'val useGsLockstep = false' "$BACKEND"
grep -Fq 'val gsBackMode = 0' "$BACKEND"
grep -Fq 'gsBackMode.toString()' "$BACKEND"
grep -Fq 'PERF_PROFILE_BALANCED -> 1' "$BACKEND"
grep -Fq 'learned == PERF_PROFILE_BALANCED' "$BACKEND"

# Visibility v2: source-built GS telemetry remains read-only, but primitive
# accounting now has a true one-entry-per-primitive denominator. Fast and legacy
# cull rejection points are tracked separately while preserving PCSX2 decisions.
grep -Fq 'OmniVisibilityTelemetry::RecordFastCull' "$GS_STATE"
grep -Fq 'OmniVisibilityTelemetry::RecordLegacyCull' "$GS_STATE"
grep -Fq 'OmniVisibilityTelemetry::RecordDrawBatch' "$GS_STATE"
grep -Fq 'thread_local std::uint64_t tl_primitive_tests' "$VIS_HEADER"
grep -Fq 'g_fast_culled' "$VIS_HEADER"
grep -Fq 'g_legacy_culled' "$VIS_HEADER"
grep -Fq 'memory_order_relaxed' "$VIS_HEADER"
grep -Fq 'source=omnicore-gs-visibility-v2' "$UPSTREAM_NATIVE"
grep -Fq 'primitiveTests=%llu' "$UPSTREAM_NATIVE"
grep -Fq 'Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot' "$UPSTREAM_NATIVE"
grep -Fq 'getOmniVisibilitySnapshot' "$NATIVE_APP"
grep -Fq 'Pcsx2VisibilitySample' "$BRIDGE_KT"
grep -Fq 'cullTests = values.long("primitiveTests")' "$BRIDGE_KT"
grep -Fq 'samplePcsx2Visibility' "$BACKEND"
grep -Fq 'PERF_VIS_EFFECTS = 3' "$CONSTANTS"
grep -Fq 'PERF_VIS_EFFECTS -> "EFFECTS"' "$CONSTANTS"
grep -Fq 'visibilityCullPercent' "$TELEMETRY"
grep -Fq 'visibilityTestsPerSecond' "$TELEMETRY"
grep -Fq 'fogWorkPercent' "$TELEMETRY"
grep -Fq 'alphaWorkPercent' "$TELEMETRY"
grep -Fq 'CULL ${fmt(t.visibilityCullPercent)}%' "$ACTIVITY"
grep -Fq 'FOG ${fmt(t.fogWorkPercent)}%' "$ACTIVITY"

# Never allow the #13 regression or fake-FPS shortcuts back in.
if grep -Fq 'startAdaptiveGovernor' "$BACKEND"; then
  echo 'Old adaptive EE-cycle governor reintroduced' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecyclerate(' "$BACKEND"; then
  echo 'Live EE cycle-rate mutation reintroduced' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecycleskip(' "$BACKEND"; then
  echo 'Live EE cycle-skip mutation reintroduced' >&2
  exit 1
fi
if grep -Eq 'setAffinity|sched_setaffinity' "$FALLBACK_CPP"; then
  echo 'Procfs telemetry must never affinity-pin PS2 workers' >&2
  exit 1
fi
if grep -Eq 'renderUpscalemultiplier\((0\.|[0-9]*\.[0-9]*[1-9][0-9]*f?\))' "$BACKEND"; then
  echo 'Dynamic sub-native resolution is not allowed by Alpha 6 policy' >&2
  exit 1
fi

# The custom JNI symbol proves these are source-overlaid cores, not untouched
# prebuilts accidentally left behind by the importer.
READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$READELF"
for CORE in app/src/main/jniLibs/arm64-v8a/libemucore_4k.so app/src/main/jniLibs/arm64-v8a/libemucore_16k.so; do
  test -s "$CORE"
  TABLE="$($READELF -Ws "$CORE")"
  grep -Fq 'Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot' <<< "$TABLE"
  grep -Fq 'Java_kr_co_iefriends_pcsx2_NativeApp_getFPS' <<< "$TABLE"
done

echo 'OMNICORE_PCSX2_ALPHA6_29_POLICY_OK source_overlay=1 visibility_v2=1 true_primitive_accounting=1 gs_back_off=1 fog_safety=1 no_fog_disable=1 no_affinity=1 safe_cycle_defaults=1'
