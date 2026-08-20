#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #18 wrapper: keep the proven official ARMSX2 import, replay the safe
# #17 big.LITTLE policy, then upgrade the learning path to native PCSX2
# EE/VU/GS/GPU telemetry with frame-spike and visibility-pressure analysis.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
chmod +x "$BASE"
"$BASE" "$@"

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
test -s "$BACKEND"

# ---- #17 conservative GS + corrected process CPU fallback -----------------
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

old_pipeline = 'val useGsPipeline = pipelineCapable && learnedProfile != PERF_PROFILE_COMPUTE'
new_pipeline = 'val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_RENDER'
if old_pipeline not in text:
    raise SystemExit('Alpha 6 #17 GS pipeline policy anchor not found')
text = text.replace(old_pipeline, new_pipeline, 1)

old_metric = '''                val processCoreLoad = (cpuMs / wallMs / cores.toFloat()).coerceIn(0f, 1.25f)'''
new_metric = '''                val cpuEquivalentCores = (cpuMs / wallMs).coerceIn(0f, cores.toFloat() + 0.5f)
                val processCoreLoad = (cpuEquivalentCores / cores.toFloat()).coerceIn(0f, 1.25f)'''
if old_metric not in text:
    raise SystemExit('Alpha 6 #17 CPU pressure metric anchor not found')
text = text.replace(old_metric, new_metric, 1)

old_comment = '''                // Decay makes this a recent-workload classifier instead of a
                // permanent verdict. Low FPS with broad process CPU saturation
                // is treated as compute/entity pressure; low FPS without it is
                // treated as GS/render/depth pressure. This never changes VM/JIT
                // settings live -- the result is only a next-boot hint.'''
new_comment = '''                // Culling-aware pressure classifier: PCSX2 cannot safely delete
                // game entities (AI/physics must still run), so classify the cost
                // they create instead. Low FPS while >= ~1.3 CPU cores are busy
                // points at EE/VU/entity/physics pressure; low FPS without that
                // compute pressure points at GS/visibility/depth pressure. The
                // result is next-boot policy only: no live JIT/cycle mutation.'''
if old_comment not in text:
    raise SystemExit('Alpha 6 #17 pressure classifier comment anchor not found')
text = text.replace(old_comment, new_comment, 1)

old_classify = '''                    if (processCoreLoad >= 0.34f) {
                        computePressure += severity * (1.0f + processCoreLoad)
                    } else {
                        renderPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                    }'''
new_classify = '''                    if (cpuEquivalentCores >= 1.30f) {
                        val hotThreadWeight = (cpuEquivalentCores / 2.0f).coerceIn(0.65f, 1.75f)
                        computePressure += severity * (1.0f + hotThreadWeight)
                    } else {
                        renderPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                    }'''
if old_classify not in text:
    raise SystemExit('Alpha 6 #17 pressure branch anchor not found')
text = text.replace(old_classify, new_classify, 1)

old_log = '''                            "cpu=${String.format("%.2f", processCoreLoad)} render=${String.format("%.2f", renderPressure)} " +
                            "compute=${String.format("%.2f", computePressure)} thermal=$thermal adpf=$adpfEnabled"'''
new_log = '''                            "cpuCores=${String.format("%.2f", cpuEquivalentCores)} cpuTotal=${String.format("%.2f", processCoreLoad)} " +
                            "render=${String.format("%.2f", renderPressure)} compute=${String.format("%.2f", computePressure)} " +
                            "thermal=$thermal adpf=$adpfEnabled"'''
if old_log not in text:
    raise SystemExit('Alpha 6 #17 profiler log anchor not found')
text = text.replace(old_log, new_log, 1)

p.write_text(text, encoding="utf-8")
PY

# ---- #18 native bottleneck profiler ---------------------------------------
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import re
import sys

p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

old_pipeline = 'val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_RENDER'
new_pipeline = 'val useGsPipeline = pipelineCapable && (learnedProfile == PERF_PROFILE_GS || learnedProfile == PERF_PROFILE_RENDER)'
if old_pipeline not in text:
    raise SystemExit('Alpha 6 #18 learned GS pipeline anchor not found')
