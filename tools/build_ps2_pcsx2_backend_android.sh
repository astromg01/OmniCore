#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #21: preserve the stable #20 runtime, then apply the measured God of
# War GS/VU policy. Learned GS-bound AUTO titles are steered to Vulkan because
# the pinned ARMSX2 hardware GS back-thread only engages on Vulkan. We also
# carry the real EE TID into the read-only procfs sampler and apply priority-only
# scheduler assistance without affinity, cycle hacks, frameskip, or resolution loss.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
PATCH21="$(cd "$(dirname "$0")" && pwd)/patch_ps2_alpha6_21.py"
chmod +x "$BASE"
"$BASE" "$@"
python3 "$PATCH21"

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
BRIDGE_KT="app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt"
BRIDGE_CPP="app/src/main/cpp/ps2/ps2_native_bridge.cpp"
FALLBACK_CPP="app/src/main/cpp/ps2/ps2_thread_perf_fallback.cpp"
TELEMETRY="app/src/main/java/com/omnicore/emulator/core/ps2/PS2Backend.kt"
ACTIVITY="app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt"

for f in "$BACKEND" "$BRIDGE_KT" "$BRIDGE_CPP" "$FALLBACK_CPP" "$TELEMETRY" "$ACTIVITY"; do
  test -s "$f"
done

# Permanent runtime safety/performance contract retained from #19/#20.
grep -Fq 'learnedProfile == PERF_PROFILE_GS' "$BACKEND"
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

# #21 measured-device contract: actual EE TID, Vulkan steering for a learned
# GS bottleneck, deeper queue only for GS, and scheduler priority assistance.
grep -Fq 'vmThreadTid = Process.myTid()' "$BACKEND"
grep -Fq 'forceLearnedGsVulkan' "$BACKEND"
grep -Fq 'PERF_PROFILE_GS -> 3' "$BACKEND"
grep -Fq 'applySchedulerAssist' "$BACKEND"
grep -Fq 'Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY)' "$BACKEND"
grep -Fq 'val eeTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'val vuTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'val gsTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'val gsBackTid: Int = -1' "$BRIDGE_KT"
grep -Fq 'values["gsbTid"]?.toIntOrNull()' "$BRIDGE_KT"
grep -Fq 'eeTid=' "$FALLBACK_CPP"
grep -Fq 'gsbTid=' "$FALLBACK_CPP"

# Native telemetry contract: use direct PCSX2 counters when exported; official
# payloads may hide them, in which case #20/#21 use the procfs thread sampler.
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

# Record symbol visibility. Hidden metrics are expected for the official ARMSX2
# payload and must degrade to procfs rather than fail the build or the VM.
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

echo 'OMNICORE_PCSX2_ALPHA6_21_POLICY_OK ee_tid=1 gs_vulkan_steering=1 gs_queue=3 scheduler_priority=1 procfs=1 no_affinity=1 safe_cycle_defaults=1'
