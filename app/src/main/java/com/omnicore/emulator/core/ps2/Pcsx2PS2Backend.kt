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
    @Volatile private var latestPerf = PS2NativeBridge.Pcsx2PerfSample()
    @Volatile private var latestProfile = PERF_PROFILE_UNKNOWN
    @Volatile private var latestVisibility = PERF_VIS_UNKNOWN
    @Volatile private var latestPeakFrameMs = -1f
    @Volatile private var latestSpikeCount = 0
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
        if (surface == null || !surface.isValid) {
            return PS2Backend.BootResult.Rejected("PS2 render surface is not ready.")
        }

        return runCatching {
            prepareFilesystem(bios)
            initializeRuntime()
            applyPreBootConfig(request.config, request.imagePath)
            publishSurface(surface)
            telemetrySamples.set(0)
            governorStop = true
            governorThread?.interrupt()
            governorThread = null
            latestPerf = PS2NativeBridge.Pcsx2PerfSample()
            latestPeakFrameMs = -1f
            latestSpikeCount = 0
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
                if (NativeApp.hasActiveVM()) {
                    active = true
                    break
                }
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
            startPressureProfiler(request.imagePath)
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
        latestPerf = PS2NativeBridge.Pcsx2PerfSample()
    }

    override fun telemetry(): PS2Backend.Telemetry {
        val fps = if (running) runCatching { NativeApp.getFPS() }.getOrDefault(-1f) else -1f
        val perf = if (running) {
            runCatching { PS2NativeBridge.samplePcsx2Performance() }.getOrDefault(latestPerf)
        } else PS2NativeBridge.Pcsx2PerfSample()
        if (perf.available) latestPerf = perf

        val am = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val memoryPressure = if (memory.totalMem > 0L) {
            (1f - memory.availMem.toFloat() / memory.totalMem.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
                    ?: PowerManager.THERMAL_STATUS_NONE
            }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        val samples = if (running) telemetrySamples.incrementAndGet() else 0
        val hostFrame = when {
            perf.frameAvgMs > 0f -> perf.frameAvgMs
            fps > 0f -> 1000f / fps
            else -> -1f
        }
        return PS2Backend.Telemetry(
            hostFrameMs = hostFrame,
            measuredFps = fps,
            internalFps = perf.internalFps,
            emulationSpeedPercent = perf.speedPercent,
            eeMs = perf.eeMs,
            vuMs = perf.vuMs,
            gsMs = perf.gsMs,
            gsBackMs = perf.gsBackMs,
            presentMs = perf.gpuMs,
            frameAverageMs = perf.frameAvgMs,
            frameMaximumMs = perf.frameMaxMs,
            eeUsagePercent = perf.eeUsage,
            vuUsagePercent = perf.vuUsage,
            gsUsagePercent = perf.gsUsage,
            gsBackUsagePercent = perf.gsBackUsage,
            gpuUsagePercent = perf.gpuUsage,
            gpuVertexInvocations = perf.vsInvocations,
            gpuPixelInvocations = perf.psInvocations,
            bottleneck = ps2PerfProfileName(latestProfile),
            visibilityPressure = ps2VisibilityName(latestVisibility),
            peakFrameMs = latestPeakFrameMs,
            spikeCount = latestSpikeCount,
            thermalStatus = thermal,
            memoryPressure = memoryPressure,
            renderer = if (running) activeRenderer else PS2Backend.Renderer.AUTO,
            sampleFrames = if (samples > 0) (samples * 60).coerceAtMost(Int.MAX_VALUE) else 0
        )
    }

    @Synchronized override fun setFrameLimit(enabled: Boolean): Boolean {
        if (!initialized || !running) return false
        return runCatching {
            NativeApp.speedhackLimitermode(if (enabled) 0 else 3)
            true
        }.getOrDefault(false)
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
        // The pinned ARMSX2 Android path explicitly recommends letting EAS choose
        // the fastest core for the hottest EE/VU/GS thread. Keep affinity mode 0;
        // no per-title learning is allowed to hard-pin a core.
        NativeApp.setAffinityMode(0)
        val power = appContext.getSystemService(PowerManager::class.java)
        NativeApp.setAdpfEnabled(Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true)
        initialized = true
    }

    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig, imagePath: String) {
        activeRenderer = when (config.renderer) {
            PS2Backend.Renderer.VULKAN -> {
                NativeApp.renderVulkan()
                PS2Backend.Renderer.VULKAN
            }
            PS2Backend.Renderer.GLES3 -> {
                NativeApp.renderOpenGL()
                PS2Backend.Renderer.GLES3
            }
            PS2Backend.Renderer.AUTO -> {
                NativeApp.renderAuto()
                PS2Backend.Renderer.AUTO
            }
        }
        NativeApp.renderUpscalemultiplier(config.internalResolutionFactor.toFloat())
        // Full preload is the pinned PCSX2 default. Partial preload caused texture
        // residency work to spill into gameplay during the #13 device test.
        runCatching { NativeApp.renderPreloading(2) }

        val perfPrefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)
        val perfKey = perfProfileKey(imagePath)
        val learnedProfile = perfPrefs.getInt(perfKey, PERF_PROFILE_UNKNOWN)
        val visibilityClass = perfPrefs.getInt("${perfKey}_visibility", PERF_VIS_UNKNOWN)
        latestProfile = learnedProfile
        latestVisibility = visibilityClass
        latestPeakFrameMs = perfPrefs.getFloat("${perfKey}_peak_frame_ms", -1f)
        latestSpikeCount = perfPrefs.getInt("${perfKey}_spike_count", 0)

        val am = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val power = appContext.getSystemService(PowerManager::class.java)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val pipelineCapable = cores >= 8 &&
            memory.totalMem >= 3L * 1024L * 1024L * 1024L &&
            power?.isPowerSaveMode != true

        // #19 makes #18's diagnosis actionable on the NEXT boot. CPU/VU-bound
        // titles keep the GS back thread disabled so it cannot compete with the
        // critical emulation threads. Only a measured GS bottleneck earns the
        // extra pipelined GS thread. GPU-bound titles keep the CPU side lean and
        // rely on render-pass coalescing while visibility pressure is measured.
        val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS
        val queueAhead = when (learnedProfile) {
            PERF_PROFILE_EE, PERF_PROFILE_VU, PERF_PROFILE_COMPUTE -> 1
            PERF_PROFILE_GS, PERF_PROFILE_GPU, PERF_PROFILE_RENDER -> max(2, config.queueAheadFrames)
            else -> if (cores >= 6) max(2, config.queueAheadFrames) else config.queueAheadFrames
        }.coerceIn(1, 3)

        val classic = PS2Settings.resolve(appContext).bootStyle == PS2Settings.BootStyle.CLASSIC
        NativeApp.setSetting("EmuCore", "EnableFastBoot", "bool", (!classic).toString())
        NativeApp.setSetting("EmuCore", "EnableFastBootFastForward", "bool", (!classic).toString())
        NativeApp.setSetting("EmuCore", "EnableWideScreenPatches", "bool", config.widescreen.toString())
        NativeApp.setSetting(
            "EmuCore/GS",
            "AspectRatio",
            "string",
            if (config.widescreen) "16:9" else "Auto 4:3/3:2"
        )
        NativeApp.setSetting("EmuCore/GS", "FrameLimitEnable", "bool", config.limitFrameRate.toString())
        NativeApp.setSetting("EmuCore/GS", "VsyncQueueSize", "int", queueAhead.toString())
        NativeApp.setSetting("EmuCore/GS", "CoalesceRenderPasses", "bool", "true")
        NativeApp.setSetting("EmuCore/GS", "SkipDuplicateFrames", "bool", "true")
        NativeApp.setSetting("EmuCore/GS", "GSBackThreadMode", "int", if (useGsPipeline) "3" else "0")

        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableFastmem", "bool", "true")
        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableVUProgramCache", "bool", "true")
        NativeApp.setSetting("EmuCore", "EnableThreadPinning", "bool", "false")
        NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", "true")
        NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", (cores >= 6).toString())
        // Accuracy guardrail: SmartPerf never turns FPS into a fake number by
        // underclocking EE or skipping emulated cycles.
        NativeApp.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", "0")
        NativeApp.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", "0")

        val audioLatency = config.audioTargetMs.coerceIn(48, 120)
        NativeApp.setSetting("SPU2/Output", "Backend", "string", "Oboe")
        NativeApp.setSetting("SPU2/Output", "Latency", "int", audioLatency.toString())
        NativeApp.setSetting("SPU2/Output", "OutputLatency", "int", "24")
        NativeApp.setSetting("SPU2/Output", "OutputLatencyMinimal", "bool", "false")
        NativeApp.setSetting("SPU2/Output", "SynchMode", "int", "0")
        NativeApp.commitSettings()

        Log.i(
            "OmniCorePS2Perf",
            "A6#19 preboot profile=${ps2PerfProfileName(learnedProfile)} " +
                "visibility=${ps2VisibilityName(visibilityClass)} gsPipeline=$useGsPipeline " +
                "queue=$queueAhead mtvu=${cores >= 6} affinity=EAS cores=$cores"
        )
    }

    private fun applyPostBootConfig(config: PS2Backend.RuntimeConfig) {
        runCatching { NativeApp.speedhackLimitermode(if (config.limitFrameRate) 0 else 3) }
        // setInstantVU1 is a targeted live bit update in the pinned core and does
        // not call the full ApplySettings/JIT-reset path used by EE cycle writes.
        runCatching { NativeApp.setInstantVU1(true) }
    }

    private fun startPressureProfiler(imagePath: String) {
        governorStop = false
        governorThread?.interrupt()
        val profileKey = perfProfileKey(imagePath)
        governorThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val prefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)
            val power = appContext.getSystemService(PowerManager::class.java)
            var lastWallNs = System.nanoTime()
            var lastCpuMs = Process.getElapsedCpuTime()
            var eePressure = 0f
            var vuPressure = 0f
            var gsPressure = 0f
            var gpuPressure = 0f
            var samples = 0
            var learned = prefs.getInt(profileKey, PERF_PROFILE_UNKNOWN)
            var visibilityClass = prefs.getInt("${profileKey}_visibility", PERF_VIS_UNKNOWN)
            var peakFrame = prefs.getFloat("${profileKey}_peak_frame_ms", -1f)
            var spikeCount = prefs.getInt("${profileKey}_spike_count", 0)
            var adpfEnabled = Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true
            var nativeMetricSeen = false

            latestProfile = learned
            latestVisibility = visibilityClass
            latestPeakFrameMs = peakFrame
            latestSpikeCount = spikeCount

            while (!governorStop && running) {
                try {
                    Thread.sleep(PRESSURE_SAMPLE_MS)
                } catch (_: InterruptedException) {
                    if (governorStop || !running) break
                }
                if (governorStop || !running) break
                if (paused) {
                    lastWallNs = System.nanoTime()
                    lastCpuMs = Process.getElapsedCpuTime()
                    continue
                }

                val fps = runCatching { NativeApp.getFPS() }.getOrDefault(-1f)
                val nominal = runCatching { NativeApp.getNominalFrameRate() }.getOrDefault(0f)
                val nowWallNs = System.nanoTime()
                val nowCpuMs = Process.getElapsedCpuTime()
                val wallMs = ((nowWallNs - lastWallNs) / 1_000_000f).coerceAtLeast(1f)
                val cpuMs = (nowCpuMs - lastCpuMs).coerceAtLeast(0L).toFloat()
                lastWallNs = nowWallNs
                lastCpuMs = nowCpuMs
                if (fps <= 1f) continue

                val target = if (nominal > 20f) nominal else 60f
                val frameBudgetMs = (1000f / target).coerceAtLeast(1f)
                val ratio = (fps / target).coerceIn(0f, 1.15f)
                val cpuEquivalentCores = (cpuMs / wallMs).coerceIn(0f, cores.toFloat() + 0.5f)
                val processCoreLoad = (cpuEquivalentCores / cores.toFloat()).coerceIn(0f, 1.25f)
                val nativePerf = runCatching { PS2NativeBridge.samplePcsx2Performance() }
                    .getOrDefault(PS2NativeBridge.Pcsx2PerfSample())
                if (nativePerf.available) {
                    latestPerf = nativePerf
                    nativeMetricSeen = true
                }

                eePressure *= 0.86f
                vuPressure *= 0.86f
                gsPressure *= 0.86f
                gpuPressure *= 0.86f

                val validFrameStats = nativePerf.available &&
                    nativePerf.frameAvgMs > 0f && nativePerf.frameMaxMs > 0f
                val frameSpike = validFrameStats && nativePerf.frameMaxMs >
                    max(frameBudgetMs * 1.55f, nativePerf.frameAvgMs * 1.80f)
                if (frameSpike) {
                    spikeCount++
                    if (nativePerf.frameMaxMs > peakFrame) peakFrame = nativePerf.frameMaxMs
                    latestPeakFrameMs = peakFrame
                    latestSpikeCount = spikeCount
                }

                val speedRatio = if (nativePerf.speedPercent > 0f) {
                    (nativePerf.speedPercent / 100f).coerceIn(0f, 1.15f)
                } else ratio
                if (ratio < 0.94f || speedRatio < 0.94f || frameSpike) {
                    val fpsSeverity = ((0.94f - ratio) / 0.94f).coerceIn(0f, 1f)
                    val speedSeverity = ((0.94f - speedRatio) / 0.94f).coerceIn(0f, 1f)
                    val spikeBoost = if (frameSpike) 1.25f else 1f
                    val severity = max(0.10f, max(fpsSeverity, speedSeverity)) * spikeBoost

                    if (nativePerf.available) {
                        val eeScore = max(
                            nativePerf.eeUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.eeMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val vuScore = max(
                            nativePerf.vuUsage.coerceAtLeast(0f) / 100f,
                            nativePerf.vuMs.coerceAtLeast(0f) / frameBudgetMs
                        )
                        val gsCombinedMs = nativePerf.gsMs.coerceAtLeast(0f) +
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
                    } else {
                        // ABI-safe fallback retained from #17. It does not change
                        // any live VM/JIT setting; it only creates a next-boot hint.
                        if (cpuEquivalentCores >= 1.30f) {
                            val hotThreadWeight = (cpuEquivalentCores / 2.0f).coerceIn(0.65f, 1.75f)
                            eePressure += severity * (1f + hotThreadWeight)
                        } else {
                            gsPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                        }
                    }
                }
                samples++

                val thermal = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                        .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
                } else PowerManager.THERMAL_STATUS_NONE
                val shouldUseAdpf = Build.VERSION.SDK_INT >= 33 &&
                    power?.isPowerSaveMode != true && thermal < PowerManager.THERMAL_STATUS_SEVERE
                if (shouldUseAdpf != adpfEnabled) {
                    runCatching { NativeApp.setAdpfEnabled(shouldUseAdpf) }
                    adpfEnabled = shouldUseAdpf
                }

                if (samples % 6 == 0) {
                    val ranked = listOf(
                        PERF_PROFILE_EE to eePressure,
                        PERF_PROFILE_VU to vuPressure,
                        PERF_PROFILE_GS to gsPressure,
                        PERF_PROFILE_GPU to gpuPressure,
                    ).sortedByDescending { it.second }
                    val best = ranked[0]
                    val second = ranked[1]
                    val next = if (best.second >= 0.78f && best.second > second.second * 1.10f) {
                        best.first
                    } else PERF_PROFILE_UNKNOWN
                    if (next != PERF_PROFILE_UNKNOWN && next != learned) {
                        prefs.edit().putInt(profileKey, next).apply()
                        learned = next
                        latestProfile = next
                    }

                    // This is culling metadata, not entity deletion. A low
                    // pixel/vertex ratio points toward geometry/visibility work;
                    // a high ratio points toward fill/pixel pressure. #19 only
                    // records the distinction so later native culling can be
                    // enabled for a proven geometry-heavy title.
                    if (nativePerf.available && nativePerf.vsInvocations > 0.0 &&
                        nativePerf.psInvocations > 0.0 &&
                        (learned == PERF_PROFILE_GS || learned == PERF_PROFILE_GPU)) {
                        val pixelsPerVertex = nativePerf.psInvocations /
                            nativePerf.vsInvocations.coerceAtLeast(1.0)
                        val measuredClass = if (pixelsPerVertex < 6.0) {
                            PERF_VIS_GEOMETRY
                        } else PERF_VIS_FILL
                        if (measuredClass != visibilityClass) {
                            prefs.edit().putInt("${profileKey}_visibility", measuredClass).apply()
                            visibilityClass = measuredClass
                            latestVisibility = measuredClass
                        }
                    }

                    prefs.edit()
                        .putFloat("${profileKey}_peak_frame_ms", peakFrame)
                        .putInt("${profileKey}_spike_count", spikeCount)
                        .apply()
                    latestPeakFrameMs = peakFrame
                    latestSpikeCount = spikeCount

                    Log.i(
                        "OmniCorePS2Perf",
                        "A6#19 native=$nativeMetricSeen profile=${ps2PerfProfileName(learned)} " +
                            "vis=${ps2VisibilityName(visibilityClass)} " +
                            "fps=${String.format("%.1f", fps)}/${String.format("%.1f", target)} " +
                            "speed=${String.format("%.1f", nativePerf.speedPercent)}% " +
                            "internal=${String.format("%.1f", nativePerf.internalFps)} " +
                            "EE=${String.format("%.0f", nativePerf.eeUsage)}%/${String.format("%.2f", nativePerf.eeMs)}ms " +
                            "VU=${String.format("%.0f", nativePerf.vuUsage)}%/${String.format("%.2f", nativePerf.vuMs)}ms " +
                            "GS=${String.format("%.0f", nativePerf.gsUsage + nativePerf.gsBackUsage)}%/" +
                            "${String.format("%.2f", nativePerf.gsMs + nativePerf.gsBackMs)}ms " +
                            "GPU=${String.format("%.0f", nativePerf.gpuUsage)}%/${String.format("%.2f", nativePerf.gpuMs)}ms " +
                            "frame=${String.format("%.2f", nativePerf.frameAvgMs)}/" +
                            "${String.format("%.2f", nativePerf.frameMaxMs)}ms peak=" +
                            "${String.format("%.2f", peakFrame)} spikes=$spikeCount " +
                            "cpuCores=${String.format("%.2f", cpuEquivalentCores)} thermal=$thermal adpf=$adpfEnabled"
                    )
                }
            }
        }, "OmniCore-PS2-NativeBottleneckProfiler").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun perfProfileKey(imagePath: String): String =
        "game_${imagePath.hashCode().toUInt().toString(16)}"

    private fun publishSurface(surface: Surface) {
        val metrics = appContext.resources.displayMetrics
        val dm = appContext.getSystemService(DisplayManager::class.java)
        val refreshRate = runCatching {
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        }.getOrDefault(60f).coerceIn(30f, 240f)
        runCatching { NativeApp.onNativeSurfaceCreated() }
        NativeApp.setDisplayRefreshRate(refreshRate)
        NativeApp.onNativeSurfaceChanged(
            surface,
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
    }

    private fun prepareFilesystem(bios: PS2BiosManager.BiosInfo) {
        dataRoot.mkdirs()
        biosDir.mkdirs()
        prepareResources()
        prepareSelectedBios(bios)
    }

    private fun prepareResources() {
        if (resourceMarker.isFile) return
        val target = File(dataRoot, "resources")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyAssetTree("pcsx2/resources", target)
        resourceMarker.writeText(ARMSX2_PIN)
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { input.copyTo(it, 128 * 1024) }
            }
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
        openBiosInput(bios.uri).use { input ->
            FileOutputStream(temp).use { input.copyTo(it, 128 * 1024) }
        }
        if (!temp.isFile || temp.length() < 4L * 1024L * 1024L) {
            temp.delete()
            error("Selected PS2 BIOS could not be copied into PCSX2 private storage.")
        }
        if (!temp.renameTo(target)) {
            FileInputStream(temp).use { input ->
                FileOutputStream(target).use { input.copyTo(it, 128 * 1024) }
            }
            temp.delete()
        }
        prefs.edit().putString("uri", bios.uri).apply()
    }

    private fun openBiosInput(uriString: String) = when (val uri = Uri.parse(uriString)) {
        else -> when (uri.scheme) {
            "content" -> appContext.contentResolver.openInputStream(uri)
                ?: error("Unable to open selected BIOS content URI.")
            "file" -> FileInputStream(File(requireNotNull(uri.path)))
            null -> FileInputStream(File(uriString))
            else -> error("Unsupported BIOS URI scheme: ${uri.scheme}")
        }
    }

    private fun pcsx2DigitalKey(id: Int): Int? = when (id) {
        BUTTON_UP -> 19
        BUTTON_DOWN -> 20
        BUTTON_LEFT -> 21
        BUTTON_RIGHT -> 22
        BUTTON_SELECT -> 109
        BUTTON_START -> 108
        BUTTON_SQUARE -> 99
        BUTTON_TRIANGLE -> 100
        BUTTON_CIRCLE -> 97
        BUTTON_CROSS -> 96
        BUTTON_L1 -> 102
        BUTTON_L2 -> 104
        BUTTON_L3 -> 106
        BUTTON_R1 -> 103
        BUTTON_R2 -> 105
        BUTTON_R3 -> 107
        else -> null
    }

    private fun sendAnalogPair(negativeKey: Int, positiveKey: Int, value: Float) {
        val magnitude = (abs(value).coerceIn(0f, 1f) * 32767f).toInt()
        when {
            value < -ANALOG_DEADZONE -> {
                NativeApp.setPadButton(negativeKey, magnitude, true)
                NativeApp.setPadButton(positiveKey, 0, false)
            }
            value > ANALOG_DEADZONE -> {
                NativeApp.setPadButton(negativeKey, 0, false)
                NativeApp.setPadButton(positiveKey, magnitude, true)
            }
            else -> {
                NativeApp.setPadButton(negativeKey, 0, false)
                NativeApp.setPadButton(positiveKey, 0, false)
            }
        }
    }

    companion object {
        const val ARMSX2_PIN = "7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
        const val ARMSX2_PIN_SHORT = "7f0ae7a6"
        private const val BOOT_WAIT_MS = 12_000L
        private const val PRESSURE_SAMPLE_MS = 900L
        private const val ANALOG_DEADZONE = 0.001f
        private const val AXIS_LEFT_X = 0
        private const val AXIS_LEFT_Y = 1
        private const val AXIS_RIGHT_X = 2
        private const val AXIS_RIGHT_Y = 3
        private const val BUTTON_UP = 4
        private const val BUTTON_DOWN = 5
        private const val BUTTON_LEFT = 6
        private const val BUTTON_RIGHT = 7
        private const val BUTTON_SELECT = 8
        private const val BUTTON_START = 9
        private const val BUTTON_SQUARE = 10
        private const val BUTTON_TRIANGLE = 11
        private const val BUTTON_CIRCLE = 12
        private const val BUTTON_CROSS = 13
        private const val BUTTON_L1 = 14
        private const val BUTTON_L2 = 15
        private const val BUTTON_L3 = 16
        private const val BUTTON_R1 = 17
        private const val BUTTON_R2 = 18
        private const val BUTTON_R3 = 19
        private const val PCSX2_L_UP = 110
        private const val PCSX2_L_RIGHT = 111
        private const val PCSX2_L_DOWN = 112
        private const val PCSX2_L_LEFT = 113
        private const val PCSX2_R_UP = 120
        private const val PCSX2_R_RIGHT = 121
        private const val PCSX2_R_DOWN = 122
        private const val PCSX2_R_LEFT = 123
    }
}
