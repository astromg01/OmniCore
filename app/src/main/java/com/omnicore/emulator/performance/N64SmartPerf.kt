package com.omnicore.emulator.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.omnicore.emulator.settings.N64PerformanceProfile
import com.omnicore.emulator.settings.N64Settings
import kotlin.math.max

/** Nintendo 64 specific adaptive performance policy. */
object N64SmartPerf {
    enum class Level { ECO, BALANCED, TURBO }

    data class Telemetry(
        val averageFrameMs: Float = 0f,
        val p95FrameMs: Float = 0f,
        val droppedFrames: Int = 0,
        val audioUnderruns: Int = 0,
        val sampleWindowFrames: Int = 0,
        val audioFillMs: Float = 0f,
        val audioBufferMs: Float = 0f,
        val targetFps: Float = 0f,
        val pacingCorrectionPct: Float = 0f,
        val presentAverageMs: Float = 0f,
        val presentP95Ms: Float = 0f,
        val smartPrecompileReady: Boolean = false
    ) {
        val hasUsefulWindow: Boolean get() = sampleWindowFrames >= 90
        val targetFrameMs: Float
            get() = if (targetFps in 40f..75f) 1000f / targetFps else 1000f / 60f
        val audioCritical: Boolean
            get() = audioBufferMs > 0f && audioFillMs in 0f..max(8f, audioBufferMs * 0.35f)
        val gpuBound: Boolean
            get() = hasUsefulWindow && presentP95Ms >= 4.8f &&
                p95FrameMs > 0f && presentP95Ms >= p95FrameMs * 0.24f
    }

    data class Decision(
        val level: Level,
        val effective: N64Settings.Config,
        val audioBufferBursts: Int,
        val preferPowerEfficiency: Boolean,
        val aggressiveFramePacing: Boolean,
        val allowResolutionPromotion: Boolean,
        val leanGraphics: Boolean = false,
        val reason: String
    )

    private data class RuntimeSignals(
        val thermalStatus: Int,
        val memoryPressure: Boolean,
        val powerSave: Boolean
    )

    /**
     * Per-emulation-session controller. It converts cumulative native counters
     * into recent-window pressure and prevents rapid ECO/BALANCED/TURBO flapping.
     */
    class Session(context: Context, private val requested: N64Settings.Config) {
        private val appContext = context.applicationContext
        private val profile = N64PerformanceProfile.detect(appContext)
        private var current = resolve(profile, requested, runtimeSignals(appContext), Telemetry())
        private var lastUnderruns = 0
        private var pressureStreak = 0
        private var healthyStreak = 0
        private var lastTransitionAt = 0L
        private var lastAudioStressAt = 0L
        private val warmupStartedAt = SystemClock.elapsedRealtime()
        private var warmupMinUntil = warmupStartedAt + 12_000L
        private val warmupMaxUntil = warmupStartedAt + 45_000L
        private var warmupStableWindows = 0
        private var warmupActive = true

        fun initial(): Decision = current.copy(
            audioBufferBursts = max(current.audioBufferBursts, 7),
            preferPowerEfficiency = false,
            aggressiveFramePacing = false,
            leanGraphics = false,
            reason = "WarmStart N64 protegendo a fase de compilação e áudio inicial"
        )

