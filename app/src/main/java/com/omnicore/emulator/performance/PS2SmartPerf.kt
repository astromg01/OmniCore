package com.omnicore.emulator.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.omnicore.emulator.core.ps2.PS2Backend
import kotlin.math.max
import kotlin.math.min

/**
 * PS2 SmartPerf foundation.
 *
 * Decisions are bounded recommendations. The backend must advertise support
 * before a recommendation is applied. No cycle skipping or sub-native dynamic
 * resolution is enabled by this policy.
 */
object PS2SmartPerf {
    enum class Mode { ECO, BALANCED, PERFORMANCE }
    enum class Pressure { STABLE, EE_VU, GS, MIXED, AUDIO, THERMAL, MEMORY }

    data class DeviceEnvelope(
        val lowRam: Boolean,
        val memoryClassMiB: Int,
        val thermalStatus: Int,
        val processors: Int
    )

    data class Plan(
        val mode: Mode,
        val renderer: PS2Backend.Renderer,
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
            processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        )
    }

    fun initial(context: Context, caps: PS2Backend.Capabilities): Plan {
        val env = envelope(context)
        val mode = when {
            env.lowRam || env.memoryClassMiB <= 256 -> Mode.ECO
            env.memoryClassMiB >= 512 && !isThermallyConstrained(env.thermalStatus) -> Mode.PERFORMANCE
            else -> Mode.BALANCED
        }
        val renderer = when {
            caps.vulkan -> PS2Backend.Renderer.VULKAN
            caps.gles3 -> PS2Backend.Renderer.GLES3
            else -> PS2Backend.Renderer.AUTO
        }
        return basePlan(mode, renderer, "initial device envelope")
    }

    fun adapt(current: Plan, telemetry: PS2Backend.Telemetry): Decision {
        if (telemetry.sampleFrames < 90) {
            return Decision(current, Pressure.STABLE, 0f, changed = false)
        }

        if (isThermallyConstrained(telemetry.thermalStatus)) {
            val next = basePlan(Mode.ECO, current.renderer, "thermal pressure")
            return Decision(next, Pressure.THERMAL, 1f, next != current)
        }
        if (telemetry.memoryPressure >= 0.85f) {
            val next = current.copy(
                textureCacheMiB = max(48, current.textureCacheMiB - 32),
                jitCacheMiB = max(12, current.jitCacheMiB - 8),
                queueAheadFrames = 1,
                reason = "memory pressure"
            )
            return Decision(next, Pressure.MEMORY, telemetry.memoryPressure.coerceIn(0f, 1f), next != current)
        }
        if (telemetry.hardAudioUnderruns > 0 && telemetry.audioFillMs in 0f..36f) {
            val next = current.copy(
                audioTargetMs = min(112, current.audioTargetMs + 12),
                queueAheadFrames = max(1, current.queueAheadFrames),
                reason = "audio starvation"
            )
            return Decision(next, Pressure.AUDIO, 0.85f, next != current)
        }

        val eeVu = positive(telemetry.eeMs) + positive(telemetry.vuMs)
        val gs = positive(telemetry.gsMs) + positive(telemetry.presentMs)
        if (eeVu <= 0f && gs <= 0f) {
            return Decision(current, Pressure.STABLE, 0f, changed = false)
        }

        val total = max(0.001f, eeVu + gs)
        val eeShare = eeVu / total
        val gsShare = gs / total
        return when {
            eeShare >= 0.62f -> {
                val next = current.copy(
                    jitCacheMiB = min(64, current.jitCacheMiB + 8),
                    reason = "EE/VU dominated window"
                )
                Decision(next, Pressure.EE_VU, eeShare.coerceIn(0f, 1f), next != current)
            }
            gsShare >= 0.62f -> {
                val next = current.copy(
                    asyncTextureUpload = true,
                    queueAheadFrames = min(2, max(1, current.queueAheadFrames)),
                    reason = "GS/present dominated window"
                )
                Decision(next, Pressure.GS, gsShare.coerceIn(0f, 1f), next != current)
            }
            else -> Decision(current, Pressure.MIXED, 0.55f, changed = false)
        }
    }

    private fun basePlan(mode: Mode, renderer: PS2Backend.Renderer, reason: String): Plan = when (mode) {
        Mode.ECO -> Plan(
            mode = mode,
            renderer = renderer,
            textureCacheMiB = 64,
            jitCacheMiB = 16,
            audioTargetMs = 76,
            queueAheadFrames = 1,
            asyncTextureUpload = true,
            reason = reason
        )
        Mode.BALANCED -> Plan(
            mode = mode,
            renderer = renderer,
            textureCacheMiB = 96,
            jitCacheMiB = 24,
            audioTargetMs = 64,
            queueAheadFrames = 1,
            asyncTextureUpload = true,
            reason = reason
        )
        Mode.PERFORMANCE -> Plan(
            mode = mode,
            renderer = renderer,
            textureCacheMiB = 128,
            jitCacheMiB = 32,
            audioTargetMs = 56,
            queueAheadFrames = 2,
            asyncTextureUpload = true,
            reason = reason
        )
    }

    private fun positive(value: Float): Float = if (value > 0f) value else 0f

    private fun isThermallyConstrained(status: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            status >= PowerManager.THERMAL_STATUS_SEVERE
}
