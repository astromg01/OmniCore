package com.omnicore.emulator.core.ps2

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import com.virtualapplications.play.InputManager
import com.virtualapplications.play.InputManagerConstants
import com.virtualapplications.play.NativeInterop
import com.virtualapplications.play.SettingsManager
import com.virtualapplications.play.StatsManager

/**
 * OmniCore adapter for the pinned Play! Android backend.
 *
 * This class exists only inside the isolated :ps2 process. Play!'s Android UI
 * never enters OmniCore; lifecycle, settings and input stay behind PS2Backend.
 */
class PlayPS2Backend(context: Context) : PS2Backend {
    private val appContext = context.applicationContext
    private val pressed = BooleanArray(20)

    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var initialized = false
    @Volatile private var running = false
    @Volatile private var activeRenderer = PS2Backend.Renderer.AUTO
    @Volatile private var systemMetricsSampledAtMs = 0L
    @Volatile private var cachedMemoryPressure = 0f
    @Volatile private var cachedThermalStatus = PowerManager.THERMAL_STATUS_NONE

    // Windowed telemetry is intentionally kept in the adapter. Alpha 5 used a
    // boot-to-now average which reacted too slowly for safe adaptive decisions.
    @Volatile private var lastTelemetryAtMs = 0L
    @Volatile private var lastTelemetryFrames = 0
    @Volatile private var lastTelemetryDrawCalls = 0
    @Volatile private var lastWindowFps = -1f
    @Volatile private var lastWindowDrawCallsPerFrame = -1f

    override val id: String = "play-pinned-04bde0df"

    override fun probe(): PS2Backend.Capabilities {
        val nativeProbe = runCatching { PS2NativeBridge.probe() }.getOrNull()
        val jniLoadable = runCatching {
            NativeInterop.isVirtualMachineCreated()
            true
        }.getOrDefault(false)
        val compatibilityDb = hasCompatibilityDatabase()
        val available = nativeProbe?.playBackend == true && nativeProbe.playBootApi && jniLoadable && compatibilityDb
        val vulkanDevice = if (Build.VERSION.SDK_INT >= 24) {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        } else false

        return PS2Backend.Capabilities(
            available = available,
            arm64Jit = available && nativeProbe?.architecture == "arm64-v8a" && nativeProbe?.pointerBits == 64,
            vulkan = available && vulkanDevice,
            gles3 = available && nativeProbe?.gles3Build == true,
            hleBios = available,
            externalBios = false,
            saveStates = available,
            backendVersion = nativeProbe?.playRevision?.take(12).orEmpty().ifBlank { "unavailable" },
            notes = when {
                nativeProbe?.playBackend != true -> "libPlay.so is not packaged or could not be loaded."
                nativeProbe?.playBootApi != true -> "Required Play! boot symbols are missing."
                !jniLoadable -> "Play! JNI_OnLoad/ABI shim validation failed."
                !compatibilityDb -> "Play! GameConfig.xml compatibility database is missing from APK assets."
                else -> "Play! VM/input/settings JNI + GameConfig compatibility database ready."
            }
        )
    }

    @Synchronized
    override fun attachSurface(surface: Surface?) {
        attachedSurface = surface
        if (surface != null && surface.isValid && initialized) {
            NativeInterop.setupGsHandler(surface)
        }
    }

    @Synchronized
    override fun boot(request: PS2Backend.BootRequest): PS2Backend.BootResult {
        if (request.imagePath.isBlank()) return PS2Backend.BootResult.Rejected("PS2 image URI/path is empty.")
        if (request.externalBiosPath != null) {
            return PS2Backend.BootResult.Rejected("External PS2 BIOS boot is not wired in this backend revision.")
        }

        val capabilities = probe()
        if (!capabilities.available) return PS2Backend.BootResult.Rejected(capabilities.notes)
        val surface = attachedSurface
        if (surface == null || !surface.isValid) return PS2Backend.BootResult.Rejected("PS2 render surface is not ready.")

        return runCatching {
            initializeRuntime()
            activeRenderer = applyRuntimeConfig(request.config, capabilities)
            NativeInterop.setupGsHandler(surface)
            NativeInterop.notifyPreferencesChanged()
            NativeInterop.bootDiskImage(request.imagePath)
            runCatching { StatsManager.clearStats() }
            resetTelemetryWindow()
            systemMetricsSampledAtMs = 0L
            NativeInterop.resumeVirtualMachine()
            running = true
            PS2Backend.BootResult.Started(id, activeRenderer)
        }.getOrElse { error ->
            running = false
            PS2Backend.BootResult.Failed(
                reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim(),
                recoverable = true
            )
        }
    }