        fun adapt(raw: Telemetry): Decision {
            val recentUnderruns = (raw.audioUnderruns - lastUnderruns).coerceAtLeast(0)
            lastUnderruns = raw.audioUnderruns
            val telemetry = raw.copy(audioUnderruns = recentUnderruns)
            val signals = runtimeSignals(appContext)
            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            if (telemetry.smartPrecompileReady) {
                warmupMinUntil = minOf(warmupMinUntil, warmupStartedAt + 7_000L)
            }
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now

            val stableWarmupWindow = telemetry.hasUsefulWindow &&
                telemetry.p95FrameMs <= telemetry.targetFrameMs * 1.12f &&
                telemetry.droppedFrames <= 2 &&
                recentUnderruns == 0 &&
                !telemetry.audioCritical
            if (warmupActive) {
                if (stableWarmupWindow && now >= warmupMinUntil) {
                    warmupStableWindows++
                } else if (!stableWarmupWindow) {
                    warmupStableWindows = 0
                }
                if (warmupStableWindows >= 3 || now >= warmupMaxUntil) warmupActive = false
            }

            fun protectAudio(decision: Decision): Decision = if (
                warmupActive || now - lastAudioStressAt < 12_000L
            ) {
                decision.copy(
                    audioBufferBursts = max(decision.audioBufferBursts, if (warmupActive) 7 else 6),
                    reason = if (recentUnderruns > 0) "SmartPerf N64 recuperando áudio sem oscilar buffer" else decision.reason
                )
            } else decision

            fun protectWarmup(decision: Decision): Decision {
                if (!warmupActive || signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || signals.memoryPressure) {
                    return decision
                }
                return decision.copy(
                    effective = decision.effective.copy(
                        internalResolution = requested.internalResolution,
                        framebufferEmulation = requested.framebufferEmulation || requested.aspectRatio.wide,
                        threadedRenderer = requested.threadedRenderer
                    ),
                    audioBufferBursts = max(decision.audioBufferBursts, 7),
                    preferPowerEfficiency = false,
                    aggressiveFramePacing = false,
                    leanGraphics = false,
                    reason = "WarmStart N64 mantendo qualidade enquanto a sessão estabiliza"
                )
            }

            if (warmupActive &&
                signals.thermalStatus < PowerManager.THERMAL_STATUS_SEVERE &&
                !signals.memoryPressure
            ) {
                pressureStreak = 0
                healthyStreak = 0
                current = current.copy(
                    audioBufferBursts = max(current.audioBufferBursts, candidate.audioBufferBursts),
                    reason = "WarmStart N64 absorvendo picos de primeira execução"
                )
                return protectWarmup(protectAudio(current))
            }

            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                recentUnderruns >= 3 || telemetry.audioCritical

            if (emergency) {
                pressureStreak = 0
                healthyStreak = 0
                current = candidate
                lastTransitionAt = now
                return protectWarmup(protectAudio(current))
            }

            when {
                candidate.level.ordinal < current.level.ordinal -> {
                    pressureStreak++
                    healthyStreak = 0
                    if (pressureStreak >= 2 && now - lastTransitionAt >= 3500L) {
                        current = candidate
                        lastTransitionAt = now
                        pressureStreak = 0
                    } else {
                        // Audio buffering is safe to react immediately even while
                        // heavier CPU/RDP changes wait for hysteresis.
                        current = current.copy(
                            audioBufferBursts = max(current.audioBufferBursts, candidate.audioBufferBursts),
                            reason = candidate.reason
                        )
                    }
                }
                candidate.level.ordinal > current.level.ordinal -> {
                    healthyStreak++
                    pressureStreak = 0
                    if (healthyStreak >= 4 && now - lastTransitionAt >= 8000L) {
                        current = candidate
                        lastTransitionAt = now
                        healthyStreak = 0
                    } else {
                        current = current.copy(
                            audioBufferBursts = candidate.audioBufferBursts.coerceAtLeast(3),
                            reason = "SmartPerf N64 aguardando margem sustentável"
                        )
                    }
                }
                else -> {
                    pressureStreak = 0
                    healthyStreak = 0
                    current = candidate
                }
            }
            return protectWarmup(protectAudio(current))
        }
    }

    fun initial(context: Context, requested: N64Settings.Config): Decision =
        resolve(N64PerformanceProfile.detect(context), requested, runtimeSignals(context), Telemetry())

    fun adapt(context: Context, requested: N64Settings.Config, telemetry: Telemetry): Decision =
        resolve(N64PerformanceProfile.detect(context), requested, runtimeSignals(context), telemetry)

    internal fun resolve(
        profile: N64PerformanceProfile.Profile,
        requested: N64Settings.Config,
        thermalStatus: Int,
        telemetry: Telemetry
    ): Decision = resolve(
        profile,
        requested,
        RuntimeSignals(thermalStatus, memoryPressure = false, powerSave = false),
        telemetry
    )

