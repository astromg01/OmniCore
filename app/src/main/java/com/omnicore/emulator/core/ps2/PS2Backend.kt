package com.omnicore.emulator.core.ps2

import android.view.Surface

/**
 * Stable OmniCore-facing PS2 backend boundary.
 *
 * Third-party emulator APIs must stay behind this interface so UI, input,
 * storage and SmartPerf remain independent from a specific implementation.
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

    data class RuntimeConfig(
        val renderer: Renderer = Renderer.AUTO,
        val biosMode: BiosMode = BiosMode.AUTO,
        val qualityFloorScale: Float = 1.0f,
        val textureCacheMiB: Int = 96,
        val jitCacheMiB: Int = 24,
        val audioTargetMs: Int = 64,
        val queueAheadFrames: Int = 1,
        val allowAsyncTextureUpload: Boolean = true,
        val allowCycleSkipping: Boolean = false
    ) {
        init {
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
     * Unknown values stay negative instead of being guessed.
     */
    data class Telemetry(
        val hostFrameMs: Float = -1f,
        val eeMs: Float = -1f,
        val vuMs: Float = -1f,
        val gsMs: Float = -1f,
        val presentMs: Float = -1f,
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
        fun hasFrameBreakdown(): Boolean = eeMs >= 0f || vuMs >= 0f || gsMs >= 0f
    }
}
