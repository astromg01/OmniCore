package com.omnicore.emulator.performance

import android.content.Context
import android.os.PowerManager
import com.omnicore.emulator.core.ps2.PS2Backend
import java.security.MessageDigest

/**
 * Conservative per-game tuning driven only by measurements produced by Play!.
 *
 * It never changes resolution, skips cycles or mutates a running GS backend.
 * When AUTO renderer performs severely below real-time for a sustained sample,
 * the alternate renderer is queued for the next launch of that same game.
 */
object PS2GameTuning {
    data class State(
        val forcedRenderer: PS2Backend.Renderer?,
        val samples: Int,
        val slowSamples: Int,
        val lastFps: Float,
        val lastDrawCallsPerFrame: Float,
        val note: String
    )

    data class Observation(
        val state: State,
        val queuedRendererChange: Boolean
    )

    private const val PREFS = "ps2_game_tuning_v1"
    private const val MIN_SAMPLE_FRAMES = 180
    private const val SLOW_FPS = 24f
    private const val REQUIRED_SLOW_SAMPLES = 5

    fun apply(
        context: Context,
        gameIdentity: String,
        plan: PS2SmartPerf.Plan,
        autoRendererRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): PS2SmartPerf.Plan {
        if (!autoRendererRequested) return plan
        val state = read(context, gameIdentity)
        val forced = state.forcedRenderer ?: return plan
        val usable = when (forced) {
            PS2Backend.Renderer.VULKAN -> caps.vulkan
            PS2Backend.Renderer.GLES3 -> caps.gles3
            PS2Backend.Renderer.AUTO -> false
        }
        return if (usable) {
            plan.copy(renderer = forced, reason = "ajuste medido por jogo: ${forced.name}")
        } else plan
    }

    fun observe(
        context: Context,
        gameIdentity: String,
        telemetry: PS2Backend.Telemetry,
        activeRenderer: PS2Backend.Renderer,
        autoRendererRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): Observation {
        val previous = read(context, gameIdentity)
        if (!autoRendererRequested || telemetry.sampleFrames < MIN_SAMPLE_FRAMES || telemetry.measuredFps <= 0f) {
            return Observation(previous, false)
        }
        if (telemetry.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || telemetry.memoryPressure >= 0.90f) {
            val state = previous.copy(
                lastFps = telemetry.measuredFps,
                lastDrawCallsPerFrame = telemetry.drawCallsPerFrame,
                note = "amostra ignorada por pressão térmica/memória"
            )
            write(context, gameIdentity, state)
            return Observation(state, false)
        }

        val slow = telemetry.measuredFps < SLOW_FPS
        var state = previous.copy(
            samples = previous.samples + 1,
            slowSamples = if (slow) previous.slowSamples + 1 else 0,
            lastFps = telemetry.measuredFps,
            lastDrawCallsPerFrame = telemetry.drawCallsPerFrame,
            note = if (slow) "slow-motion sustentado em observação" else "renderer atual estável"
        )

        var queued = false
        if (state.slowSamples >= REQUIRED_SLOW_SAMPLES) {
            val alternate = when (activeRenderer) {
                PS2Backend.Renderer.VULKAN -> if (caps.gles3) PS2Backend.Renderer.GLES3 else null
                PS2Backend.Renderer.GLES3 -> if (caps.vulkan) PS2Backend.Renderer.VULKAN else null
                PS2Backend.Renderer.AUTO -> null
            }
            if (alternate != null && alternate != state.forcedRenderer) {
                state = state.copy(
                    forcedRenderer = alternate,
                    slowSamples = 0,
                    note = "${alternate.name} agendado para o próximo boot após slow-motion medido"
                )
                queued = true
            }
        }
        write(context, gameIdentity, state)
        return Observation(state, queued)
    }

    fun read(context: Context, gameIdentity: String): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(gameIdentity)
        val renderer = prefs.getString("${key}_renderer", null)?.let { raw ->
            PS2Backend.Renderer.entries.firstOrNull { it.name == raw }
        }
        return State(
            forcedRenderer = renderer,
            samples = prefs.getInt("${key}_samples", 0),
            slowSamples = prefs.getInt("${key}_slow", 0),
            lastFps = prefs.getFloat("${key}_fps", -1f),
            lastDrawCallsPerFrame = prefs.getFloat("${key}_draw", -1f),
            note = prefs.getString("${key}_note", "sem histórico medido") ?: "sem histórico medido"
        )
    }

    fun clear(context: Context, gameIdentity: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(gameIdentity)
        prefs.edit()
            .remove("${key}_renderer")
            .remove("${key}_samples")
            .remove("${key}_slow")
            .remove("${key}_fps")
            .remove("${key}_draw")
            .remove("${key}_note")
            .apply()
    }

    private fun write(context: Context, gameIdentity: String, state: State) {
        val key = key(gameIdentity)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply {
                if (state.forcedRenderer == null) remove("${key}_renderer")
                else putString("${key}_renderer", state.forcedRenderer.name)
            }
            .putInt("${key}_samples", state.samples)
            .putInt("${key}_slow", state.slowSamples)
            .putFloat("${key}_fps", state.lastFps)
            .putFloat("${key}_draw", state.lastDrawCallsPerFrame)
            .putString("${key}_note", state.note)
            .apply()
    }

    private fun key(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
