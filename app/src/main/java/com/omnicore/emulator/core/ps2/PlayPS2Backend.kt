package com.omnicore.emulator.core.ps2

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.Surface
import com.virtualapplications.play.InputManager
import com.virtualapplications.play.InputManagerConstants
import com.virtualapplications.play.NativeInterop
import com.virtualapplications.play.SettingsManager

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

    override val id: String = "play-pinned-04bde0df"

    override fun probe(): PS2Backend.Capabilities {
        val nativeProbe = runCatching { PS2NativeBridge.probe() }.getOrNull()
        val jniLoadable = runCatching {
            NativeInterop.isVirtualMachineCreated()
            true
        }.getOrDefault(false)
        val available = nativeProbe?.playBackend == true && nativeProbe.playBootApi && jniLoadable
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
                available -> "Play! VM/input/settings JNI ready; renderer selected per PS2 session."
                nativeProbe?.playBackend != true -> "libPlay.so is not packaged or could not be loaded."
                nativeProbe?.playBootApi != true -> "Required Play! boot symbols are missing."
                else -> "Play! JNI_OnLoad/ABI shim validation failed."
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
        runCatching { NativeInterop.resumeVirtualMachine() }
    }

    @Synchronized
    override fun stop() {
        releaseAllInput()
        if (initialized && running) runCatching { NativeInterop.pauseVirtualMachine() }
        running = false
        attachedSurface = null
    }

    override fun telemetry(): PS2Backend.Telemetry {
        val am = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val memoryPressure = when {
            memory.totalMem <= 0L -> 0f
            memory.lowMemory -> 1f
            else -> (1f - memory.availMem.toFloat() / memory.totalMem.toFloat()).coerceIn(0f, 1f)
        }
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
                    ?: PowerManager.THERMAL_STATUS_NONE
            }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        return PS2Backend.Telemetry(
            thermalStatus = thermal,
            memoryPressure = memoryPressure,
            renderer = if (running) activeRenderer else PS2Backend.Renderer.AUTO
        )
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
        SettingsManager.setPreferenceInteger(PREF_SPU_BLOCK_COUNT, config.spuBlockCount.coerceIn(32, 100))
        SettingsManager.save()
        return renderer
    }

    companion object {
        private const val PREF_VIDEO_GS_HANDLER = "video.gshandler"
        private const val PREF_OPENGL_RESOLUTION = "renderer.opengl.resfactor"
        private const val PREF_WIDESCREEN = "renderer.widescreen"
        private const val PREF_PRESENTATION_MODE = "renderer.presentationmode"
        private const val PREF_FORCE_BILINEAR = "renderer.opengl.forcebilineartextures"
        private const val PREF_LIMIT_FRAMERATE = "ps2.limitframerate"
        private const val PREF_SPU_BLOCK_COUNT = "audio.spublockcount"
    }
}
