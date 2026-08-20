#!/usr/bin/env python3
from pathlib import Path

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


backend = BACKEND.read_text()

# #21 used a set because every discovered PS2 worker received DISPLAY priority.
# #22 needs reversible per-thread priorities: EE/VU stay favored while GS/GSB
# can return to DEFAULT when the pipelined GS branch is already below the CPU
# critical path.
backend = replace_once(
    backend,
    "    private val boostedPs2Tids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()\n",
    "    private val ps2TidPriorities = java.util.concurrent.ConcurrentHashMap<Int, Int>()\n",
    "#22 per-thread priority map",
)
backend = replace_once(
    backend,
    "        boostedPs2Tids.clear()\n",
    "        ps2TidPriorities.clear()\n",
    "#22 priority cleanup",
)

# Keep the Vulkan two-object GS split for the new balanced profile. The device
# test proved that the split roughly halves the individual GS branch time; the
# new policy therefore rebalances CPU priority instead of throwing that gain
# away by disabling the back thread.
backend = replace_once(
    backend,
    "            earlyLearnedProfile == PERF_PROFILE_GS && hasVulkan\n",
    "            (earlyLearnedProfile == PERF_PROFILE_GS || earlyLearnedProfile == PERF_PROFILE_BALANCED) && hasVulkan\n",
    "#22 balanced Vulkan steering",
)
backend = replace_once(
    backend,
    "        val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS\n",
    "        val useGsPipeline = pipelineCapable &&\n"
    "            (learnedProfile == PERF_PROFILE_GS || learnedProfile == PERF_PROFILE_BALANCED)\n",
    "#22 balanced pipeline",
)
backend = replace_once(
    backend,
    "            PERF_PROFILE_GS -> 3\n"
    "            PERF_PROFILE_GPU, PERF_PROFILE_RENDER -> max(2, config.queueAheadFrames)\n",
    "            PERF_PROFILE_GS -> 3\n"
    "            PERF_PROFILE_BALANCED -> 2\n"
    "            PERF_PROFILE_GPU, PERF_PROFILE_RENDER -> max(2, config.queueAheadFrames)\n",
    "#22 balanced queue depth",
)

# Add a fifth pressure accumulator. BALANCED means the GS split is active and
# useful, but the parallel EE+VU path is now longer than either GS branch.
backend = replace_once(
    backend,
    "            var gsPressure = 0f\n"
    "            var gpuPressure = 0f\n",
    "            var gsPressure = 0f\n"
    "            var gpuPressure = 0f\n"
    "            var balancedPressure = 0f\n",
    "#22 balanced accumulator",
)
backend = replace_once(
    backend,
    "                gsPressure *= 0.86f\n"
    "                gpuPressure *= 0.86f\n",
    "                gsPressure *= 0.86f\n"
    "                gpuPressure *= 0.86f\n"
    "                balancedPressure *= 0.86f\n",
    "#22 balanced decay",
)

