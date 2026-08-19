package com.omnicore.emulator.performance

import android.content.Context
import android.os.PowerManager
import com.omnicore.emulator.core.ps2.PS2Backend
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Measurement-only per-game PS2 tuning.
 *
 * Alpha 5 used to persist an alternate GS renderer after sustained slow samples.
 * On real devices that can turn a bootable game into a black screen on the next
 * launch. Runtime renderer changes are therefore advisory only until a renderer
 * can be validated safely before becoming persistent.
 *
 * Resolution and cycle skipping are never changed here.
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

    private const val LEGACY_PREFS = "ps2_game_tuning_v1"
    private const val MIN_SAMPLE_FRAMES = 180
    private const val SLOW_FPS = 24f
    // Kept as the Alpha 5 sustained-slow diagnostic threshold. Reaching it no
    // longer changes or persists the renderer; it only enriches the note.
    private const val REQUIRED_SLOW_SAMPLES = 5

    // Telemetry history is intentionally process-local. This keeps the gameplay
    // path free of SharedPreferences writes and prevents an unsafe renderer from
    // being carried into the next boot.
    private val liveStates = ConcurrentHashMap<String, State>()

    fun apply(
        context: Context,
        gameIdentity: String,
        plan: PS2SmartPerf.Plan,
        autoRendererRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): PS2SmartPerf.Plan {
        // Retire the Alpha 5 persisted renderer once. The selected launch plan is
        // always the plan resolved from current user settings/device capability.
        clearLegacyRenderer(context, gameIdentity)
        return plan
    }

    fun observe(
        context: Context,
        gameIdentity: String,
        telemetry: PS2Backend.Telemetry,
        activeRenderer: PS2Backend.Renderer,
        autoRendererRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): Observation {
        val stateKey = key(gameIdentity)
        val previous = liveStates[stateKey] ?: emptyState()

        if (!autoRendererRequested || telemetry.sampleFrames < MIN_SAMPLE_FRAMES || telemetry.measuredFps <= 0f) {
            return Observation(previous, false)
        }

        if (telemetry.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || telemetry.memoryPressure >= 0.90f) {
            val next = previous.copy(
                forcedRenderer = null,
                lastFps = telemetry.measuredFps,
                lastDrawCallsPerFrame = telemetry.drawCallsPerFrame,
                note = "amostra ignorada por pressão térmica/memória; renderer mantido"
            )
            liveStates[stateKey] = next
            return Observation(next, false)
        }

        val slow = telemetry.measuredFps < SLOW_FPS
        val slowSamples = if (slow) previous.slowSamples + 1 else 0
        val sustained = slowSamples >= REQUIRED_SLOW_SAMPLES
        val next = previous.copy(
            forcedRenderer = null,
            samples = previous.samples + 1,
            slowSamples = slowSamples,
            lastFps = telemetry.measuredFps,
            lastDrawCallsPerFrame = telemetry.drawCallsPerFrame,
            note = when {
                sustained -> "slow-motion sustentado; renderer ${activeRenderer.name} mantido por segurança"
                slow -> "slow-motion medido; renderer ${activeRenderer.name} mantido por segurança"
                else -> "renderer ${activeRenderer.name} estável"
            }
        )
        liveStates[stateKey] = next

        // Never queue a renderer switch from telemetry alone. A future tuning
        // revision may benchmark an alternate renderer in a disposable probe,
        // but the normal next boot must stay known-good.
        return Observation(next, false)
    }

    fun read(context: Context, gameIdentity: String): State {
        clearLegacyRenderer(context, gameIdentity)
        return liveStates[key(gameIdentity)] ?: emptyState()
    }

    fun clear(context: Context, gameIdentity: String) {
        val stateKey = key(gameIdentity)
        liveStates.remove(stateKey)
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
            .remove("${stateKey}_renderer")
            .remove("${stateKey}_samples")
            .remove("${stateKey}_slow")
            .remove("${stateKey}_fps")
            .remove("${stateKey}_draw")
            .remove("${stateKey}_note")
            .apply()
    }

    private fun clearLegacyRenderer(context: Context, gameIdentity: String) {
        val stateKey = key(gameIdentity)
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val rendererKey = "${stateKey}_renderer"
        if (prefs.contains(rendererKey)) {
            prefs.edit().remove(rendererKey).apply()
        }
    }

    private fun emptyState() = State(
        forcedRenderer = null,
        samples = 0,
        slowSamples = 0,
        lastFps = -1f,
        lastDrawCallsPerFrame = -1f,
        note = "sem histórico medido"
    )

    private fun key(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
