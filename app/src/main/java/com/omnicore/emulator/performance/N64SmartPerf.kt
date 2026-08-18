package com.omnicore.emulator.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.omnicore.emulator.settings.N64PerformanceProfile
import com.omnicore.emulator.settings.N64Settings
import kotlin.math.max

/** Nintendo 64 adaptive policy. Visual quality is never reduced automatically. */
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
        val presentP95Ms: Float = 0f
    ) {
        val hasUsefulWindow: Boolean get() = sampleWindowFrames >= 90
        val targetFrameMs: Float
            get() = if (targetFps in 40f..75f) 1000f / targetFps else 1000f / 60f
        val audioCritical: Boolean
            get() = audioBufferMs > 0f && audioFillMs in 0f..max(8f, audioBufferMs * 0.35f)
        val gpuBound: Boolean
            get() = hasUsefulWindow && presentP95Ms >= max(3.5f, targetFrameMs * 0.22f) &&
                p95FrameMs > 0f && presentP95Ms >= p95FrameMs * 0.25f
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

    /** Session hysteresis controls safe live knobs; renderer/core changes wait for restart. */
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
        private val warmupMinUntil = warmupStartedAt + 4_000L
        private val warmupMaxUntil = warmupStartedAt + 12_000L
        private var warmupStableWindows = 0
        private var warmupActive = true

        fun initial(): Decision = current.copy(
            effective = protectedConfig(current.effective),
            audioBufferBursts = max(current.audioBufferBursts, 6),
            preferPowerEfficiency = false,
            aggressiveFramePacing = false,
            allowResolutionPromotion = false,
            leanGraphics = false,
            reason = "Precision WarmStart: qualidade preservada e áudio protegido"
        )

        fun adapt(raw: Telemetry): Decision {
            val recentUnderruns = (raw.audioUnderruns - lastUnderruns).coerceAtLeast(0)
            lastUnderruns = raw.audioUnderruns
            val telemetry = raw.copy(audioUnderruns = recentUnderruns)
            val signals = runtimeSignals(appContext)
            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now

            val stableWarmupWindow = telemetry.hasUsefulWindow &&
                telemetry.p95FrameMs <= telemetry.targetFrameMs * 1.10f &&
                telemetry.droppedFrames <= 2 &&
                recentUnderruns == 0 &&
                !telemetry.audioCritical
            if (warmupActive) {
                if (stableWarmupWindow && now >= warmupMinUntil) warmupStableWindows++
                else if (!stableWarmupWindow) warmupStableWindows = 0
                if (warmupStableWindows >= 2 || now >= warmupMaxUntil) warmupActive = false
            }

            fun protectAudio(decision: Decision): Decision {
                val recovering = now - lastAudioStressAt < 8_000L
                val target = when {
                    recentUnderruns >= 2 || telemetry.audioCritical -> 7
                    recentUnderruns > 0 || recovering -> 6
                    warmupActive -> 6
                    else -> decision.audioBufferBursts
                }
                return decision.copy(
                    effective = protectedConfig(decision.effective),
                    audioBufferBursts = max(decision.audioBufferBursts, target),
                    allowResolutionPromotion = false,
                    leanGraphics = false,
                    reason = if (recentUnderruns > 0) "PrecisionGovernor v2: recuperando áudio medido" else decision.reason
                )
            }

            if (warmupActive && signals.thermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
                pressureStreak = 0
                healthyStreak = 0
                current = protectAudio(current.copy(
                    effective = protectedConfig(current.effective),
                    reason = "Precision WarmStart estabilizando sem alterar qualidade"
                ))
                return current
            }

            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                recentUnderruns >= 3 || telemetry.audioCritical
            if (emergency) {
                pressureStreak = 0
                healthyStreak = 0
                current = protectAudio(candidate)
                lastTransitionAt = now
                return current
            }

            when {
                candidate.level.ordinal < current.level.ordinal -> {
                    pressureStreak++
                    healthyStreak = 0
                    if (pressureStreak >= 3 && now - lastTransitionAt >= 5_000L) {
                        current = candidate
                        lastTransitionAt = now
                        pressureStreak = 0
                    }
                }
                candidate.level.ordinal > current.level.ordinal -> {
                    healthyStreak++
                    pressureStreak = 0
                    if (healthyStreak >= 5 && now - lastTransitionAt >= 10_000L) {
                        current = candidate
                        lastTransitionAt = now
                        healthyStreak = 0
                    }
                }
                else -> {
                    pressureStreak = 0
                    healthyStreak = 0
                    current = candidate
                }
            }
            current = protectAudio(current)
            return current
        }

        private fun protectedConfig(config: N64Settings.Config): N64Settings.Config = config.copy(
            cpuMode = N64Settings.CpuMode.DYNAREC,
            rspMode = N64Settings.RspMode.HLE,
            internalResolution = requested.internalResolution,
            framebufferEmulation = requested.framebufferEmulation || requested.aspectRatio.wide,
            threadedRenderer = requested.threadedRenderer
        )
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
            telemetry.p95FrameMs >= target * 1.18f ||
                telemetry.droppedFrames >= 5 ||
                telemetry.audioUnderruns >= 1
            )
        val heavyPressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= target * 1.42f ||
                telemetry.droppedFrames >= 12 ||
                telemetry.audioUnderruns >= 3 || telemetry.audioCritical
            )

        fun protectedConfig(): N64Settings.Config = requested.copy(
            cpuMode = N64Settings.CpuMode.DYNAREC,
            rspMode = N64Settings.RspMode.HLE,
            internalResolution = requested.internalResolution,
            framebufferEmulation = requested.framebufferEmulation || requested.aspectRatio.wide,
            threadedRenderer = requested.threadedRenderer
        )

        if (severeThermal || heavyPressure) {
            return Decision(
                level = Level.ECO,
                effective = protectedConfig(),
                audioBufferBursts = if (telemetry.audioCritical || telemetry.audioUnderruns >= 2) 7 else 6,
                preferPowerEfficiency = severeThermal,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                leanGraphics = false,
                reason = when {
                    severeThermal -> "PrecisionGovernor v2: pressão térmica, qualidade visual preservada"
                    telemetry.audioCritical -> "PrecisionGovernor v2: pressão de áudio detectada"
                    telemetry.gpuBound -> "PrecisionGovernor v2: gargalo de apresentação/GPU medido"
                    else -> "PrecisionGovernor v2: pressão sustentada de emulação medida"
                }
            )
        }

        if (warmThermal || framePressure || signals.memoryPressure || signals.powerSave ||
            profile.tier == N64PerformanceProfile.Tier.LOW) {
            return Decision(
                level = Level.BALANCED,
                effective = protectedConfig(),
                audioBufferBursts = if (telemetry.audioUnderruns > 0 || telemetry.audioCritical) 6 else 5,
                preferPowerEfficiency = warmThermal || signals.powerSave,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                leanGraphics = false,
                reason = when {
                    warmThermal -> "PrecisionGovernor v2: desempenho sustentável sem reduzir resolução"
                    signals.memoryPressure -> "PrecisionGovernor v2: pressão de memória monitorada"
                    signals.powerSave -> "PrecisionGovernor v2: economia de energia detectada"
                    telemetry.gpuBound -> "PrecisionGovernor v2: pressão GPU/present detectada"
                    framePressure -> "PrecisionGovernor v2: frame time fora do orçamento"
                    else -> "PrecisionGovernor v2: perfil conservador de hardware"
                }
            )
        }

        val highMargin = telemetry.hasUsefulWindow &&
            telemetry.p95FrameMs <= target * 1.05f &&
            telemetry.droppedFrames <= 1 &&
            telemetry.audioUnderruns == 0 && !telemetry.audioCritical
        return Decision(
            level = if (highMargin) Level.TURBO else Level.BALANCED,
            effective = protectedConfig(),
            audioBufferBursts = if (highMargin) 3 else 4,
            preferPowerEfficiency = false,
            aggressiveFramePacing = highMargin,
            allowResolutionPromotion = false,
            leanGraphics = false,
            reason = if (highMargin) {
                "PrecisionGovernor v2: margem sustentada confirmada"
            } else {
                "PrecisionGovernor: equilíbrio medido"
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