# Critical correctness fix: under true GS pipelining, GS and GS Back run in
# parallel. #21 inherited the old classifier which ADDED both times/usages,
# making a healthy split look more expensive than it is and locking titles into
# the GS profile. Score the longest GS branch instead.
old_rank = '''                        val gsCombinedMs = nativePerf.gsMs.coerceAtLeast(0f) +
                            nativePerf.gsBackMs.coerceAtLeast(0f)
                        val gsCombinedPct = nativePerf.gsUsage.coerceAtLeast(0f) +
                            nativePerf.gsBackUsage.coerceAtLeast(0f)
                        val gsScore = max(gsCombinedPct / 100f, gsCombinedMs / frameBudgetMs)
                        val gpuScore = max(
                            nativePerf.gpuUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.gpuMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val rankedNow = listOf(
                            PERF_PROFILE_EE to eeScore,
                            PERF_PROFILE_VU to vuScore,
                            PERF_PROFILE_GS to gsScore,
                            PERF_PROFILE_GPU to gpuScore,
                        ).sortedByDescending { it.second }
                        val strongest = rankedNow.first()
                        val weighted = severity * (1f + strongest.second.coerceIn(0f, 1.75f))
                        when (strongest.first) {
                            PERF_PROFILE_EE -> eePressure += weighted
                            PERF_PROFILE_VU -> vuPressure += weighted
                            PERF_PROFILE_GS -> gsPressure += weighted
                            PERF_PROFILE_GPU -> gpuPressure += weighted
                        }
'''
new_rank = '''                        val gsParallelMs = max(
                            nativePerf.gsMs.coerceAtLeast(0f),
                            nativePerf.gsBackMs.coerceAtLeast(0f)
                        )
                        val gsParallelPct = max(
                            nativePerf.gsUsage.coerceAtLeast(0f),
                            nativePerf.gsBackUsage.coerceAtLeast(0f)
                        )
                        val gsScore = max(gsParallelPct / 100f, gsParallelMs / frameBudgetMs)
                        val gpuScore = max(
                            nativePerf.gpuUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.gpuMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val balancedNow = nativePerf.gsBackTid > 0 &&
                            minOf(eeScore, vuScore) >= gsScore * 1.08f &&
                            minOf(eeScore, vuScore) >= 0.90f
                        if (balancedNow) {
                            val cpuCritical = minOf(eeScore, vuScore)
                            balancedPressure += severity * (1f + cpuCritical.coerceIn(0f, 1.75f))
                        } else {
                            val rankedNow = listOf(
                                PERF_PROFILE_EE to eeScore,
                                PERF_PROFILE_VU to vuScore,
                                PERF_PROFILE_GS to gsScore,
                                PERF_PROFILE_GPU to gpuScore,
                            ).sortedByDescending { it.second }
                            val strongest = rankedNow.first()
                            val weighted = severity * (1f + strongest.second.coerceIn(0f, 1.75f))
                            when (strongest.first) {
                                PERF_PROFILE_EE -> eePressure += weighted
                                PERF_PROFILE_VU -> vuPressure += weighted
                                PERF_PROFILE_GS -> gsPressure += weighted
                                PERF_PROFILE_GPU -> gpuPressure += weighted
                            }
                        }
'''
backend = replace_once(backend, old_rank, new_rank, "#22 parallel GS classifier")

backend = replace_once(
    backend,
    "                        PERF_PROFILE_GS to gsPressure,\n"
    "                        PERF_PROFILE_GPU to gpuPressure,\n",
    "                        PERF_PROFILE_GS to gsPressure,\n"
    "                        PERF_PROFILE_GPU to gpuPressure,\n"
    "                        PERF_PROFILE_BALANCED to balancedPressure,\n",
    "#22 persistent balanced ranking",
)

