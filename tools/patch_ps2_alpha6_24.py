#!/usr/bin/env python3
from pathlib import Path

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")
BRIDGE = Path("app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt")
CONSTANTS = Path("app/src/main/java/com/omnicore/emulator/core/ps2/PS2PerformanceConstants.kt")
TELEMETRY = Path("app/src/main/java/com/omnicore/emulator/core/ps2/PS2Backend.kt")
ACTIVITY = Path("app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt")
NATIVE_APP = Path("app/src/main/java/kr/co/iefriends/pcsx2/NativeApp.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Java ABI: the #24 source-built core exports a stable JNI visibility snapshot.
# ---------------------------------------------------------------------------
native_app = NATIVE_APP.read_text()
native_app = replace_once(
    native_app,
    "    public static native float getFPS();\n"
    "    public static native float getNominalFrameRate();\n",
    "    public static native float getFPS();\n"
    "    public static native float getNominalFrameRate();\n"
    "    public static native String getOmniVisibilitySnapshot();\n",
    "#24 visibility JNI declaration",
)
NATIVE_APP.write_text(native_app)

# ---------------------------------------------------------------------------
# Kotlin bridge: parse cumulative primitive/effect counters from the core.
# ---------------------------------------------------------------------------
bridge = BRIDGE.read_text()
bridge = replace_once(
    bridge,
    "        val source: String = \"unavailable\"\n"
    "    )\n\n"
    "    fun descriptor(): String = nativeDescriptor()\n",
    "        val source: String = \"unavailable\"\n"
    "    )\n\n"
    "    data class Pcsx2VisibilitySample(\n"
    "        val available: Boolean = false,\n"
    "        val cullTests: Long = 0L,\n"
    "        val culled: Long = 0L,\n"
    "        val drawBatches: Long = 0L,\n"
    "        val fogDrawBatches: Long = 0L,\n"
    "        val alphaDrawBatches: Long = 0L,\n"
    "        val indices: Long = 0L,\n"
    "        val fogIndices: Long = 0L,\n"
    "        val alphaIndices: Long = 0L,\n"
    "        val source: String = \"unavailable\"\n"
    "    )\n\n"
    "    fun descriptor(): String = nativeDescriptor()\n",
    "#24 visibility sample data",
)
bridge = replace_once(
    bridge,
    "    private fun parsePerf(raw: String): Pcsx2PerfSample {\n",
    "    fun samplePcsx2Visibility(): Pcsx2VisibilitySample = parseVisibility(\n"
    "        runCatching { NativeApp.getOmniVisibilitySnapshot() }.getOrDefault(\"\")\n"
    "    )\n\n"
    "    private fun parseVisibility(raw: String): Pcsx2VisibilitySample {\n"
    "        if (raw.isBlank()) return Pcsx2VisibilitySample()\n"
    "        val values = parseKeyValues(raw)\n"
    "        return Pcsx2VisibilitySample(\n"
    "            available = values[\"ok\"] == \"1\",\n"
    "            cullTests = values.long(\"cullTests\"),\n"
    "            culled = values.long(\"culled\"),\n"
    "            drawBatches = values.long(\"drawBatches\"),\n"
    "            fogDrawBatches = values.long(\"fogDraws\"),\n"
    "            alphaDrawBatches = values.long(\"alphaDraws\"),\n"
    "            indices = values.long(\"indices\"),\n"
    "            fogIndices = values.long(\"fogIndices\"),\n"
    "            alphaIndices = values.long(\"alphaIndices\"),\n"
    "            source = values[\"source\"].orEmpty().ifBlank { \"unavailable\" }\n"
    "        )\n"
    "    }\n\n"
    "    private fun parsePerf(raw: String): Pcsx2PerfSample {\n",
    "#24 visibility parser",
)
bridge = replace_once(
    bridge,
    "    private fun Map<String, String>.float(key: String): Float =\n"
    "        this[key]?.toFloatOrNull() ?: -1f\n",
    "    private fun Map<String, String>.float(key: String): Float =\n"
    "        this[key]?.toFloatOrNull() ?: -1f\n\n"
    "    private fun Map<String, String>.long(key: String): Long =\n"
    "        this[key]?.toLongOrNull() ?: 0L\n",
    "#24 long parser",
)
BRIDGE.write_text(bridge)

# ---------------------------------------------------------------------------
# Visibility classification: EFFECTS means fog/alpha workload is proven high.
# ---------------------------------------------------------------------------
constants = CONSTANTS.read_text()
constants = replace_once(
    constants,
    "internal const val PERF_VIS_FILL = 2\n",
    "internal const val PERF_VIS_FILL = 2\n"
    "internal const val PERF_VIS_EFFECTS = 3\n",
    "#24 effects visibility constant",
)
constants = replace_once(
    constants,
    "    PERF_VIS_FILL -> \"FILL\"\n",
    "    PERF_VIS_FILL -> \"FILL\"\n"
    "    PERF_VIS_EFFECTS -> \"EFFECTS\"\n",
    "#24 effects visibility name",
)
CONSTANTS.write_text(constants)

telemetry = TELEMETRY.read_text()
telemetry = replace_once(
    telemetry,
    "        val visibilityPressure: String = \"UNKNOWN\",\n"
    "        val peakFrameMs: Float = -1f,\n",
    "        val visibilityPressure: String = \"UNKNOWN\",\n"
    "        val visibilityCullPercent: Float = -1f,\n"
    "        val visibilityTestsPerSecond: Float = -1f,\n"
    "        val fogWorkPercent: Float = -1f,\n"
    "        val alphaWorkPercent: Float = -1f,\n"
    "        val peakFrameMs: Float = -1f,\n",
    "#24 telemetry visibility fields",
)
TELEMETRY.write_text(telemetry)

# ---------------------------------------------------------------------------
# Backend: BALANCED gets strict Lockstep (mode 2) when the selected renderer can
# support it. Unlike #21 Pipelined (mode 3), #24 never forces Vulkan solely for
# this experiment. Visibility counters are read-only and drive only labels.
# ---------------------------------------------------------------------------
backend = BACKEND.read_text()
backend = replace_once(
    backend,
    "    @Volatile private var latestVisibility = PERF_VIS_UNKNOWN\n"
    "    @Volatile private var latestPeakFrameMs = -1f\n",
    "    @Volatile private var latestVisibility = PERF_VIS_UNKNOWN\n"
    "    @Volatile private var latestVisibilityCullPercent = -1f\n"
    "    @Volatile private var latestVisibilityTestsPerSecond = -1f\n"
    "    @Volatile private var latestFogWorkPercent = -1f\n"
    "    @Volatile private var latestAlphaWorkPercent = -1f\n"
    "    @Volatile private var latestPeakFrameMs = -1f\n",
    "#24 backend visibility fields",
)
backend = replace_once(
    backend,
    "            latestPerf = PS2NativeBridge.Pcsx2PerfSample()\n"
    "            latestPeakFrameMs = -1f\n",
    "            latestPerf = PS2NativeBridge.Pcsx2PerfSample()\n"
    "            latestVisibilityCullPercent = -1f\n"
    "            latestVisibilityTestsPerSecond = -1f\n"
    "            latestFogWorkPercent = -1f\n"
    "            latestAlphaWorkPercent = -1f\n"
    "            latestPeakFrameMs = -1f\n",
    "#24 boot visibility reset",
)
backend = replace_once(
    backend,
    "            visibilityPressure = ps2VisibilityName(latestVisibility),\n"
    "            peakFrameMs = latestPeakFrameMs,\n",
    "            visibilityPressure = ps2VisibilityName(latestVisibility),\n"
    "            visibilityCullPercent = latestVisibilityCullPercent,\n"
    "            visibilityTestsPerSecond = latestVisibilityTestsPerSecond,\n"
    "            fogWorkPercent = latestFogWorkPercent,\n"
    "            alphaWorkPercent = latestAlphaWorkPercent,\n"
    "            peakFrameMs = latestPeakFrameMs,\n",
    "#24 telemetry values",
)
backend = replace_once(
    backend,
    "        val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS\n"
    "        val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED\n",
    "        val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS\n"
    "        val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED\n"
    "        // Lockstep keeps strict GS ordering and therefore avoids the true\n"
    "        // two-object Pipelined path which flickered fog on the #21/#22\n"
    "        // device test. Renderer compatibility is left to PCSX2; unlike #21\n"
    "        // this does not force Vulkan for BALANCED.\n"
    "        val useGsLockstep = pipelineCapable && visualSafeBalanced\n"
    "        val gsBackMode = when {\n"
    "            useGsPipeline -> 3\n"
    "            useGsLockstep -> 2\n"
    "            else -> 0\n"
    "        }\n",
    "#24 lockstep policy",
)
backend = replace_once(
    backend,
    "        NativeApp.setSetting(\"EmuCore/GS\", \"GSBackThreadMode\", \"int\", if (useGsPipeline) \"3\" else \"0\")\n",
    "        NativeApp.setSetting(\"EmuCore/GS\", \"GSBackThreadMode\", \"int\", gsBackMode.toString())\n",
    "#24 GS back mode",
)
backend = backend.replace(
    '"visualSafeBalanced=$visualSafeBalanced " +',
    '"visualSafeBalanced=$visualSafeBalanced gsLockstep=$useGsLockstep " +',
)
backend = backend.replace('"A6#23 preboot profile=', '"A6#24 preboot profile=')
backend = backend.replace('"A6#23 native=$nativeMetricSeen profile=', '"A6#24 native=$nativeMetricSeen profile=')

backend = replace_once(
    backend,
    "            var nativeMetricSeen = false\n\n"
    "            latestProfile = learned\n",
    "            var nativeMetricSeen = false\n"
    "            var lastVisibilitySample = PS2NativeBridge.Pcsx2VisibilitySample()\n"
    "            var lastVisibilityWallNs = System.nanoTime()\n\n"
    "            latestProfile = learned\n",
    "#24 visibility profiler state",
)

# #21 has already changed this call to pass vmThreadTid and adds scheduler assist.
visibility_anchor = '''                if (nativePerf.available) {
                    latestPerf = nativePerf
                    nativeMetricSeen = true
                    applySchedulerAssist(nativePerf, power)
                }
'''
visibility_block = visibility_anchor + '''
                val visibilityPerf = runCatching { PS2NativeBridge.samplePcsx2Visibility() }
                    .getOrDefault(PS2NativeBridge.Pcsx2VisibilitySample())
                if (visibilityPerf.available) {
                    if (lastVisibilitySample.available) {
                        val windowMs = ((nowWallNs - lastVisibilityWallNs) / 1_000_000f).coerceAtLeast(1f)
                        val testDelta = (visibilityPerf.cullTests - lastVisibilitySample.cullTests).coerceAtLeast(0L)
                        val culledDelta = (visibilityPerf.culled - lastVisibilitySample.culled).coerceAtLeast(0L)
                        val indexDelta = (visibilityPerf.indices - lastVisibilitySample.indices).coerceAtLeast(0L)
                        val fogIndexDelta = (visibilityPerf.fogIndices - lastVisibilitySample.fogIndices).coerceAtLeast(0L)
                        val alphaIndexDelta = (visibilityPerf.alphaIndices - lastVisibilitySample.alphaIndices).coerceAtLeast(0L)

                        val cullPercent = if (testDelta > 0L) {
                            (culledDelta * 100.0 / testDelta.toDouble()).toFloat().coerceIn(0f, 100f)
                        } else 0f
                        val testsPerSecond = (testDelta * 1000.0 / windowMs.toDouble()).toFloat().coerceAtLeast(0f)
                        val fogPercent = if (indexDelta > 0L) {
                            (fogIndexDelta * 100.0 / indexDelta.toDouble()).toFloat().coerceIn(0f, 100f)
                        } else 0f
                        val alphaPercent = if (indexDelta > 0L) {
                            (alphaIndexDelta * 100.0 / indexDelta.toDouble()).toFloat().coerceIn(0f, 100f)
                        } else 0f

                        latestVisibilityCullPercent = cullPercent
                        latestVisibilityTestsPerSecond = testsPerSecond
                        latestFogWorkPercent = fogPercent
                        latestAlphaWorkPercent = alphaPercent

                        // EFFECTS is evidence-based: no fog/alpha effect is ever
                        // disabled. GEOMETRY means the primitive stream itself is
                        // substantial enough to justify deeper visibility work.
                        val measuredVisibility = when {
                            fogPercent >= 18f || alphaPercent >= 35f -> PERF_VIS_EFFECTS
                            testsPerSecond >= 300f -> PERF_VIS_GEOMETRY
                            else -> PERF_VIS_UNKNOWN
                        }
                        if (measuredVisibility != PERF_VIS_UNKNOWN && measuredVisibility != visibilityClass) {
                            prefs.edit().putInt("${profileKey}_visibility", measuredVisibility).apply()
                            visibilityClass = measuredVisibility
                            latestVisibility = measuredVisibility
                        }
                    }
                    lastVisibilitySample = visibilityPerf
                    lastVisibilityWallNs = nowWallNs
                }
'''
backend = replace_once(backend, visibility_anchor, visibility_block, "#24 visibility sampling")

# Do not let optional VS/PS counters overwrite a proven effect-heavy core sample.
backend = backend.replace(
    "                    if (nativePerf.available && nativePerf.vsInvocations > 0.0 &&\n",
    "                    if (visibilityClass != PERF_VIS_EFFECTS && nativePerf.available && nativePerf.vsInvocations > 0.0 &&\n",
)

# Add visibility evidence to the six-sample diagnostic line.
backend = backend.replace(
    '"vis=${ps2VisibilityName(visibilityClass)} " +',
    '"vis=${ps2VisibilityName(visibilityClass)} " +\n'
    '                            "cull=${String.format(\"%.1f\", latestVisibilityCullPercent)}% " +\n'
    '                            "prim=${String.format(\"%.0f\", latestVisibilityTestsPerSecond)}/s " +\n'
    '                            "fog=${String.format(\"%.1f\", latestFogWorkPercent)}% " +\n'
    '                            "alpha=${String.format(\"%.1f\", latestAlphaWorkPercent)}% " +',
)

BACKEND.write_text(backend)

# ---------------------------------------------------------------------------
# HUD: expose actual Visibility evidence and distinguish strict SYNC from the
# unsafe two-object Pipelined mode. Seven lines fit without clipping.
# ---------------------------------------------------------------------------
activity = ACTIVITY.read_text()
activity = replace_once(activity, "            maxLines = 6\n", "            maxLines = 7\n", "#24 HUD max lines")
activity = replace_once(
    activity,
    '        val pipe = if (t.gsBackUsagePercent >= 0f || t.gsBackMs >= 0f) "ON" else "OFF"\n',
    '        val hasGsBack = t.gsBackUsagePercent >= 0f || t.gsBackMs >= 0f\n'
    '        val pipe = when {\n'
    '            hasGsBack && t.bottleneck == "BALANCED" -> "SYNC"\n'
    '            hasGsBack -> "ON"\n'
    '            else -> "OFF"\n'
    '        }\n',
    "#24 HUD sync state",
)
activity = replace_once(
    activity,
    '        append("GPU ${fmt(t.gpuUsagePercent)}%/${fmt(t.presentMs)}ms • frame ${fmt(t.frameAverageMs)}/${fmt(t.frameMaximumMs)}ms\\n")\n'
    '        append("peak ${fmt(t.peakFrameMs)}ms • spikes ${t.spikeCount} • T${t.thermalStatus}")\n',
    '        append("GPU ${fmt(t.gpuUsagePercent)}%/${fmt(t.presentMs)}ms • frame ${fmt(t.frameAverageMs)}/${fmt(t.frameMaximumMs)}ms\\n")\n'
    '        append("CULL ${fmt(t.visibilityCullPercent)}% • prim ${fmt(t.visibilityTestsPerSecond)}/s • FOG ${fmt(t.fogWorkPercent)}% • ALPHA ${fmt(t.alphaWorkPercent)}%\\n")\n'
    '        append("peak ${fmt(t.peakFrameMs)}ms • spikes ${t.spikeCount} • T${t.thermalStatus}")\n',
    "#24 HUD visibility line",
)
ACTIVITY.write_text(activity)

print('OMNICORE_PCSX2_ALPHA6_24_RUNTIME_PATCH_OK visibility_v1=1 effects_counter=1 lockstep_balanced=1 hud_sync=1 no_fog_disable=1')