    @Synchronized
    override fun pause() {
        if (!initialized || !running) return
        releaseAllInput()
        runCatching { NativeInterop.pauseVirtualMachine() }
    }

    @Synchronized
    override fun resume() {
        if (!initialized || !running) return
        resetTelemetryWindow()
        runCatching { NativeInterop.resumeVirtualMachine() }
    }

    @Synchronized
    override fun stop() {
        releaseAllInput()
        if (initialized && running) runCatching { NativeInterop.pauseVirtualMachine() }
        running = false
        systemMetricsSampledAtMs = 0L
        resetTelemetryWindow()
        attachedSurface = null
    }

    /**
     * Applies Play!'s real frame-limiter preference for this process only.
     * We deliberately do not call SettingsManager.save(): SmartPerf probes must
     * never alter the next boot or become another persistent auto-tuning trap.
     */
    @Synchronized
    override fun setFrameLimit(enabled: Boolean): Boolean {
        if (!initialized || !running) return false
        return runCatching {
            SettingsManager.setPreferenceBoolean(PREF_LIMIT_FRAMERATE, enabled)
            NativeInterop.notifyPreferencesChanged()
            resetTelemetryWindow()
            true
        }.getOrDefault(false)
    }

    override fun telemetry(): PS2Backend.Telemetry {
        val now = SystemClock.elapsedRealtime()
        refreshSystemMetrics(now)

        val frames = if (running) runCatching { StatsManager.getFrames() }.getOrDefault(0) else 0
        val drawCalls = if (running) runCatching { StatsManager.getDrawCalls() }.getOrDefault(0) else 0

        val previousAt = lastTelemetryAtMs
        val deltaMs = if (previousAt > 0L) (now - previousAt).coerceAtLeast(0L) else 0L
        if (running && deltaMs >= MIN_TELEMETRY_WINDOW_MS) {
            val deltaFrames = (frames - lastTelemetryFrames).coerceAtLeast(0)
            val deltaDrawCalls = (drawCalls - lastTelemetryDrawCalls).coerceAtLeast(0)
            lastWindowFps = deltaFrames * 1000f / deltaMs.toFloat()
            lastWindowDrawCallsPerFrame = if (deltaFrames > 0) {
                deltaDrawCalls.toFloat() / deltaFrames.toFloat()
            } else -1f
            lastTelemetryAtMs = now
            lastTelemetryFrames = frames
            lastTelemetryDrawCalls = drawCalls
        } else if (previousAt == 0L) {
            lastTelemetryAtMs = now
            lastTelemetryFrames = frames
            lastTelemetryDrawCalls = drawCalls
        }

        val fps = lastWindowFps
        val frameMs = if (fps > 0f) 1000f / fps else -1f

        return PS2Backend.Telemetry(
            hostFrameMs = frameMs,
            measuredFps = fps,
            drawCallsPerFrame = lastWindowDrawCallsPerFrame,
            thermalStatus = cachedThermalStatus,
            memoryPressure = cachedMemoryPressure,
            renderer = if (running) activeRenderer else PS2Backend.Renderer.AUTO,
            sampleFrames = frames
        )
    }