old_scheduler = '''    // Alpha 6 #21 scheduler assist: priority only, never affinity. This
    // leaves Android EAS free to choose cores while preventing the measured
    // GS/VU critical workers from sitting at background-like priority.
    private fun applySchedulerAssist(perf: PS2NativeBridge.Pcsx2PerfSample, power: PowerManager?) {
        if (!running || paused || power?.isPowerSaveMode == true) return
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return

        val tids = when (latestProfile) {
            PERF_PROFILE_GS -> intArrayOf(perf.gsTid, perf.gsBackTid, perf.vuTid, perf.eeTid)
            PERF_PROFILE_VU -> intArrayOf(perf.vuTid, perf.eeTid, perf.gsTid)
            PERF_PROFILE_EE, PERF_PROFILE_COMPUTE -> intArrayOf(perf.eeTid, perf.vuTid)
            else -> intArrayOf(perf.eeTid, perf.vuTid, perf.gsTid, perf.gsBackTid)
        }
        for (tid in tids) {
            if (tid <= 0 || !boostedPs2Tids.add(tid)) continue
            runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY) }
                .onFailure { boostedPs2Tids.remove(tid) }
        }
    }
'''
new_scheduler = '''    // Alpha 6 #22: reversible priority-only load balancer. The #21 device test
    // showed true GS pipelining working (GSB present), but EE and VU became the
    // longer parallel branches. Keep EAS in control of core placement; only
    // rebalance nice priority so the extra GS worker cannot starve EE/VU.
    private fun applySchedulerAssist(perf: PS2NativeBridge.Pcsx2PerfSample, power: PowerManager?) {
        if (!running || paused) return
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE

        if (power?.isPowerSaveMode == true || thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            for (tid in ps2TidPriorities.keys) setPs2Priority(tid, Process.THREAD_PRIORITY_DEFAULT)
            return
        }

        val gsBranchMs = max(perf.gsMs.coerceAtLeast(0f), perf.gsBackMs.coerceAtLeast(0f))
        val cpuBranchMs = minOf(perf.eeMs.coerceAtLeast(0f), perf.vuMs.coerceAtLeast(0f))
        val cpuBranchPct = minOf(perf.eeUsage.coerceAtLeast(0f), perf.vuUsage.coerceAtLeast(0f))
        val liveBalanced = perf.gsBackTid > 0 && gsBranchMs > 0f &&
            cpuBranchMs > gsBranchMs * 1.10f && cpuBranchPct >= 60f

        if (liveBalanced || latestProfile == PERF_PROFILE_BALANCED) {
            // Reserve the scarce fast-core time for EE/VU while retaining the
            // Vulkan GS split. This is deliberately not affinity pinning.
            setPs2Priority(perf.eeTid, Process.THREAD_PRIORITY_DISPLAY)
            setPs2Priority(perf.vuTid, Process.THREAD_PRIORITY_DISPLAY)
            setPs2Priority(perf.gsTid, Process.THREAD_PRIORITY_DEFAULT)
            setPs2Priority(perf.gsBackTid, Process.THREAD_PRIORITY_DEFAULT)
            return
        }

        when (latestProfile) {
            PERF_PROFILE_GS -> {
                setPs2Priority(perf.eeTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.vuTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.gsTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.gsBackTid, Process.THREAD_PRIORITY_DISPLAY)
            }
            PERF_PROFILE_VU -> {
                setPs2Priority(perf.vuTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.eeTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.gsTid, Process.THREAD_PRIORITY_DEFAULT)
                setPs2Priority(perf.gsBackTid, Process.THREAD_PRIORITY_DEFAULT)
            }
            PERF_PROFILE_EE, PERF_PROFILE_COMPUTE -> {
                setPs2Priority(perf.eeTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.vuTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.gsTid, Process.THREAD_PRIORITY_DEFAULT)
                setPs2Priority(perf.gsBackTid, Process.THREAD_PRIORITY_DEFAULT)
            }
            else -> {
                setPs2Priority(perf.eeTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.vuTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.gsTid, Process.THREAD_PRIORITY_DISPLAY)
                setPs2Priority(perf.gsBackTid, Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }

    private fun setPs2Priority(tid: Int, priority: Int) {
        if (tid <= 0 || ps2TidPriorities[tid] == priority) return
        runCatching { Process.setThreadPriority(tid, priority) }
            .onSuccess { ps2TidPriorities[tid] = priority }
    }
'''
backend = replace_once(backend, old_scheduler, new_scheduler, "#22 scheduler rebalance")

# Keep diagnostics honest: GS and GSB are parallel branches, so log the maximum
# branch rather than the physically meaningless sum inherited from #19.
backend = backend.replace(
    '"GS=${String.format("%.0f", nativePerf.gsUsage + nativePerf.gsBackUsage)}%/" +\n'
    '                            "${String.format("%.2f", nativePerf.gsMs + nativePerf.gsBackMs)}ms " +',
    '"GSmax=${String.format("%.0f", max(nativePerf.gsUsage, nativePerf.gsBackUsage))}%/" +\n'
    '                            "${String.format("%.2f", max(nativePerf.gsMs, nativePerf.gsBackMs))}ms " +',
)
backend = backend.replace('"A6#19 native=$nativeMetricSeen profile=', '"A6#22 native=$nativeMetricSeen profile=')
backend = backend.replace('"A6#19 preboot profile=', '"A6#22 preboot profile=')

BACKEND.write_text(backend)
print("OMNICORE_PCSX2_ALPHA6_22_PATCH_OK gs_parallel_score=1 balanced_profile=1 ee_vu_priority=1 reversible_scheduler=1 queue2=1")