text = text.replace(old_pipeline, new_pipeline, 1)

# Include the visibility classification in preboot diagnostics. It is a safe
# next-boot hint only; no game entity, physics object or draw side effect is
# deleted. GS pipeline remains restricted to a measured GS bottleneck.
old_log = 'Log.i("OmniCorePS2Perf", "preboot profile=$learnedProfile gsPipeline=$useGsPipeline cores=${Runtime.getRuntime().availableProcessors()}")'
new_log = '''val visibilityClass = perfPrefs.getInt("${perfKey}_visibility", 0)
        Log.i("OmniCorePS2Perf", "preboot profile=$learnedProfile visibility=$visibilityClass gsPipeline=$useGsPipeline cores=${Runtime.getRuntime().availableProcessors()}")'''
if old_log not in text:
    raise SystemExit('Alpha 6 #18 preboot diagnostic anchor not found')
text = text.replace(old_log, new_log, 1)

pattern = re.compile(
    r'\n    private fun startPressureProfiler\(imagePath: String\) \{.*?\n    private fun perfProfileKey\(imagePath: String\): String =',
    re.S,
)
replacement = r'''
    private fun startPressureProfiler(imagePath: String) {
        governorStop = false
        governorThread?.interrupt()
        val profileKey = perfProfileKey(imagePath)
        governorThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val prefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)
            val power = appContext.getSystemService(PowerManager::class.java)
            var lastWallNs = System.nanoTime()
            var lastCpuMs = Process.getElapsedCpuTime()
            var eePressure = 0f
            var vuPressure = 0f
            var gsPressure = 0f
            var gpuPressure = 0f
            var samples = 0
            var learned = prefs.getInt(profileKey, PERF_PROFILE_UNKNOWN)
            var adpfEnabled = Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true
            var nativeMetricSeen = false

            while (!governorStop && running) {
                try { Thread.sleep(PRESSURE_SAMPLE_MS) } catch (_: InterruptedException) {
                    if (governorStop || !running) break
                }
                if (governorStop || !running) break
                if (paused) {
                    lastWallNs = System.nanoTime()
                    lastCpuMs = Process.getElapsedCpuTime()
                    continue
                }

                val fps = runCatching { NativeApp.getFPS() }.getOrDefault(-1f)
                val nominal = runCatching { NativeApp.getNominalFrameRate() }.getOrDefault(0f)
                val nowWallNs = System.nanoTime()
                val nowCpuMs = Process.getElapsedCpuTime()
                val wallMs = ((nowWallNs - lastWallNs) / 1_000_000f).coerceAtLeast(1f)
                val cpuMs = (nowCpuMs - lastCpuMs).coerceAtLeast(0L).toFloat()
                lastWallNs = nowWallNs
                lastCpuMs = nowCpuMs
                if (fps <= 1f) continue

                val target = if (nominal > 20f) nominal else 60f
                val frameBudgetMs = (1000f / target).coerceAtLeast(1f)
                val ratio = (fps / target).coerceIn(0f, 1.15f)
                val cpuEquivalentCores = (cpuMs / wallMs).coerceIn(0f, cores.toFloat() + 0.5f)
                val processCoreLoad = (cpuEquivalentCores / cores.toFloat()).coerceIn(0f, 1.25f)
                val nativePerf = PS2NativeBridge.samplePcsx2Performance()
                nativeMetricSeen = nativeMetricSeen || nativePerf.available

                eePressure *= 0.86f
                vuPressure *= 0.86f
                gsPressure *= 0.86f
                gpuPressure *= 0.86f

                val validFrameStats = nativePerf.available && nativePerf.frameAvgMs > 0f && nativePerf.frameMaxMs > 0f
                val frameSpike = validFrameStats && nativePerf.frameMaxMs >
                    max(frameBudgetMs * 1.55f, nativePerf.frameAvgMs * 1.80f)
                if (ratio < 0.94f || frameSpike) {
                    val fpsSeverity = ((0.94f - ratio) / 0.94f).coerceIn(0f, 1f)
                    val spikeBoost = if (frameSpike) 1.25f else 1f
                    val severity = max(0.10f, fpsSeverity) * spikeBoost

                    if (nativePerf.available) {
                        val eeScore = max(
                            nativePerf.eeUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.eeMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val vuScore = max(
                            nativePerf.vuUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.vuMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val gsCombinedMs = nativePerf.gsMs.coerceAtLeast(0f) + nativePerf.gsBackMs.coerceAtLeast(0f)
                        val gsCombinedPct = nativePerf.gsUsage.coerceAtLeast(0f) + nativePerf.gsBackUsage.coerceAtLeast(0f)
                        val gsScore = max(gsCombinedPct / 100f, gsCombinedMs / frameBudgetMs)
                        val gpuScore = max(
                            nativePerf.gpuUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.gpuMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val strongest = max(max(eeScore, vuScore), max(gsScore, gpuScore))

                        when (strongest) {
                            eeScore -> eePressure += severity * (1f + eeScore.coerceIn(0f, 1.5f))
                            vuScore -> vuPressure += severity * (1f + vuScore.coerceIn(0f, 1.5f))
                            gsScore -> gsPressure += severity * (1f + gsScore.coerceIn(0f, 1.5f))
                            else -> gpuPressure += severity * (1f + gpuScore.coerceIn(0f, 1.5f))
                        }
                    } else {
                        // ABI-safe fallback. If a future pinned core hides the
                        // PerformanceMetrics symbols, retain #17's corrected
                        // equivalent-core classifier instead of crashing or
                        // guessing with live JIT changes.
                        if (cpuEquivalentCores >= 1.30f) {
                            val hotThreadWeight = (cpuEquivalentCores / 2.0f).coerceIn(0.65f, 1.75f)
                            eePressure += severity * (1f + hotThreadWeight)
                        } else {
                            gsPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                        }
                    }
                }
                samples++

                val thermal = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                        .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
                } else PowerManager.THERMAL_STATUS_NONE
                val shouldUseAdpf = Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true &&
                    thermal < PowerManager.THERMAL_STATUS_SEVERE
                if (shouldUseAdpf != adpfEnabled) {
                    runCatching { NativeApp.setAdpfEnabled(shouldUseAdpf) }
                    adpfEnabled = shouldUseAdpf
                }

                if (samples % 6 == 0) {
                    val ranked = listOf(
                        PERF_PROFILE_EE to eePressure,
                        PERF_PROFILE_VU to vuPressure,
                        PERF_PROFILE_GS to gsPressure,
                        PERF_PROFILE_GPU to gpuPressure,
                    ).sortedByDescending { it.second }
                    val best = ranked[0]
                    val second = ranked[1]
                    val next = if (best.second >= 0.82f && best.second > second.second * 1.12f) best.first
                        else PERF_PROFILE_UNKNOWN
                    if (next != PERF_PROFILE_UNKNOWN && next != learned) {
                        prefs.edit().putInt(profileKey, next).apply()
                        learned = next
                    }

                    // Visibility pressure is diagnostic/next-boot metadata, not
                    // entity deletion. VS/PS invocation density distinguishes a
                    // geometry-heavy GPU workload from fill/pixel pressure so a
                    // later native culling pass can be enabled only where proven.
                    var visibilityClass = prefs.getInt("${profileKey}_visibility", 0)
                    if (nativePerf.available && nativePerf.vsInvocations > 0.0 && nativePerf.psInvocations > 0.0 &&
                        (learned == PERF_PROFILE_GS || learned == PERF_PROFILE_GPU)) {
                        val pixelsPerVertex = nativePerf.psInvocations / nativePerf.vsInvocations.coerceAtLeast(1.0)
                        val measuredClass = if (pixelsPerVertex < 6.0) 1 else 2 // 1 geometry/mixed, 2 fill/pixel
                        if (measuredClass != visibilityClass) {
                            prefs.edit().putInt("${profileKey}_visibility", measuredClass).apply()
                            visibilityClass = measuredClass
                        }
                    }

                    Log.i(
                        "OmniCorePS2Perf",
                        "native=$nativeMetricSeen profile=$learned vis=$visibilityClass " +
                            "fps=${String.format("%.1f", fps)}/${String.format("%.1f", target)} " +
                            "EE=${String.format("%.0f", nativePerf.eeUsage)}%/${String.format("%.2f", nativePerf.eeMs)}ms " +
                            "VU=${String.format("%.0f", nativePerf.vuUsage)}%/${String.format("%.2f", nativePerf.vuMs)}ms " +
                            "GS=${String.format("%.0f", nativePerf.gsUsage + nativePerf.gsBackUsage)}%/" +
                            "${String.format("%.2f", nativePerf.gsMs + nativePerf.gsBackMs)}ms " +
                            "GPU=${String.format("%.0f", nativePerf.gpuUsage)}%/${String.format("%.2f", nativePerf.gpuMs)}ms " +
                            "frame=${String.format("%.2f", nativePerf.frameAvgMs)}/${String.format("%.2f", nativePerf.frameMaxMs)}ms " +
                            "cpuCores=${String.format("%.2f", cpuEquivalentCores)} thermal=$thermal adpf=$adpfEnabled"
                    )
                }
            }
        }, "OmniCore-PS2-NativeBottleneckProfiler").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun perfProfileKey(imagePath: String): String ='''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit('Alpha 6 #18 pressure profiler block not found')