    private fun refreshSystemMetrics(nowMs: Long) {
        val previous = systemMetricsSampledAtMs
        if (previous > 0L && nowMs - previous < SYSTEM_METRICS_SAMPLE_MS) return

        val am = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        cachedMemoryPressure = when {
            memory.totalMem <= 0L -> cachedMemoryPressure
            memory.lowMemory -> 1f
            else -> (1f - memory.availMem.toFloat() / memory.totalMem.toFloat()).coerceIn(0f, 1f)
        }

        cachedThermalStatus = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
                    ?: PowerManager.THERMAL_STATUS_NONE
            }.getOrDefault(cachedThermalStatus)
        } else PowerManager.THERMAL_STATUS_NONE

        systemMetricsSampledAtMs = nowMs
    }

    private fun resetTelemetryWindow() {
        lastTelemetryAtMs = SystemClock.elapsedRealtime()
        lastTelemetryFrames = if (running) runCatching { StatsManager.getFrames() }.getOrDefault(0) else 0
        lastTelemetryDrawCalls = if (running) runCatching { StatsManager.getDrawCalls() }.getOrDefault(0) else 0
        lastWindowFps = -1f
        lastWindowDrawCallsPerFrame = -1f
    }

    @Synchronized
    override fun setButton(controlId: Int, pressedNow: Boolean) {
        if (!initialized || controlId !in 4..19) return
        if (pressed[controlId] == pressedNow) return
        pressed[controlId] = pressedNow
        runCatching { InputManager.setButtonState(controlId, pressedNow) }
    }

    @Synchronized
    override fun setAxis(controlId: Int, value: Float) {
        if (!initialized || controlId !in 0..3) return
        runCatching { InputManager.setAxisState(controlId, value.coerceIn(-1f, 1f)) }
    }

    @Synchronized
    override fun releaseAllInput() {
        if (!initialized) return
        for (id in 4..19) {
            if (pressed[id]) runCatching { InputManager.setButtonState(id, false) }
            pressed[id] = false
        }
        for (axis in InputManagerConstants.ANALOG_LEFT_X..InputManagerConstants.ANALOG_RIGHT_Y) {
            runCatching { InputManager.setAxisState(axis, 0f) }
        }
    }

    @Synchronized
    override fun saveState(slot: Int): Boolean {
        if (!initialized || !running || slot !in 0..9) return false
        return runCatching { NativeInterop.saveState(slot); true }.getOrDefault(false)
    }

    @Synchronized
    override fun loadState(slot: Int): Boolean {
        if (!initialized || !running || slot !in 0..9) return false
        releaseAllInput()
        return runCatching { NativeInterop.loadState(slot); true }.getOrDefault(false)
    }

    @Synchronized
    private fun initializeRuntime() {
        if (initialized && NativeInterop.isVirtualMachineCreated()) return
        NativeInterop.setFilesDirPath(appContext.filesDir.absolutePath)
        NativeInterop.setCacheDirPath(appContext.cacheDir.absolutePath)
        NativeInterop.setAssetManager(appContext.assets)
        NativeInterop.setContentResolver(appContext.contentResolver)
        if (!NativeInterop.isVirtualMachineCreated()) NativeInterop.createVirtualMachine()
        initialized = true
    }

    private fun hasCompatibilityDatabase(): Boolean = runCatching {
        appContext.assets.open(COMPATIBILITY_DB_ASSET).use { stream ->
            stream.read() >= 0
        }
    }.getOrDefault(false)

    private fun applyRuntimeConfig(
        config: PS2Backend.RuntimeConfig,
        caps: PS2Backend.Capabilities
    ): PS2Backend.Renderer {
        val renderer = when (config.renderer) {
            PS2Backend.Renderer.VULKAN -> if (caps.vulkan) PS2Backend.Renderer.VULKAN else PS2Backend.Renderer.GLES3
            PS2Backend.Renderer.GLES3 -> PS2Backend.Renderer.GLES3
            PS2Backend.Renderer.AUTO -> PS2Backend.Renderer.GLES3
        }
        SettingsManager.setPreferenceInteger(PREF_VIDEO_GS_HANDLER, if (renderer == PS2Backend.Renderer.VULKAN) 1 else 0)
        SettingsManager.setPreferenceInteger(PREF_OPENGL_RESOLUTION, config.internalResolutionFactor)
        SettingsManager.setPreferenceBoolean(PREF_WIDESCREEN, config.widescreen)
        SettingsManager.setPreferenceInteger(PREF_PRESENTATION_MODE, config.presentationMode)
        SettingsManager.setPreferenceBoolean(PREF_FORCE_BILINEAR, config.forceBilinear)
        SettingsManager.setPreferenceBoolean(PREF_LIMIT_FRAMERATE, config.limitFrameRate)
        SettingsManager.setPreferenceInteger(PREF_SPU_BLOCK_COUNT, config.spuBlockCount.coerceIn(10, 400))
        SettingsManager.save()
        return renderer
    }

    companion object {
        private const val COMPATIBILITY_DB_ASSET = "GameConfig.xml"
        private const val PREF_VIDEO_GS_HANDLER = "video.gshandler"
        private const val PREF_OPENGL_RESOLUTION = "renderer.opengl.resfactor"
        private const val PREF_WIDESCREEN = "renderer.widescreen"
        private const val PREF_PRESENTATION_MODE = "renderer.presentationmode"
        private const val PREF_FORCE_BILINEAR = "renderer.opengl.forcebilineartextures"
        private const val PREF_LIMIT_FRAMERATE = "ps2.limitframerate"
        private const val PREF_SPU_BLOCK_COUNT = "audio.spublockcount"
        private const val SYSTEM_METRICS_SAMPLE_MS = 7_500L
        private const val MIN_TELEMETRY_WINDOW_MS = 600L
    }
}
