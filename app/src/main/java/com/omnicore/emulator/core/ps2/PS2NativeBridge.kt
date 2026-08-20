package com.omnicore.emulator.core.ps2

/** Native PS2 foundation/boot-bridge probe plus low-overhead PCSX2 perf sampling. */
object PS2NativeBridge {
    init {
        System.loadLibrary("omnicore_ps2_runtime")
    }

    data class Probe(
        val apiLevel: Int,
        val pointerBits: Int,
        val pageSize: Int,
        val architecture: String,
        val vulkanLoader: Boolean,
        val gles3Build: Boolean,
        val playBackend: Boolean,
        val playBootApi: Boolean,
        val foundationVersion: String,
        val playRevision: String
    )

    /**
     * Snapshot of PCSX2's native PerformanceMetrics counters.
     *
     * Percent fields are per-thread/device utilization over PCSX2's rolling
     * window. Millisecond fields are average time per emulated frame. The bridge
     * resolves the pinned emucore symbols dynamically so OmniCore remains able to
     * fall back to its Android process-level classifier if a future core hides or
     * renames a metric symbol instead of crashing the VM.
     */
    data class Pcsx2PerfSample(
        val available: Boolean = false,
        val eeUsage: Float = -1f,
        val eeMs: Float = -1f,
        val vuUsage: Float = -1f,
        val vuMs: Float = -1f,
        val gsUsage: Float = -1f,
        val gsMs: Float = -1f,
        val gsBackUsage: Float = -1f,
        val gsBackMs: Float = -1f,
        val gpuUsage: Float = -1f,
        val gpuMs: Float = -1f,
        val frameAvgMs: Float = -1f,
        val frameMinMs: Float = -1f,
        val frameMaxMs: Float = -1f,
        val vsInvocations: Double = -1.0,
        val psInvocations: Double = -1.0,
        val source: String = "unavailable"
    )

    fun descriptor(): String = nativeDescriptor()

    fun probe(): Probe {
        val values = parseKeyValues(nativeProbe())
        return Probe(
            apiLevel = values["api"]?.toIntOrNull() ?: -1,
            pointerBits = values["ptr"]?.toIntOrNull() ?: -1,
            pageSize = values["page"]?.toIntOrNull() ?: -1,
            architecture = values["arch"].orEmpty().ifBlank { "unknown" },
            vulkanLoader = values["vulkan"] == "1",
            gles3Build = values["gles3"] == "1",
            playBackend = values["play"] == "1",
            playBootApi = values["playboot"] == "1",
            foundationVersion = values["version"].orEmpty().ifBlank { "unknown" },
            playRevision = values["playrev"].orEmpty().ifBlank { "unknown" }
        )
    }

    fun samplePcsx2Performance(): Pcsx2PerfSample {
        val raw = runCatching { nativePcsx2Performance() }.getOrDefault("")
        if (raw.isBlank()) return Pcsx2PerfSample()
        val values = parseKeyValues(raw)
        return Pcsx2PerfSample(
            available = values["ok"] == "1",
            eeUsage = values.float("eePct"),
            eeMs = values.float("eeMs"),
            vuUsage = values.float("vuPct"),
            vuMs = values.float("vuMs"),
            gsUsage = values.float("gsPct"),
            gsMs = values.float("gsMs"),
            gsBackUsage = values.float("gsbPct"),
            gsBackMs = values.float("gsbMs"),
            gpuUsage = values.float("gpuPct"),
            gpuMs = values.float("gpuMs"),
            frameAvgMs = values.float("frameAvgMs"),
            frameMinMs = values.float("frameMinMs"),
            frameMaxMs = values.float("frameMaxMs"),
            vsInvocations = values["vs"]?.toDoubleOrNull() ?: -1.0,
            psInvocations = values["ps"]?.toDoubleOrNull() ?: -1.0,
            source = values["source"].orEmpty().ifBlank { "unavailable" }
        )
    }

    private fun parseKeyValues(raw: String): Map<String, String> = raw
        .split(';')
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
        }
        .toMap()

    private fun Map<String, String>.float(key: String): Float =
        this[key]?.toFloatOrNull() ?: -1f

    private external fun nativeDescriptor(): String
    private external fun nativeProbe(): String
    private external fun nativePcsx2Performance(): String
}
