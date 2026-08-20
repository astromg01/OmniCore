package com.omnicore.emulator.core.ps2

import android.view.Surface

/**
 * Stable OmniCore-facing PS2 backend boundary.
 *
 * Third-party emulator APIs stay behind this interface so UI, input, storage
 * and SmartPerf remain independent from a specific implementation.
 */
interface PS2Backend {
    val id: String

    fun probe(): Capabilities
    fun attachSurface(surface: Surface?)
    fun boot(request: BootRequest): BootResult
    fun pause()
    fun resume()
    fun stop()
    fun telemetry(): Telemetry

    /**
     * Session-only frame limiter control used by SmartPerf probes.
     * Implementations must not persist this outside the active PS2 session.
     */
    fun setFrameLimit(enabled: Boolean): Boolean

    /** DualShock 2 input sink used by touch and physical Android controllers. */
    fun setButton(controlId: Int, pressed: Boolean)
    fun setAxis(controlId: Int, value: Float)
    fun releaseAllInput()

    /** Process-scoped state slots. Returns false when unsupported/not ready. */
    fun saveState(slot: Int): Boolean
    fun loadState(slot: Int): Boolean

    data class Capabilities(
        val available: Boolean,
        val arm64Jit: Boolean,
        val vulkan: Boolean,
        val gles3: Boolean,
        val hleBios: Boolean,
        val externalBios: Boolean,
        val saveStates: Boolean,
        val backendVersion: String,
        val notes: String = ""
    )

    enum class Renderer {
        AUTO,
        VULKAN,
        GLES3
    }

    enum class BiosMode {
        AUTO,
        HLE,
        EXTERNAL
    }

    /**
     * Static pre-boot policy tier. SAFE/OPTIMAL/FAST are resolved once per
     * session; they never mutate EE timing or visual quality while the VM runs.
     */
    enum class PerformanceTier {
        SAFE,
        OPTIMAL,
        FAST
    }

    data class RuntimeConfig(
        val renderer: Renderer = Renderer.AUTO,
        val biosMode: BiosMode = BiosMode.AUTO,
        /** PCSX2 internal-resolution multiplier: 1, 2 or 4. */
        val internalResolutionFactor: Int = 1,
        val widescreen: Boolean = false,
        val presentationMode: Int = 1,
        val forceBilinear: Boolean = false,
        val limitFrameRate: Boolean = true,
        val spuBlockCount: Int = 100,
        val qualityFloorScale: Float = 1.0f,
        val textureCacheMiB: Int = 96,
        val jitCacheMiB: Int = 24,
        val audioTargetMs: Int = 64,
        val queueAheadFrames: Int = 1,
        val allowAsyncTextureUpload: Boolean = true,
        val allowCycleSkipping: Boolean = false,
        val performanceTier: PerformanceTier = PerformanceTier.OPTIMAL
    ) {
        init {
            require(internalResolutionFactor in setOf(1, 2, 4))
            require(presentationMode in 0..2)
            require(spuBlockCount in 10..400)
            require(qualityFloorScale >= 1.0f) { "PS2 quality floor cannot be below native scale." }
            require(textureCacheMiB in 32..512)
            require(jitCacheMiB in 8..256)
            require(audioTargetMs in 32..160)
            require(queueAheadFrames in 0..3)
        }
    }

    data class BootRequest(
        val imagePath: String,
        val externalBiosPath: String? = null,
        val gameKey: String,
        val config: RuntimeConfig
    )

    sealed interface BootResult {
        data class Started(val backend: String, val renderer: Renderer) : BootResult
        data class Rejected(val reason: String) : BootResult
        data class Failed(val reason: String, val recoverable: Boolean) : BootResult
    }

    /**
     * Times are split by subsystem whenever the backend can expose them.
     * Unknown numeric values stay negative instead of being guessed.
     */
    data class Telemetry(
        val hostFrameMs: Float = -1f,
        val measuredFps: Float = -1f,
        val internalFps: Float = -1f,
        val emulationSpeedPercent: Float = -1f,
        val drawCallsPerFrame: Float = -1f,
        val eeMs: Float = -1f,
        val vuMs: Float = -1f,
        val gsMs: Float = -1f,
        val gsBackMs: Float = -1f,
        val presentMs: Float = -1f,
        val frameAverageMs: Float = -1f,
        val frameMaximumMs: Float = -1f,
        val eeUsagePercent: Float = -1f,
        val vuUsagePercent: Float = -1f,
        val gsUsagePercent: Float = -1f,
        val gsBackUsagePercent: Float = -1f,
        val gpuUsagePercent: Float = -1f,
        val gpuVertexInvocations: Double = -1.0,
        val gpuPixelInvocations: Double = -1.0,
        val bottleneck: String = "UNKNOWN",
        val visibilityPressure: String = "UNKNOWN",
        val peakFrameMs: Float = -1f,
        val spikeCount: Int = 0,
        val audioFillMs: Float = -1f,
        val hardAudioUnderruns: Long = 0,
        val jitCacheUsedMiB: Float = -1f,
        val jitInvalidations: Long = 0,
        val textureCacheUsedMiB: Float = -1f,
        val thermalStatus: Int = 0,
        val memoryPressure: Float = 0f,
        val renderer: Renderer = Renderer.AUTO,
        val sampleFrames: Int = 0
    ) {
        fun hasFrameBreakdown(): Boolean = eeMs >= 0f || vuMs >= 0f || gsMs >= 0f || presentMs >= 0f
    }
}
