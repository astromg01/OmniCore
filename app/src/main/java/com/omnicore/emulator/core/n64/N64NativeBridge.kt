package com.omnicore.emulator.core.n64

import android.view.Surface
import com.omnicore.emulator.performance.N64SmartPerf
import com.omnicore.emulator.settings.N64InputSettings
import com.omnicore.emulator.storage.N64Storage
import java.io.File
import kotlin.math.roundToInt

/** JNI boundary owned exclusively by the Nintendo 64 runtime. */
object N64NativeBridge {
    data class Telemetry(
        val averageFrameMs: Float = 0f,
        val p95FrameMs: Float = 0f,
        val droppedFrames: Int = 0,
        val audioUnderruns: Int = 0,
        val sampleWindowFrames: Int = 0
    ) {
        fun smartPerf(): N64SmartPerf.Telemetry = N64SmartPerf.Telemetry(
            averageFrameMs = averageFrameMs,
            p95FrameMs = p95FrameMs,
            droppedFrames = droppedFrames,
            audioUnderruns = audioUnderruns,
            sampleWindowFrames = sampleWindowFrames
        )
    }

    private val runtimeLoaded: Boolean = runCatching {
        System.loadLibrary("omnicore_n64_runtime")
        true
    }.getOrDefault(false)

    @Volatile private var coreAvailable: Boolean? = null

    fun hasCore(): Boolean {
        if (!runtimeLoaded) return false
        coreAvailable?.let { return it }
        return synchronized(this) {
            coreAvailable ?: runCatching { nativeHasCore() }.getOrDefault(false).also { coreAvailable = it }
        }
    }

    fun runtimeInfo(): String = if (!runtimeLoaded) {
        "N64 Runtime indisponível"
    } else {
        runCatching { nativeRuntimeInfo() }.getOrDefault("N64 Runtime")
    }

    fun start(
        surface: Surface,
        rom: File,
        paths: N64Storage.Paths,
        decision: N64SmartPerf.Decision,
        input: N64InputSettings.Config
    ): Boolean {
        if (!runtimeLoaded || !surface.isValid || !rom.isFile || rom.length() <= 0L) return false
        val config = decision.effective
        val pak = when (input.pakMode) {
            N64InputSettings.PakMode.RUMBLE -> "rumble"
            N64InputSettings.PakMode.NONE -> "none"
            N64InputSettings.PakMode.AUTO,
            N64InputSettings.PakMode.MEMORY -> "memory"
        }
        val diagnosticPath = File(paths.root, "last_boot_stage.txt").absolutePath
        val verificationPath = File(paths.root, "boot_verified.flag").absolutePath
        runCatching {
            File(diagnosticPath).writeText(
                "stage=kotlin:native_start\ntimestamp=${System.currentTimeMillis()}\n" +
                    "detail=${config.cpuMode.storage},threaded=${config.threadedRenderer},fb=${config.framebufferEmulation}\n"
            )
        }
        return runCatching {
            nativeStart(
                surface = surface,
                romPath = rom.absolutePath,
                systemDir = paths.system.absolutePath,
                saveDir = paths.saves.absolutePath,
                diagnosticPath = diagnosticPath,
                verificationPath = verificationPath,
                cpuMode = config.cpuMode.storage,
                rspMode = config.rspMode.storage,
                pakMode = pak,
                expansionPak = config.expansionPak.storage,
                framebufferEmulation = config.framebufferEmulation,
                threadedRenderer = config.threadedRenderer,
                internalResolution = config.internalResolution.multiplier,
                analogDeadzonePercent = (input.analogDeadzone * 100f).roundToInt(),
                analogSensitivityPercent = (input.analogSensitivity * 100f).roundToInt(),
                audioBufferBursts = decision.audioBufferBursts.coerceIn(2, 7)
            )
        }.getOrDefault(false)
    }

    fun stop() {
        if (runtimeLoaded) runCatching { nativeStop() }
    }

    fun setPaused(paused: Boolean) {
        if (runtimeLoaded) runCatching { nativeSetPaused(paused) }
    }

    fun isRunning(): Boolean = runtimeLoaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun lastMessage(): String = if (!runtimeLoaded) {
        "N64 Runtime indisponível"
    } else {
        runCatching { nativeLastMessage() }.getOrDefault("N64 Runtime")
    }

    fun telemetry(): Telemetry {
        if (!runtimeLoaded) return Telemetry()
        val raw = runCatching { nativeTelemetry() }.getOrNull() ?: return Telemetry()
        return Telemetry(
            averageFrameMs = raw.getOrElse(0) { 0f },
            p95FrameMs = raw.getOrElse(1) { 0f },
            droppedFrames = raw.getOrElse(2) { 0f }.roundToInt(),
            audioUnderruns = raw.getOrElse(3) { 0f }.roundToInt(),
            sampleWindowFrames = raw.getOrElse(4) { 0f }.roundToInt()
        )
    }

    fun setButton(retroPadId: Int, pressed: Boolean) {
        if (runtimeLoaded) runCatching { nativeSetButton(retroPadId, pressed) }
    }

    fun setAnalog(x: Float, y: Float, cX: Float = 0f, cY: Float = 0f) {
        if (runtimeLoaded) runCatching { nativeSetAnalog(x, y, cX, cY) }
    }

    private external fun nativeHasCore(): Boolean
    private external fun nativeRuntimeInfo(): String
    private external fun nativeStart(
        surface: Surface,
        romPath: String,
        systemDir: String,
        saveDir: String,
        diagnosticPath: String,
        verificationPath: String,
        cpuMode: String,
        rspMode: String,
        pakMode: String,
        expansionPak: String,
        framebufferEmulation: Boolean,
        threadedRenderer: Boolean,
        internalResolution: Int,
        analogDeadzonePercent: Int,
        analogSensitivityPercent: Int,
        audioBufferBursts: Int
    ): Boolean
    private external fun nativeStop()
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLastMessage(): String
    private external fun nativeTelemetry(): FloatArray
    private external fun nativeSetButton(retroPadId: Int, pressed: Boolean)
    private external fun nativeSetAnalog(x: Float, y: Float, cX: Float, cY: Float)
}
