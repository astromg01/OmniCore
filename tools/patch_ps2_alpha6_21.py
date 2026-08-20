#!/usr/bin/env python3
from pathlib import Path

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")
BRIDGE = Path("app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


backend = BACKEND.read_text()

backend = replace_once(
    backend,
    "    @Volatile private var vmThread: Thread? = null\n"
    "    @Volatile private var governorThread: Thread? = null\n",
    "    @Volatile private var vmThread: Thread? = null\n"
    "    // Alpha 6 #21: Linux TID of the real PCSX2 EE/VM thread. The #20 HUD\n"
    "    // sampled GS/MTVU correctly, but EE stayed unknown because callers ran\n"
    "    // from the profiler/UI threads. Keep the VM TID explicitly.\n"
    "    @Volatile private var vmThreadTid: Int = -1\n"
    "    private val boostedPs2Tids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()\n"
    "    @Volatile private var governorThread: Thread? = null\n",
    "#21 VM TID fields",
)

backend = replace_once(
    backend,
    "            val thread = Thread({\n"
    "                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }\n"
    "                try {\n"
    "                    NativeApp.runVMThread(request.imagePath)\n"
    "                } finally {\n"
    "                    governorStop = true\n"
    "                    running = false\n"
    "                    paused = false\n"
    "                }\n",
    "            val thread = Thread({\n"
    "                vmThreadTid = Process.myTid()\n"
    "                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }\n"
    "                try {\n"
    "                    NativeApp.runVMThread(request.imagePath)\n"
    "                } finally {\n"
    "                    vmThreadTid = -1\n"
    "                    governorStop = true\n"
    "                    running = false\n"
    "                    paused = false\n"
    "                }\n",
    "#21 capture EE thread TID",
)

backend = replace_once(
    backend,
    "        vmThread = null\n"
    "        attachedSurface = null\n",
    "        vmThread = null\n"
    "        vmThreadTid = -1\n"
    "        boostedPs2Tids.clear()\n"
    "        attachedSurface = null\n",
    "#21 stop cleanup",
)

old_call = "PS2NativeBridge.samplePcsx2Performance()"
new_call = "PS2NativeBridge.samplePcsx2Performance(vmThreadTid)"
if old_call in backend:
    if backend.count(old_call) < 2:
        raise SystemExit("#21 perf bridge: expected telemetry + profiler call sites")
    backend = backend.replace(old_call, new_call)

backend = replace_once(
    backend,
    "    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig, imagePath: String) {\n"
    "        activeRenderer = when (config.renderer) {\n"
    "            PS2Backend.Renderer.VULKAN -> {\n"
    "                NativeApp.renderVulkan()\n"
    "                PS2Backend.Renderer.VULKAN\n"
    "            }\n"
    "            PS2Backend.Renderer.GLES3 -> {\n"
    "                NativeApp.renderOpenGL()\n"
    "                PS2Backend.Renderer.GLES3\n"
    "            }\n"
    "            PS2Backend.Renderer.AUTO -> {\n"
    "                NativeApp.renderAuto()\n"
    "                PS2Backend.Renderer.AUTO\n"
    "            }\n"
    "        }\n",
    "    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig, imagePath: String) {\n"
    "        // Alpha 6 #21: the device test proved God of War is GS-bound, but\n"
    "        // GSB never appeared after reboot. The pinned ARMSX2 back-thread\n"
    "        // path only engages for Vulkan (or SW), so an AUTO title which has\n"
    "        // already learned GS pressure is steered to Vulkan. Explicit user\n"
    "        // GLES/Vulkan choices are still respected.\n"
    "        val earlyPerfPrefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)\n"
    "        val earlyLearnedProfile = earlyPerfPrefs.getInt(perfProfileKey(imagePath), PERF_PROFILE_UNKNOWN)\n"
    "        val hasVulkan = Build.VERSION.SDK_INT >= 24 &&\n"
    "            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)\n"
    "        val forceLearnedGsVulkan = config.renderer == PS2Backend.Renderer.AUTO &&\n"
    "            earlyLearnedProfile == PERF_PROFILE_GS && hasVulkan\n"
    "        activeRenderer = if (forceLearnedGsVulkan) {\n"
    "            NativeApp.renderVulkan()\n"
    "            PS2Backend.Renderer.VULKAN\n"
    "        } else when (config.renderer) {\n"
    "            PS2Backend.Renderer.VULKAN -> {\n"
    "                NativeApp.renderVulkan()\n"
    "                PS2Backend.Renderer.VULKAN\n"
    "            }\n"
    "            PS2Backend.Renderer.GLES3 -> {\n"
    "                NativeApp.renderOpenGL()\n"
    "                PS2Backend.Renderer.GLES3\n"
    "            }\n"
    "            PS2Backend.Renderer.AUTO -> {\n"
    "                NativeApp.renderAuto()\n"
    "                PS2Backend.Renderer.AUTO\n"
    "            }\n"
    "        }\n",
    "#21 learned GS Vulkan steering",
)

backend = replace_once(
    backend,
    "        val perfPrefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)\n",
    "        val perfPrefs = earlyPerfPrefs\n",
    "#21 reuse early perf prefs",
)

backend = replace_once(
    backend,
    "            PERF_PROFILE_GS, PERF_PROFILE_GPU, PERF_PROFILE_RENDER -> max(2, config.queueAheadFrames)\n",
    "            PERF_PROFILE_GS -> 3\n"
    "            PERF_PROFILE_GPU, PERF_PROFILE_RENDER -> max(2, config.queueAheadFrames)\n",
    "#21 GS queue depth",
)

backend = replace_once(
    backend,
    "                if (nativePerf.available) {\n"
    "                    latestPerf = nativePerf\n"
    "                    nativeMetricSeen = true\n"
    "                }\n",
    "                if (nativePerf.available) {\n"
    "                    latestPerf = nativePerf\n"
    "                    nativeMetricSeen = true\n"
    "                    applySchedulerAssist(nativePerf, power)\n"
    "                }\n",
    "#21 scheduler assist call",
)

backend = replace_once(
    backend,
    "    private fun perfProfileKey(imagePath: String): String =\n"
    "        \"game_${imagePath.hashCode().toUInt().toString(16)}\"\n",
    "    // Alpha 6 #21 scheduler assist: priority only, never affinity. This\n"
    "    // leaves Android EAS free to choose cores while preventing the measured\n"
    "    // GS/VU critical workers from sitting at background-like priority.\n"
    "    private fun applySchedulerAssist(perf: PS2NativeBridge.Pcsx2PerfSample, power: PowerManager?) {\n"
    "        if (!running || paused || power?.isPowerSaveMode == true) return\n"
    "        val thermal = if (Build.VERSION.SDK_INT >= 29) {\n"
    "            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }\n"
    "                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)\n"
    "        } else PowerManager.THERMAL_STATUS_NONE\n"
    "        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return\n"
    "\n"
    "        val tids = when (latestProfile) {\n"
    "            PERF_PROFILE_GS -> intArrayOf(perf.gsTid, perf.gsBackTid, perf.vuTid, perf.eeTid)\n"
    "            PERF_PROFILE_VU -> intArrayOf(perf.vuTid, perf.eeTid, perf.gsTid)\n"
    "            PERF_PROFILE_EE, PERF_PROFILE_COMPUTE -> intArrayOf(perf.eeTid, perf.vuTid)\n"
    "            else -> intArrayOf(perf.eeTid, perf.vuTid, perf.gsTid, perf.gsBackTid)\n"
    "        }\n"
    "        for (tid in tids) {\n"
    "            if (tid <= 0 || !boostedPs2Tids.add(tid)) continue\n"
    "            runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY) }\n"
    "                .onFailure { boostedPs2Tids.remove(tid) }\n"
    "        }\n"
    "    }\n"
    "\n"
    "    private fun perfProfileKey(imagePath: String): String =\n"
    "        \"game_${imagePath.hashCode().toUInt().toString(16)}\"\n",
    "#21 scheduler assist implementation",
)

BACKEND.write_text(backend)

bridge = BRIDGE.read_text()
bridge = replace_once(
    bridge,
    "        val psInvocations: Double = -1.0,\n"
    "        val source: String = \"unavailable\"\n",
    "        val psInvocations: Double = -1.0,\n"
    "        val eeTid: Int = -1,\n"
    "        val vuTid: Int = -1,\n"
    "        val gsTid: Int = -1,\n"
    "        val gsBackTid: Int = -1,\n"
    "        val source: String = \"unavailable\"\n",
    "#21 perf sample TIDs",
)

bridge = replace_once(
    bridge,
    "            psInvocations = values[\"ps\"]?.toDoubleOrNull() ?: -1.0,\n"
    "            source = values[\"source\"].orEmpty().ifBlank { \"unavailable\" }\n",
    "            psInvocations = values[\"ps\"]?.toDoubleOrNull() ?: -1.0,\n"
    "            eeTid = values[\"eeTid\"]?.toIntOrNull() ?: -1,\n"
    "            vuTid = values[\"vuTid\"]?.toIntOrNull() ?: -1,\n"
    "            gsTid = values[\"gsTid\"]?.toIntOrNull() ?: -1,\n"
    "            gsBackTid = values[\"gsbTid\"]?.toIntOrNull() ?: -1,\n"
    "            source = values[\"source\"].orEmpty().ifBlank { \"unavailable\" }\n",
    "#21 parse perf TIDs",
)
BRIDGE.write_text(bridge)

print("OMNICORE_PCSX2_ALPHA6_21_PATCH_OK vm_tid=1 gs_vulkan_steering=1 scheduler_assist=1 gs_queue=3")