p.write_text(text, encoding="utf-8")
PY

# Regression/feature guards. The core stays at accurate EE cycle defaults; the
# profiler may classify and persist, but it cannot mutate VM/JIT timing live.
grep -Fq 'PS2NativeBridge.samplePcsx2Performance()' "$BACKEND"
grep -Fq 'PERF_PROFILE_VU' "$BACKEND"
grep -Fq 'PERF_PROFILE_GS' "$BACKEND"
grep -Fq 'PERF_PROFILE_GPU' "$BACKEND"
grep -Fq 'frameSpike' "$BACKEND"
grep -Fq '_visibility' "$BACKEND"
grep -Fq 'learnedProfile == PERF_PROFILE_GS' "$BACKEND"
grep -Fq 'CoalesceRenderPasses' "$BACKEND"
grep -Fq 'SkipDuplicateFrames' "$BACKEND"
grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
if grep -Fq 'NativeApp.speedhackEecyclerate(' "$BACKEND"; then
  echo 'Live EE cycle-rate mutation reintroduced into PS2 backend' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecycleskip(' "$BACKEND"; then
  echo 'Live EE cycle-skip mutation reintroduced into PS2 backend' >&2
  exit 1
fi

# The OmniCore-side native bridge dynamically resolves these symbols from the
# official pinned emucore. Do not hard-fail here: Android package stripping can
# vary, and the runtime deliberately falls back to the #17 process classifier.
READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if [[ -x "$READELF" ]]; then
  for CORE in app/src/main/jniLibs/arm64-v8a/libemucore_4k.so app/src/main/jniLibs/arm64-v8a/libemucore_16k.so; do
    if "$READELF" -Ws "$CORE" | grep -Fq '_ZN18PerformanceMetrics17GetCPUThreadUsageEv'; then
      echo "PCSX2 native PerformanceMetrics symbols visible: $CORE"
    else
      echo "WARNING: PerformanceMetrics symbols are not visible in $CORE; #18 will use ABI-safe fallback on this core." >&2
    fi
  done
fi

echo 'OMNICORE_PCSX2_ALPHA6_18_POLICY_OK native_metrics=1 ee_vu_gs_gpu=1 frame_spikes=1 per_game_v3=1 visibility_pressure=1 safe_cycle_defaults=1'
