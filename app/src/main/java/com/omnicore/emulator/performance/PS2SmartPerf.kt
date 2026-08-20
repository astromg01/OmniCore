package com.omnicore.emulator.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.omnicore.emulator.core.ps2.PS2Backend
import com.omnicore.emulator.settings.PS2Settings
import kotlin.math.max
import kotlin.math.min

/**
 * PS2 adaptive policy.
 *
 * Only proven knobs are applied. Requested image quality is protected: thermal,
 * memory or timing pressure never silently lowers the selected framebuffer scale
 * and cycle skipping stays disabled.
 */
object PS2SmartPerf {
    enum class Mode { ECO, BALANCED, PERFORMANCE }
    enum class Pressure { STABLE, EE_VU, GS, MIXED, AUDIO, THERMAL, MEMORY }

    data class DeviceEnvelope(
        val lowRam: Boolean,
        val memoryClassMiB: Int,
        val thermalStatus: Int,
        val processors: Int,
        val powerSave: Boolean
    )

    data class Plan(
        val mode: Mode,
        val renderer: PS2Backend.Renderer,
        val internalResolutionFactor: Int,
        val widescreen: Boolean,
        val presentationMode: Int,
        val forceBilinear: Boolean,
        val frameLimit: Boolean,
        val spuBlockCount: Int,
        val textureCacheMiB: Int,
        val jitCacheMiB: Int,
        val audioTargetMs: Int,
        val queueAheadFrames: Int,
        val asyncTextureUpload: Boolean,
        val qualityFloorScale: Float = 1.0f,
        val dynamicResolutionAllowed: Boolean = false,
        val cycleSkippingAllowed: Boolean = false,
        val reason: String
    ) {
        fun asRuntimeConfig(): PS2Backend.RuntimeConfig = PS2Backend.RuntimeConfig(
            renderer = renderer,
            internalResolutionFactor = internalResolutionFactor,
            widescreen = widescreen,
            presentationMode = presentationMode,
            forceBilinear = forceBilinear,
            limitFrameRate = frameLimit,
            spuBlockCount = spuBlockCount,
            qualityFloorScale = max(1.0f, qualityFloorScale),
            textureCacheMiB = textureCacheMiB,
            jitCacheMiB = jitCacheMiB,
            audioTargetMs = audioTargetMs,
            queueAheadFrames = queueAheadFrames,
            allowAsyncTextureUpload = asyncTextureUpload,
            allowCycleSkipping = false
        )
    }

    data class Decision(
        val plan: Plan,
        val pressure: Pressure,
        val confidence: Float,
        val changed: Boolean
    )

    fun envelope(context: Context): DeviceEnvelope {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return DeviceEnvelope(
            lowRam = am.isLowRamDevice,
            memoryClassMiB = am.memoryClass,
            thermalStatus = thermal,
            processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            powerSave = power.isPowerSaveMode
        )
    }

    fun initial(
        context: Context,
        caps: PS2Backend.Capabilities,
        requested: PS2Settings.Config = PS2Settings.resolve(context)
    ): Plan {
        val env = envelope(context)
        val mode = when (requested.preset) {
            PS2Settings.Preset.PERFORMANCE -> Mode.PERFORMANCE
            PS2Settings.Preset.BALANCED, PS2Settings.Preset.QUALITY -> Mode.BALANCED
            PS2Settings.Preset.CUSTOM -> if (env.lowRam) Mode.ECO else Mode.BALANCED
            PS2Settings.Preset.AUTO -> when {
                env.lowRam || isThermallyConstrained(env.thermalStatus) || env.powerSave -> Mode.ECO
                env.memoryClassMiB >= 384 && env.processors >= 6 -> Mode.PERFORMANCE
                else -> Mode.BALANCED
            }
        }
        val renderer = PS2Settings.rendererFor(requested, caps)
        val base = basePlan(mode, requested, renderer, "perfil inicial medido do dispositivo")
        return if (isThermallyConstrained(env.thermalStatus)) {
            base.copy(
                mode = Mode.ECO,
                spuBlockCount = max(base.spuBlockCount, 96),
                queueAheadFrames = 1,
                reason = "WarmStart PS2: pressão térmica; qualidade solicitada preservada"
            )
        } else base
    }

