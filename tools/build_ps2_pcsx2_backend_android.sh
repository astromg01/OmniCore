#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #23: retain #20 telemetry, #21 GS pipeline support and #22 parallel
# scoring, then make the measured BALANCED EE/VU-heavy profile visual-safe.
# BALANCED no longer spends a host core on GS Back or forces the pipeline path
# which produced fog flicker in the device test. No affinity, cycle hacks,
# frameskip, or resolution loss are allowed.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
PATCH21="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_21.py"
PATCH22="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_22.py"
PATCH23="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_23.py"
chmod +x "$BASE"
"$BASE" "$@"
python3 "$PATCH21"
python3 "$PATCH22"
python3 "$PATCH23"

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
BRIDGE_KT="app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt"
BRIDGE_CPP="app/src/main/cpp/ps2/ps2_native_bridge.cpp"
FALLBACK_CPP="app/src/main/cpp/ps2/ps2_thread_perf_fallback.cpp"
CONSTANTS="app/src/main/java/com/omnicore/emulator/core/ps2/PS2PerformanceConstants.kt"
TELEMETRY="app/src/main/java/com/omnicore/emulator/core/ps2/PS2Backend.kt"
ACTIVITY="app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt"

for f in "$BACKEND" "$BRIDGE_KT" "$BRIDGE_CPP" "$FALLBACK_CPP" "$CONSTANTS" "$TELEMETRY" "$ACTIVITY"; do
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
grep -Fq 'internalFps' "$BACKEND"
grep -Fq 'frameSpike' "$BACKEND"
grep -Fq 'peak_frame_ms' "$BACKEND"
grep -Fq 'spike_count' "$BACKEND"

# #21 device contract remains available for titles which are genuinely GS-only.
grep -Fq 'vmThreadTid = Process.myTid()' "$BACKEND"
grep -Fq 'forceLearnedGsVulkan' "$BACKEND"
grep -Fq 'Process.THREAD_PRIORITY_DISPLAY' "$BACKEND"
grep -Fq 'val eeTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'val vuTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'val gsTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'val gsBackTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'values["gsbTid"]?.toIntOrNull()' "$BRIDGE_KT"
grep -Fq 'eeTid=' "$FALLBACK_CPP"
grep -Fq 'gsbTid=' "$FALLBACK_CPP"

# #22 parallel scoring remains correct: GS and GSB are parallel branches.
grep -Fq 'PERF_PROFILE_BALANCED = 4' "$CONSTANTS"
grep -Fq 'PERF_PROFILE_BALANCED -> "BALANCED"' "$CONSTANTS"
grep -Fq 'val gsParallelMs = max(' "$BACKEND"
grep -Fq 'val gsParallelPct = max(' "$BACKEND"
grep -Fq 'balancedPressure' "$BACKEND"
grep -Fq 'ps2TidPriorities' "$BACKEND"
grep -Fq 'setPs2Priority(perf.eeTid, Process.THREAD_PRIORITY_DISPLAY)' "$BACKEND"

# #23 measured-device contract. BALANCED means EE/VU are the critical path:
# one GS worker, one queued frame, sticky visual-safe profile and no forced
# Vulkan solely to obtain the two-object split. The HUD must expose PIPE OFF/ON.
grep -Fq 'val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS' "$BACKEND"
grep -Fq 'val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED' "$BACKEND"
grep -Fq 'PERF_PROFILE_BALANCED -> 1' "$BACKEND"
grep -Fq 'learned == PERF_PROFILE_BALANCED' "$BACKEND"
grep -Fq 'visualSafeBalanced=$visualSafeBalanced' "$BACKEND"
grep -Fq 'val pipe = if (t.gsBackUsagePercent >= 0f || t.gsBackMs >= 0f) "ON" else "OFF"' "$ACTIVITY"
grep -Fq 'PIPE $pipe' "$ACTIVITY"

# Native telemetry contract: direct metrics when exported, procfs otherwise.
grep -Fq '_ZN18PerformanceMetrics8GetSpeedEv' "$BRIDGE_CPP"
grep -Fq '_ZN18PerformanceMetrics14GetInternalFPSEv' "$BRIDGE_CPP"
grep -Fq '_ZN18PerformanceMetrics17GetCPUThreadUsageEv' "$BRIDGE_CPP"
grep -Fq '_ZN18PerformanceMetrics16GetVUThreadUsageEv' "$BRIDGE_CPP"
grep -Fq '_ZN18PerformanceMetrics16GetGSThreadUsageEv' "$BRIDGE_CPP"
grep -Fq '_ZN18PerformanceMetrics11GetGPUUsageEv' "$BRIDGE_CPP"
grep -Fq 'vsInvocations' "$BRIDGE_KT"
grep -Fq 'psInvocations' "$BRIDGE_KT"

grep -Fq 'emulationSpeedPercent' "$TELEMETRY"
grep -Fq 'visibilityPressure' "$TELEMETRY"
grep -Fq 'peakFrameMs' "$TELEMETRY"

# In-game HUD remains optional and measurement-only.
grep -Fq 'perfHudVisible' "$ACTIVITY"
grep -Fq 'formatPerformanceHud' "$ACTIVITY"
grep -Fq 'MENU_HUD' "$ACTIVITY"
grep -Fq 'emulationSpeedPercent' "$ACTIVITY"
grep -Fq 'bottleneck' "$ACTIVITY"
grep -Fq 'visibilityPressure' "$ACTIVITY"

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

# Hidden C++ perf symbols are expected for the official ARMSX2 payload and
# must degrade to procfs rather than fail the build or VM.
READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if [[ -x "$READELF" ]]; then
  for CORE in app/src/main/jniLibs/arm64-v8a/libemucore_4k.so app/src/main/jniLibs/arm64-v8a/libemucore_16k.so; do
    test -s "$CORE"
    for SYMBOL in \
      _ZN18PerformanceMetrics17GetCPUThreadUsageEv \
      _ZN18PerformanceMetrics16GetVUThreadUsageEv \
      _ZN18PerformanceMetrics16GetGSThreadUsageEv \
      _ZN18PerformanceMetrics11GetGPUUsageEv; do
      if ! "$READELF" -Ws "$CORE" | grep -Fq "$SYMBOL"; then
        echo "WARNING: runtime metric symbol hidden in $CORE: $SYMBOL; procfs fallback enabled." >&2
      fi
    done
  done
fi

echo 'OMNICORE_PCSX2_ALPHA6_23_POLICY_OK balanced_pipeline_off=1 queue1=1 sticky_visual_safe=1 ee_vu_priority=1 hud_pipe=1 procfs=1 no_affinity=1 safe_cycle_defaults=1'
