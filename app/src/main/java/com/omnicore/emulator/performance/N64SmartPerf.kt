package com.omnicore.emulator.performance

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.omnicore.emulator.settings.N64PerformanceProfile
import com.omnicore.emulator.settings.N64Settings

/**
 * Nintendo 64 specific adaptive performance policy.
 *
 * This intentionally does not reuse PlayStation runtime knobs. N64 pressure is
 * dominated by R4300 dynarec, RSP/RDP work, framebuffer effects and internal
 * resolution. The policy only produces conservative recommendations; the N64
 * host decides when a recommendation can be applied safely between frames.
 */
object N64SmartPerf {
    enum class Level { ECO, BALANCED, TURBO }

    data class Telemetry(
        val averageFrameMs: Float = 0f,
        val p95FrameMs: Float = 0f,
        val droppedFrames: Int = 0,
        val audioUnderruns: Int = 0,
        val sampleWindowFrames: Int = 0
    ) {
        val hasUsefulWindow: Boolean get() = sampleWindowFrames >= 90
    }

    data class Decision(
        val level: Level,
        val effective: N64Settings.Config,
        val audioBufferBursts: Int,
        val preferPowerEfficiency: Boolean,
        val aggressiveFramePacing: Boolean,
        val allowResolutionPromotion: Boolean,
        val reason: String
    )

    fun initial(context: Context, requested: N64Settings.Config): Decision {
        return resolve(
            profile = N64PerformanceProfile.detect(context),
            requested = requested,
            thermalStatus = currentThermalStatus(context),
            telemetry = Telemetry()
        )
    }

    fun adapt(
        context: Context,
        requested: N64Settings.Config,
        telemetry: Telemetry
    ): Decision = resolve(
        profile = N64PerformanceProfile.detect(context),
        requested = requested,
        thermalStatus = currentThermalStatus(context),
        telemetry = telemetry
    )

    internal fun resolve(
        profile: N64PerformanceProfile.Profile,
        requested: N64Settings.Config,
        thermalStatus: Int,
        telemetry: Telemetry
    ): Decision {
        val severeThermal = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val warmThermal = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

        // Do not react to one bad frame. Runtime adaptation starts only after a
        // meaningful window and requires repeated symptoms (p95/drop/audio).
        val framePressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= 22.5f ||
                telemetry.droppedFrames >= 6 ||
                telemetry.audioUnderruns >= 3
            )
        val heavyPressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= 28.0f ||
                telemetry.droppedFrames >= 14 ||
                telemetry.audioUnderruns >= 8
            )

        if (severeThermal || heavyPressure) {
            return Decision(
                level = Level.ECO,
                effective = requested.copy(
                    internalResolution = N64Settings.InternalResolution.NATIVE,
                    framebufferEmulation = false,
                    threadedRenderer = true,
                    cpuMode = N64Settings.CpuMode.DYNAREC,
                    rspMode = N64Settings.RspMode.HLE
                ),
                audioBufferBursts = 4,
                preferPowerEfficiency = true,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                reason = if (severeThermal) {
                    "SmartPerf N64 reduziu carga por temperatura"
                } else {
                    "SmartPerf N64 detectou pressão sustentada de frame/áudio"
                }
            )
        }

        if (warmThermal || framePressure || profile.tier == N64PerformanceProfile.Tier.LOW) {
            return Decision(
                level = Level.BALANCED,
                effective = requested.copy(
                    internalResolution = N64Settings.InternalResolution.NATIVE,
                    threadedRenderer = true,
                    cpuMode = N64Settings.CpuMode.DYNAREC,
                    rspMode = N64Settings.RspMode.HLE
                ),
                audioBufferBursts = 3,
                preferPowerEfficiency = warmThermal,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                reason = when {
                    warmThermal -> "SmartPerf N64 preservando desempenho sustentável"
                    framePressure -> "SmartPerf N64 estabilizando frame pacing"
                    else -> "SmartPerf N64 conservador para hardware limitado"
                }
            )
        }

        val highMargin = profile.tier == N64PerformanceProfile.Tier.HIGH &&
            (!telemetry.hasUsefulWindow || (
                telemetry.p95FrameMs in 0f..18.5f &&
                    telemetry.droppedFrames <= 1 &&
                    telemetry.audioUnderruns == 0
                ))

        return Decision(
            level = if (highMargin) Level.TURBO else Level.BALANCED,
            effective = requested.copy(
                threadedRenderer = true,
                cpuMode = N64Settings.CpuMode.DYNAREC
            ),
            audioBufferBursts = if (highMargin) 2 else 3,
            preferPowerEfficiency = false,
            aggressiveFramePacing = highMargin,
            // Promotion is only permission. The host must wait for a safe point
            // and the selected preset must allow quality changes.
            allowResolutionPromotion = highMargin && requested.preset == N64Settings.Preset.AUTO,
            reason = if (highMargin) {
                "SmartPerf N64 detectou margem para baixa latência"
            } else {
                "SmartPerf N64 em equilíbrio automático"
            }
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
