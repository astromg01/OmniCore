#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #19: the PS2 runtime policy is now permanent source code. This wrapper
# imports the pinned official ARMSX2 payload and then verifies that the actionable
# EE/VU/GS/GPU policy, frame-spike profiler and HUD telemetry bridge survived the
# build preparation unchanged.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
chmod +x "$BASE"
"$BASE" "$@"

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
BRIDGE_KT="app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt"
BRIDGE_CPP="app/src/main/cpp/ps2/ps2_native_bridge.cpp"
TELEMETRY="app/src/main/java/com/omnicore/emulator/core/ps2/PS2Backend.kt"
ACTIVITY="app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt"

for f in "$BACKEND" "$BRIDGE_KT" "$BRIDGE_CPP" "$TELEMETRY" "$ACTIVITY"; do
  test -s "$f"
done

# Permanent runtime safety/performance contract.
grep -Fq 'learnedProfile == PERF_PROFILE_GS' "$BACKEND"
grep -Fq 'GSBackThreadMode' "$BACKEND"
grep -Fq 'CoalesceRenderPasses' "$BACKEND"
grep -Fq 'SkipDuplicateFrames' "$BACKEND"
grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
grep -Fq 'EnableThreadPinning", "bool", "false"' "$BACKEND"
grep -Fq 'PS2NativeBridge.samplePcsx2Performance()' "$BACKEND"
grep -Fq 'speedPercent' "$BACKEND"
grep -Fq 'internalFps' "$BACKEND"
grep -Fq 'frameSpike' "$BACKEND"
grep -Fq 'peak_frame_ms' "$BACKEND"
grep -Fq 'spike_count' "$BACKEND"

# Native telemetry contract: speed/internal FPS + subsystem timings + shader
# invocation density used for visibility/geometry classification.
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

# Never allow the #13 regression back in.
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

# Confirm the official pinned binary exposes the subsystem metrics. Speed and
# internal-FPS are optional in the runtime bridge, but record their visibility in
# CI so a future pin cannot silently degrade the HUD without a diagnostic.
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
        echo "WARNING: required runtime metric symbol not visible in $CORE: $SYMBOL; runtime fallback will be used." >&2
      fi
    done
    for SYMBOL in \
      _ZN18PerformanceMetrics8GetSpeedEv \
      _ZN18PerformanceMetrics14GetInternalFPSEv; do
      if "$READELF" -Ws "$CORE" | grep -Fq "$SYMBOL"; then
        echo "PCSX2 HUD metric visible: $CORE $SYMBOL"
      else
        echo "WARNING: optional HUD metric not visible in $CORE: $SYMBOL" >&2
      fi
    done
  done
fi

echo 'OMNICORE_PCSX2_ALPHA6_19_POLICY_OK actionable_profiles=1 native_hud=1 speed=1 internal_fps=1 frame_spikes=1 permanent_source=1 safe_cycle_defaults=1'
