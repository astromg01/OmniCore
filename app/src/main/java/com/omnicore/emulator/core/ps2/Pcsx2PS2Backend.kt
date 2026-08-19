package com.omnicore.emulator.core.ps2

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Surface
import com.omnicore.emulator.settings.PS2BiosManager
import com.omnicore.emulator.settings.PS2Settings
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Pcsx2PS2Backend(context: Context) : PS2Backend {
    private val appContext = context.applicationContext
    private val dataRoot = File(appContext.filesDir, "ps2/pcsx2")
    private val biosDir = File(dataRoot, "bios")
    private val resourceMarker = File(dataRoot, ".resources-$ARMSX2_PIN_SHORT")

    @Volatile private var initialized = false
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var activeRenderer = PS2Backend.Renderer.AUTO
    @Volatile private var vmThread: Thread? = null
    @Volatile private var governorThread: Thread? = null
    @Volatile private var governorStop = false
    @Volatile private var adaptiveLevel = 0
    private val telemetrySamples = AtomicInteger(0)

    override val id: String = "pcsx2-armsx2-$ARMSX2_PIN_SHORT"

    override fun probe(): PS2Backend.Capabilities {
        val arm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val binary = arm64 && !NativeApp.hasNoNativeBinary
        val vulkan = Build.VERSION.SDK_INT >= 24 &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val am = appContext.getSystemService(ActivityManager::class.java)
        val gles3 = (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x00030000
        val bios = PS2BiosManager.read(appContext)
        return PS2Backend.Capabilities(
            available = binary,
            arm64Jit = binary,
            vulkan = binary && vulkan,
            gles3 = binary && gles3,
            hleBios = false,
            externalBios = bios?.plausible == true,
            saveStates = binary,
            backendVersion = "PCSX2/ARMSX2 $ARMSX2_PIN_SHORT",
            notes = when {
                !arm64 -> "PCSX2 Android backend requires arm64-v8a."
                NativeApp.hasNoNativeBinary -> "${NativeApp.nativeLoadDiagnostic()} ABI=${Build.SUPPORTED_ABIS.joinToString("/")}" 
                bios?.plausible != true -> "Select a valid user-owned PS2 BIOS before booting."
                else -> "Pinned PCSX2 ARM64 emucore ready with real BIOS boot."
            }
        )
    }

    @Synchronized
    override fun attachSurface(surface: Surface?) {
        attachedSurface = surface
        if (!initialized) return
        if (surface == null || !surface.isValid) {
            runCatching { NativeApp.onNativeSurfaceDestroyed() }
        } else publishSurface(surface)
    }

    @Synchronized
    override fun boot(request: PS2Backend.BootRequest): PS2Backend.BootResult {
        if (request.imagePath.isBlank()) return PS2Backend.BootResult.Rejected("PS2 image URI/path is empty.")
        if (running || runCatching { NativeApp.hasActiveVM() }.getOrDefault(false)) {
            return PS2Backend.BootResult.Rejected("A PCSX2 VM is already active.")
        }
        val caps = probe()
        if (!caps.available) return PS2Backend.BootResult.Rejected(caps.notes)
        val bios = PS2BiosManager.read(appContext)
            ?: return PS2Backend.BootResult.Rejected("Selecione uma BIOS real de PS2 nas configurações.")
        if (!bios.plausible) return PS2Backend.BootResult.Rejected("BIOS PS2 inválida: ${bios.reason}")
        val surface = attachedSurface
        if (surface == null || !surface.isValid) return PS2Backend.BootResult.Rejected("PS2 render surface is not ready.")

        return runCatching {
            prepareFilesystem(bios)
            initializeRuntime()
            applyPreBootConfig(request.config)
            publishSurface(surface)
            telemetrySamples.set(0)
            governorStop = true
            governorThread?.interrupt()
            governorThread = null
            adaptiveLevel = 0
            paused = false

            val thread = Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                try {
                    NativeApp.runVMThread(request.imagePath)
                } finally {
                    governorStop = true
                    running = false
                    paused = false
                }
            }, "OmniCore-PCSX2-VM").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
            vmThread = thread

            val deadline = System.nanoTime() + BOOT_WAIT_MS * 1_000_000L
            var active = false
            while (System.nanoTime() < deadline && thread.isAlive) {
                if (NativeApp.hasActiveVM()) { active = true; break }
                Thread.sleep(80L)
            }
            if (!active) {
                runCatching { NativeApp.shutdown() }
                return@runCatching PS2Backend.BootResult.Failed(
                    "PCSX2 VM did not become active within the boot window.", true
                )
            }
            running = true
            applyPostBootConfig(request.config)
            startAdaptiveGovernor()
            PS2Backend.BootResult.Started(id, activeRenderer)
        }.getOrElse { error ->
            governorStop = true
            running = false
            paused = false
            PS2Backend.BootResult.Failed(
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim(), true
            )
        }
    }

    @Synchronized override fun pause() {
        if (!running) return
        paused = true
        releaseAllInput()
        runCatching { NativeApp.pause() }
    }

    @Synchronized override fun resume() {
        if (!running) return
        runCatching { NativeApp.resume() }
        paused = false
    }

    @Synchronized override fun stop() {
        governorStop = true
        governorThread?.interrupt()
        governorThread = null
        releaseAllInput()
        if (initialized) runCatching { NativeApp.shutdown() }
        running = false
        paused = false
        vmThread = null
        attachedSurface = null
        adaptiveLevel = 0
    }

    override fun telemetry(): PS2Backend.Telemetry {
        val fps = if (running) runCatching { NativeApp.getFPS() }.getOrDefault(-1f) else -1f
        val am = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val memoryPressure = if (memory.totalMem > 0L) {
            (1f - memory.availMem.toFloat() / memory.totalMem.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        val samples = if (running) telemetrySamples.incrementAndGet() else 0
        return PS2Backend.Telemetry(
            hostFrameMs = if (fps > 0f) 1000f / fps else -1f,
            measuredFps = fps,
            thermalStatus = thermal,
            memoryPressure = memoryPressure,
            renderer = if (running) activeRenderer else PS2Backend.Renderer.AUTO,
            sampleFrames = if (samples > 0) (samples * 120).coerceAtMost(Int.MAX_VALUE) else 0
        )
    }

    @Synchronized override fun setFrameLimit(enabled: Boolean): Boolean {
        if (!initialized || !running) return false
        return runCatching { NativeApp.speedhackLimitermode(if (enabled) 0 else 3); true }.getOrDefault(false)
    }

    @Synchronized override fun setButton(controlId: Int, pressed: Boolean) {
        if (!initialized || !running) return
        pcsx2DigitalKey(controlId)?.let { runCatching { NativeApp.setPadButton(it, 0, pressed) } }
    }

    @Synchronized override fun setAxis(controlId: Int, value: Float) {
        if (!initialized || !running) return
        val v = value.coerceIn(-1f, 1f)
        when (controlId) {
            AXIS_LEFT_X -> sendAnalogPair(PCSX2_L_LEFT, PCSX2_L_RIGHT, v)
            AXIS_LEFT_Y -> sendAnalogPair(PCSX2_L_UP, PCSX2_L_DOWN, v)
            AXIS_RIGHT_X -> sendAnalogPair(PCSX2_R_LEFT, PCSX2_R_RIGHT, v)
            AXIS_RIGHT_Y -> sendAnalogPair(PCSX2_R_UP, PCSX2_R_DOWN, v)
        }
    }

    @Synchronized override fun releaseAllInput() {
        if (!initialized) return
        for (id in BUTTON_UP..BUTTON_R3) {
            pcsx2DigitalKey(id)?.let { runCatching { NativeApp.setPadButton(it, 0, false) } }
        }
        sendAnalogPair(PCSX2_L_LEFT, PCSX2_L_RIGHT, 0f)
        sendAnalogPair(PCSX2_L_UP, PCSX2_L_DOWN, 0f)
        sendAnalogPair(PCSX2_R_LEFT, PCSX2_R_RIGHT, 0f)
        sendAnalogPair(PCSX2_R_UP, PCSX2_R_DOWN, 0f)
        runCatching { NativeApp.resetKeyStatus() }
    }

    override fun saveState(slot: Int): Boolean =
        running && slot in 0..9 && runCatching { NativeApp.saveStateToSlot(slot) }.getOrDefault(false)

    override fun loadState(slot: Int): Boolean {
        if (!running || slot !in 0..9) return false
        releaseAllInput()
        return runCatching { NativeApp.loadStateFromSlot(slot) }.getOrDefault(false)
    }

    private fun initializeRuntime() {
        if (initialized) return
        NativeApp.bindContext(appContext)
        NativeApp.initialize(dataRoot.absolutePath, biosDir.absolutePath, Build.VERSION.SDK_INT)
        NativeApp.setAffinityMode(0)
        val power = appContext.getSystemService(PowerManager::class.java)
        NativeApp.setAdpfEnabled(Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true)
        initialized = true
    }

    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig) {
        activeRenderer = when (config.renderer) {
            PS2Backend.Renderer.VULKAN -> { NativeApp.renderVulkan(); PS2Backend.Renderer.VULKAN }
            PS2Backend.Renderer.GLES3 -> { NativeApp.renderOpenGL(); PS2Backend.Renderer.GLES3 }
            PS2Backend.Renderer.AUTO -> { NativeApp.renderAuto(); PS2Backend.Renderer.AUTO }
        }
        NativeApp.renderUpscalemultiplier(config.internalResolutionFactor.toFloat())
        runCatching { NativeApp.renderPreloading(1) }
        val classic = PS2Settings.resolve(appContext).bootStyle == PS2Settings.BootStyle.CLASSIC
        NativeApp.setSetting("EmuCore", "EnableFastBoot", "bool", (!classic).toString())
        NativeApp.setSetting("EmuCore", "EnableFastBootFastForward", "bool", (!classic).toString())
        NativeApp.setSetting("EmuCore", "EnableWideScreenPatches", "bool", config.widescreen.toString())
        NativeApp.setSetting("EmuCore/GS", "AspectRatio", "string",
            if (config.widescreen) "16:9" else "Auto 4:3/3:2")
        NativeApp.setSetting("EmuCore/GS", "FrameLimitEnable", "bool", config.limitFrameRate.toString())
        val queueAhead = if (Runtime.getRuntime().availableProcessors() >= 6) max(2, config.queueAheadFrames)
            else config.queueAheadFrames
        NativeApp.setSetting("EmuCore/GS", "VsyncQueueSize", "int", queueAhead.coerceIn(1, 3).toString())
        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableFastmem", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool",
            (Runtime.getRuntime().availableProcessors() >= 6).toString())
        NativeApp.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", "0")
        NativeApp.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", "0")
        val audioLatency = config.audioTargetMs.coerceIn(48, 120)
        NativeApp.setSetting("SPU2/Output", "Backend", "string", "Oboe")
        NativeApp.setSetting("SPU2/Output", "Latency", "int", audioLatency.toString())
        NativeApp.setSetting("SPU2/Output", "OutputLatency", "int", "24")
        NativeApp.setSetting("SPU2/Output", "OutputLatencyMinimal", "bool", "false")
        NativeApp.setSetting("SPU2/Output", "SynchMode", "int", "0")
        NativeApp.commitSettings()
    }

    private fun applyPostBootConfig(config: PS2Backend.RuntimeConfig) {
        runCatching { NativeApp.speedhackLimitermode(if (config.limitFrameRate) 0 else 3) }
        runCatching { NativeApp.speedhackEecycleskip(0) }
        runCatching { NativeApp.setInstantVU1(true) }
    }

    private fun startAdaptiveGovernor() {
        governorStop = false
        governorThread?.interrupt()
        governorThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            var bestFps = 0f
            var emaRatio = 1f
            var lastFps = -1f
            var lowStreak = 0
            var severeStreak = 0
            var stableStreak = 0
            var samples = 0
            while (!governorStop && running) {
                try { Thread.sleep(GOVERNOR_SAMPLE_MS) } catch (_: InterruptedException) {
                    if (governorStop || !running) break
                }
                if (governorStop || !running || paused) continue
                val fps = runCatching { NativeApp.getFPS() }.getOrDefault(-1f)
                if (fps <= 1f) continue
                val nominal = runCatching { NativeApp.getNominalFrameRate() }.getOrDefault(0f)
                bestFps = max(bestFps * 0.999f, fps)
                samples++
                val target = when {
                    nominal >= 45f && bestFps >= 45f -> min(nominal, max(50f, bestFps))
                    bestFps >= 10f -> bestFps
                    nominal > 1f -> nominal
                    else -> 60f
                }.coerceAtLeast(20f)
                val ratio = (fps / target).coerceIn(0f, 1.25f)
                emaRatio = if (samples <= 2) ratio else emaRatio * 0.72f + ratio * 0.28f
                val sudden = lastFps > 1f && fps < lastFps * 0.72f && ratio < 0.90f
                val severe = ratio < 0.68f || emaRatio < 0.72f
                val low = ratio < 0.88f || emaRatio < 0.86f
                val stable = ratio >= 0.965f && emaRatio >= 0.94f
                when {
                    severe -> { severeStreak++; lowStreak++; stableStreak = 0 }
                    low -> { lowStreak++; severeStreak = 0; stableStreak = 0 }
                    stable -> { stableStreak++; lowStreak = 0; severeStreak = 0 }
                    else -> { severeStreak = 0; lowStreak = max(0, lowStreak - 1); stableStreak = max(0, stableStreak - 1) }
                }
                var desired = adaptiveLevel
                if (samples >= 3) {
                    desired = when {
                        sudden && adaptiveLevel < 1 -> 1
                        severeStreak >= 2 -> min(2, adaptiveLevel + 1)
                        lowStreak >= 3 -> min(2, adaptiveLevel + 1)
                        stableStreak >= 8 -> max(0, adaptiveLevel - 1)
                        else -> adaptiveLevel
                    }
                }
                if (desired != adaptiveLevel) {
                    applyAdaptiveLevel(desired, fps, target, sudden)
                    adaptiveLevel = desired
                    if (desired > adaptiveLevel) { lowStreak = 0; severeStreak = 0 }
                    else stableStreak = 0
                }
                lastFps = fps
            }
        }, "OmniCore-PS2-Governor").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    private fun applyAdaptiveLevel(level: Int, fps: Float, target: Float, sudden: Boolean) {
        val rate = when (level.coerceIn(0, 2)) { 0 -> 0; 1 -> -1; else -> -2 }
        runCatching { NativeApp.speedhackEecyclerate(rate) }
        runCatching { NativeApp.speedhackEecycleskip(0) }
        runCatching { NativeApp.setInstantVU1(true) }
        Log.i("OmniCorePS2Perf", "level=$level ee=$rate fps=${String.format("%.1f", fps)}/${String.format("%.1f", target)} burst=$sudden")
    }

    private fun publishSurface(surface: Surface) {
        val metrics = appContext.resources.displayMetrics
        val dm = appContext.getSystemService(DisplayManager::class.java)
        val refreshRate = runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f }
            .getOrDefault(60f).coerceIn(30f, 240f)
        runCatching { NativeApp.onNativeSurfaceCreated() }
        NativeApp.setDisplayRefreshRate(refreshRate)
        NativeApp.onNativeSurfaceChanged(surface, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }

    private fun prepareFilesystem(bios: PS2BiosManager.BiosInfo) {
        dataRoot.mkdirs(); biosDir.mkdirs(); prepareResources(); prepareSelectedBios(bios)
    }

    private fun prepareResources() {
        if (resourceMarker.isFile) return
        val target = File(dataRoot, "resources")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs(); copyAssetTree("pcsx2/resources", target); resourceMarker.writeText(ARMSX2_PIN)
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input -> FileOutputStream(destination).use { input.copyTo(it, 128 * 1024) } }
            return
        }
        destination.mkdirs()
        for (name in children) copyAssetTree("$assetPath/$name", File(destination, name))
    }

    private fun prepareSelectedBios(bios: PS2BiosManager.BiosInfo) {
        val prefs = appContext.getSharedPreferences("pcsx2_bios_bridge", Context.MODE_PRIVATE)
        val target = File(biosDir, "omnicore-ps2-bios.bin")
        if (prefs.getString("uri", null) == bios.uri && target.isFile && target.length() == bios.sizeBytes) return
        biosDir.listFiles()?.forEach { it.delete() }
        val temp = File(biosDir, ".bios-copy.tmp")
        openBiosInput(bios.uri).use { input -> FileOutputStream(temp).use { input.copyTo(it, 128 * 1024) } }
        if (!temp.isFile || temp.length() < 4L * 1024L * 1024L) { temp.delete(); error("Selected PS2 BIOS could not be copied into PCSX2 private storage.") }
        if (!temp.renameTo(target)) {
            FileInputStream(temp).use { input -> FileOutputStream(target).use { input.copyTo(it, 128 * 1024) } }; temp.delete()
        }
        prefs.edit().putString("uri", bios.uri).apply()
    }

    private fun openBiosInput(uriString: String) = when (val uri = Uri.parse(uriString)) {
        else -> when (uri.scheme) {
            "content" -> appContext.contentResolver.openInputStream(uri) ?: error("Unable to open selected BIOS content URI.")
            "file" -> FileInputStream(File(requireNotNull(uri.path)))
            null -> FileInputStream(File(uriString))
            else -> error("Unsupported BIOS URI scheme: ${uri.scheme}")
        }
    }

    private fun pcsx2DigitalKey(id: Int): Int? = when (id) {
        BUTTON_UP -> 19; BUTTON_DOWN -> 20; BUTTON_LEFT -> 21; BUTTON_RIGHT -> 22
        BUTTON_SELECT -> 109; BUTTON_START -> 108; BUTTON_SQUARE -> 99; BUTTON_TRIANGLE -> 100
        BUTTON_CIRCLE -> 97; BUTTON_CROSS -> 96; BUTTON_L1 -> 102; BUTTON_L2 -> 104
        BUTTON_L3 -> 106; BUTTON_R1 -> 103; BUTTON_R2 -> 105; BUTTON_R3 -> 107
        else -> null
    }

    private fun sendAnalogPair(negativeKey: Int, positiveKey: Int, value: Float) {
        val magnitude = (abs(value).coerceIn(0f, 1f) * 32767f).toInt()
        when {
            value < -ANALOG_DEADZONE -> { NativeApp.setPadButton(negativeKey, magnitude, true); NativeApp.setPadButton(positiveKey, 0, false) }
            value > ANALOG_DEADZONE -> { NativeApp.setPadButton(negativeKey, 0, false); NativeApp.setPadButton(positiveKey, magnitude, true) }
            else -> { NativeApp.setPadButton(negativeKey, 0, false); NativeApp.setPadButton(positiveKey, 0, false) }
        }
    }

    companion object {
        const val ARMSX2_PIN = "7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
        const val ARMSX2_PIN_SHORT = "7f0ae7a6"
        private const val BOOT_WAIT_MS = 12_000L
        private const val GOVERNOR_SAMPLE_MS = 850L
        private const val ANALOG_DEADZONE = 0.001f
        private const val AXIS_LEFT_X = 0; private const val AXIS_LEFT_Y = 1
        private const val AXIS_RIGHT_X = 2; private const val AXIS_RIGHT_Y = 3
        private const val BUTTON_UP = 4; private const val BUTTON_DOWN = 5; private const val BUTTON_LEFT = 6; private const val BUTTON_RIGHT = 7
        private const val BUTTON_SELECT = 8; private const val BUTTON_START = 9; private const val BUTTON_SQUARE = 10; private const val BUTTON_TRIANGLE = 11
        private const val BUTTON_CIRCLE = 12; private const val BUTTON_CROSS = 13; private const val BUTTON_L1 = 14; private const val BUTTON_L2 = 15
        private const val BUTTON_L3 = 16; private const val BUTTON_R1 = 17; private const val BUTTON_R2 = 18; private const val BUTTON_R3 = 19
        private const val PCSX2_L_UP = 110; private const val PCSX2_L_RIGHT = 111; private const val PCSX2_L_DOWN = 112; private const val PCSX2_L_LEFT = 113
        private const val PCSX2_R_UP = 120; private const val PCSX2_R_RIGHT = 121; private const val PCSX2_R_DOWN = 122; private const val PCSX2_R_LEFT = 123
    }
}