    private fun resolve(
        profile: N64PerformanceProfile.Profile,
        requested: N64Settings.Config,
        signals: RuntimeSignals,
        telemetry: Telemetry
    ): Decision {
        val severeThermal = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val warmThermal = signals.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val target = telemetry.targetFrameMs
        val framePressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= target * 1.22f ||
                telemetry.droppedFrames >= 5 ||
                telemetry.audioUnderruns >= 1 ||
                (telemetry.audioBufferMs > 0f && telemetry.audioFillMs < telemetry.audioBufferMs * 0.65f)
            )
        val heavyPressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= target * 1.55f ||
                telemetry.droppedFrames >= 12 ||
                telemetry.audioUnderruns >= 3 ||
                telemetry.audioCritical
            )
        val canThread = profile.is64Bit && profile.cpuCores >= 6
        val protectFramebuffer = requested.preset != N64Settings.Preset.PERFORMANCE || requested.aspectRatio.wide

        fun compatibleFramebuffer(underGpuPressure: Boolean): Boolean = when {
            protectFramebuffer -> true
            requested.aspectRatio.wide -> true
            underGpuPressure -> false
            else -> requested.framebufferEmulation
        }

        fun safe(config: N64Settings.Config, threaded: Boolean): N64Settings.Config =
            config.copy(
                cpuMode = N64Settings.CpuMode.DYNAREC,
                threadedRenderer = threaded && canThread,
                rspMode = N64Settings.RspMode.HLE
            )

        if (severeThermal || heavyPressure) {
            return Decision(
                level = Level.ECO,
                effective = safe(
                    requested.copy(
                        internalResolution = when {
                            severeThermal -> N64Settings.InternalResolution.NATIVE
                            telemetry.gpuBound || signals.memoryPressure ->
                                if (requested.internalResolution == N64Settings.InternalResolution.X2) {
                                    N64Settings.InternalResolution.X15
                                } else {
                                    requested.internalResolution
                                }
                            else -> requested.internalResolution
                        },
                        framebufferEmulation = compatibleFramebuffer(telemetry.gpuBound || signals.memoryPressure)
                    ),
                    threaded = !severeThermal
                ),
                audioBufferBursts = if (telemetry.audioCritical || telemetry.audioUnderruns >= 2) 7 else 6,
                preferPowerEfficiency = severeThermal,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                leanGraphics = telemetry.gpuBound || signals.memoryPressure || severeThermal,
                reason = when {
                    severeThermal -> "SmartPerf N64 reduziu carga por temperatura"
                    telemetry.audioCritical -> "SmartPerf N64 recuperando buffer de áudio"
                    else -> "SmartPerf N64 atacando gargalo forte de frame time"
                }
            )
        }

        if (
            warmThermal || framePressure || signals.memoryPressure || signals.powerSave ||
            profile.tier == N64PerformanceProfile.Tier.LOW
        ) {
            return Decision(
                level = Level.BALANCED,
                effective = safe(
                    requested.copy(
                        internalResolution = if (telemetry.gpuBound || signals.memoryPressure) {
                            if (requested.internalResolution == N64Settings.InternalResolution.X2) {
                                N64Settings.InternalResolution.X15
                            } else {
                                requested.internalResolution
                            }
                        } else {
                            requested.internalResolution
                        },
                        framebufferEmulation = compatibleFramebuffer(
                            telemetry.gpuBound || signals.memoryPressure
                        )
                    ),
                    threaded = canThread && !warmThermal
                ),
                audioBufferBursts = if (telemetry.audioUnderruns > 0) 6 else 5,
                preferPowerEfficiency = warmThermal || signals.powerSave,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                leanGraphics = telemetry.gpuBound || signals.memoryPressure || warmThermal,
                reason = when {
                    warmThermal -> "SmartPerf N64 preservando desempenho sustentável"
                    signals.memoryPressure -> "SmartPerf N64 reduzindo pressão de memória/GPU"
                    signals.powerSave -> "SmartPerf N64 compensando economia de energia ativa"
                    telemetry.gpuBound -> "SmartPerf N64 reduziu custo gráfico medido"
                    framePressure -> "SmartPerf N64 preservou resolução; BurstShield ataca picos CPU/GPU"
                    else -> "SmartPerf N64 otimizado para hardware limitado"
                }
            )
        }

        val highMargin = telemetry.hasUsefulWindow &&
            telemetry.p95FrameMs <= target * 1.06f &&
            telemetry.droppedFrames <= 1 &&
            telemetry.audioUnderruns == 0 &&
            !telemetry.audioCritical

        return Decision(
            level = if (highMargin) Level.TURBO else Level.BALANCED,
            effective = safe(requested, requested.threadedRenderer || canThread),
            audioBufferBursts = if (highMargin) 3 else 4,
            preferPowerEfficiency = false,
            aggressiveFramePacing = highMargin,
            allowResolutionPromotion = highMargin && requested.preset == N64Settings.Preset.AUTO,
            reason = if (highMargin) {
                "SmartPerf N64 detectou margem sustentada para baixa latência"
            } else {
                "SmartPerf N64 em equilíbrio automático"
            }
        )
    }

    private fun runtimeSignals(context: Context): RuntimeSignals {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val memoryPressure = memory.lowMemory ||
            (memory.threshold > 0L && memory.availMem in 1L..(memory.threshold * 3L / 2L))
        val power = context.getSystemService(PowerManager::class.java)
        return RuntimeSignals(
            thermalStatus = currentThermalStatus(context),
            memoryPressure = memoryPressure,
            powerSave = power?.isPowerSaveMode == true
        )
    }

    fun currentThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE
        return runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
    }
}