    fun adapt(current: Plan, telemetry: PS2Backend.Telemetry): Decision {
        if (telemetry.sampleFrames < 90) {
            return Decision(current, Pressure.STABLE, 0f, changed = false)
        }

        if (isThermallyConstrained(telemetry.thermalStatus)) {
            val next = current.copy(
                mode = Mode.ECO,
                spuBlockCount = max(current.spuBlockCount, 96),
                queueAheadFrames = 1,
                reason = "pressão térmica; resolução protegida em ${current.internalResolutionFactor}×"
            )
            return Decision(next, Pressure.THERMAL, 1f, next != current)
        }
        if (telemetry.memoryPressure >= 0.85f) {
            val next = current.copy(
                textureCacheMiB = max(48, current.textureCacheMiB - 32),
                jitCacheMiB = max(12, current.jitCacheMiB - 8),
                queueAheadFrames = 1,
                reason = "pressão de memória; qualidade visual preservada"
            )
            return Decision(next, Pressure.MEMORY, telemetry.memoryPressure.coerceIn(0f, 1f), next != current)
        }
        if (telemetry.hardAudioUnderruns > 0) {
            val next = current.copy(
                spuBlockCount = min(100, current.spuBlockCount + 4),
                audioTargetMs = min(112, current.audioTargetMs + 8),
                reason = "starvation de áudio medido"
            )
            return Decision(next, Pressure.AUDIO, 0.9f, next != current)
        }

        // Alpha 6 #19 prefers the backend's persistent native classifier. These
        // labels come from PCSX2 EE/VU/GS/GPU counters, not process-wide guesses.
        when (telemetry.bottleneck) {
            "EE" -> return Decision(
                current.copy(reason = "EE dominante; GS extra mantido desligado no próximo boot"),
                Pressure.EE_VU,
                (telemetry.eeUsagePercent / 100f).coerceIn(0f, 1f),
                false
            )
            "VU" -> return Decision(
                current.copy(reason = "VU dominante; MTVU/InstantVU1 preservados e afinidade deixada ao EAS"),
                Pressure.EE_VU,
                (telemetry.vuUsagePercent / 100f).coerceIn(0f, 1f),
                false
            )
            "GS" -> return Decision(
                current.copy(reason = "GS dominante; pipeline GS será elegível no próximo boot"),
                Pressure.GS,
                ((telemetry.gsUsagePercent + telemetry.gsBackUsagePercent) / 100f).coerceIn(0f, 1f),
                false
            )
            "GPU" -> return Decision(
                current.copy(reason = "GPU dominante; visibilidade=${telemetry.visibilityPressure}; resolução protegida"),
                Pressure.GS,
                (telemetry.gpuUsagePercent / 100f).coerceIn(0f, 1f),
                false
            )
        }

        // Fallback for backends without the native classifier.
        val eeVu = positive(telemetry.eeMs) + positive(telemetry.vuMs)
        val gs = positive(telemetry.gsMs) + positive(telemetry.gsBackMs) + positive(telemetry.presentMs)
        if (eeVu <= 0f && gs <= 0f) {
            return Decision(current, Pressure.STABLE, 0f, changed = false)
        }
        val total = max(0.001f, eeVu + gs)
        val eeShare = eeVu / total
        val gsShare = gs / total
        return when {
            eeShare >= 0.62f -> Decision(current.copy(reason = "EE/VU dominante; aguardando perfil persistente"), Pressure.EE_VU, eeShare, false)
            gsShare >= 0.62f -> Decision(current.copy(reason = "GS/GPU dominante; qualidade solicitada protegida"), Pressure.GS, gsShare, false)
            else -> Decision(current, Pressure.MIXED, 0.55f, false)
        }
    }

    private fun basePlan(
        mode: Mode,
        requested: PS2Settings.Config,
        renderer: PS2Backend.Renderer,
        reason: String
    ): Plan {
        val common = Plan(
            mode = mode,
            renderer = renderer,
            internalResolutionFactor = requested.internalResolution.factor,
            widescreen = requested.widescreen,
            presentationMode = requested.presentation.nativeValue,
            forceBilinear = requested.forceBilinear,
            frameLimit = requested.frameLimit,
            spuBlockCount = requested.spuBlockCount,
            textureCacheMiB = 96,
            jitCacheMiB = 24,
            audioTargetMs = 64,
            queueAheadFrames = 1,
            asyncTextureUpload = true,
            reason = reason
        )
        return when (mode) {
            Mode.ECO -> common.copy(
                textureCacheMiB = 64,
                jitCacheMiB = 16,
                audioTargetMs = 76,
                queueAheadFrames = 1,
                spuBlockCount = max(common.spuBlockCount, 96)
            )
            Mode.BALANCED -> common.copy(
                textureCacheMiB = 96,
                jitCacheMiB = 24,
                audioTargetMs = 64,
                queueAheadFrames = 1
            )
            Mode.PERFORMANCE -> common.copy(
                textureCacheMiB = 128,
                jitCacheMiB = 32,
                audioTargetMs = 56,
                queueAheadFrames = 2
            )
        }
    }

    private fun positive(value: Float): Float = if (value > 0f) value else 0f

    private fun isThermallyConstrained(status: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            status >= PowerManager.THERMAL_STATUS_SEVERE
}
