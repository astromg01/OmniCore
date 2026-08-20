#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #31: keep the #29 fog-safe single-object GS core, but replace the
# telemetry-driven micro-tuning loop with one static pre-boot architecture:
# device class + GameDB-style game class + SAFE/OPTIMAL/FAST tier. PCSX2's own
# GameDB remains authoritative for compatibility hacks. No cycle skipping,
# sub-native resolution, effect removal or affinity pinning is allowed.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
PATCH21="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_21.py"
PATCH22="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_22.py"
PATCH23="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_23.py"
PATCH24="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_24.py"
PATCH29="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_29.py"
PATCH31="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_31_performance_architecture.py"
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
python3 "$PATCH31"
python3 "$UPSTREAM_PATCH24" "$SRC"
python3 "$UPSTREAM_PATCH29" "$SRC"
"$SOURCE_BUILD" "$SRC" app/src/main/jniLibs/arm64-v8a

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
ARCH="app/src/main/java/com/omnicore/emulator/core/ps2/PS2PerformanceArchitecture.kt"
BRIDGE_KT="app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt"
FALLBACK_CPP="app/src/main/cpp/ps2/ps2_thread_perf_fallback.cpp"
CONSTANTS="app/src/main/java/com/omnicore/emulator/core/ps2/PS2PerformanceConstants.kt"
TELEMETRY="app/src/main/java/com/omnicore/emulator/core/ps2/PS2Backend.kt"
SMART="app/src/main/java/com/omnicore/emulator/performance/PS2SmartPerf.kt"
ACTIVITY="app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt"
NATIVE_APP="app/src/main/java/kr/co/iefriends/pcsx2/NativeApp.java"
VIS_HEADER="$SRC/pcsx2/GS/OmniVisibilityTelemetry.h"
GS_STATE="$SRC/pcsx2/GS/GSState.cpp"
UPSTREAM_NATIVE="$SRC/platforms/android/app/src/main/cpp/native-lib.cpp"

for f in "$BACKEND" "$ARCH" "$BRIDGE_KT" "$FALLBACK_CPP" "$CONSTANTS" "$TELEMETRY" "$SMART" "$ACTIVITY" "$NATIVE_APP" "$VIS_HEADER" "$GS_STATE" "$UPSTREAM_NATIVE"; do
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

# #21/#22 diagnostics remain compiled for opt-in HUD/debug use. They no longer
# choose renderer/readback/scheduler policy during normal gameplay.
grep -Fq 'vmThreadTid = Process.myTid()' "$BACKEND"
grep -Fq 'Process.THREAD_PRIORITY_DISPLAY' "$BACKEND"
grep -Fq 'val gsParallelMs = max(' "$BACKEND"
grep -Fq 'val gsParallelPct = max(' "$BACKEND"
grep -Fq 'PERF_PROFILE_BALANCED = 4' "$CONSTANTS"
grep -Fq 'PERF_PROFILE_BALANCED -> "BALANCED"' "$CONSTANTS"
grep -Fq 'ps2TidPriorities' "$BACKEND"

# #29 physical-device safety remains non-negotiable: Pipelined and Vulkan
# Lockstep reproduced fog flashing, so the GS back thread stays OFF.
grep -Fq 'val useGsPipeline = false' "$BACKEND"
grep -Fq 'val useGsLockstep = false' "$BACKEND"
grep -Fq 'val gsBackMode = 0' "$BACKEND"
grep -Fq 'gsBackMode.toString()' "$BACKEND"

# #31 architecture contract: static tier -> device/game policy -> renderer and
# hardware-download policy, all before boot. FAST uses PCSX2 NoReadbacks; the
# currently validated heavy reference can receive the same policy under OPTIMAL.
grep -Fq 'enum class PerformanceTier' "$TELEMETRY"
grep -Fq 'performanceTier: PerformanceTier = PerformanceTier.OPTIMAL' "$TELEMETRY"
grep -Fq 'object PS2PerformanceArchitecture' "$ARCH"
grep -Fq 'HEAVY_GS_REFERENCE' "$ARCH"
grep -Fq 'NO_READBACKS(2)' "$ARCH"
grep -Fq 'PS2PerformanceArchitecture.resolve(appContext, gameKey, config)' "$BACKEND"
grep -Fq '"HWDownloadMode", "int", architecture.readbacks.nativeValue.toString()' "$BACKEND"
grep -Fq 'architecture.mtvu.toString()' "$BACKEND"
grep -Fq 'performanceTier = when (mode)' "$SMART"
grep -Fq 'currentGame.title.lowercase()' "$ACTIVITY"
grep -Fq 'started && perfHudVisible' "$ACTIVITY"
if grep -Fq 'startPressureProfiler(request.imagePath)' "$BACKEND"; then
  echo 'Live pressure profiler must not run in #31 static architecture' >&2
  exit 1
fi

# Visibility v2 source telemetry remains ABI-compatible and read-only. It is
# available when the HUD is explicitly enabled, not as a permanent governor.
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

echo 'OMNICORE_PCSX2_ALPHA6_31_POLICY_OK architecture_v1=1 device_profile=1 game_profile=1 static_tiers=1 no_readbacks_scoped=1 profiler_off=1 hud_opt_in=1 gs_back_off=1 fog_safety=1 no_fog_disable=1 no_affinity=1 safe_cycle_defaults=1'
