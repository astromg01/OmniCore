package com.omnicore.emulator.performance

import android.content.Context
import com.omnicore.emulator.core.ps2.PS2Backend
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Measurement-only per-game PS2 session monitor.
 *
 * Alpha 5 tried two forms of automatic runtime mutation: persistent renderer
 * fallback and a session-only frame-limiter probe. Physical-device testing on
 * a low-end Android device showed both are unsafe as generic policies: renderer
 * fallback could black-screen a later boot and limiter probing could reduce
 * observed performance dramatically. SmartPerf therefore stays passive until
 * the backend exposes a proven bottleneck-specific knob.
 */
object PS2GameTuning {
    enum class Phase { MEASURING, LOCKED }

    data class State(
        val forcedRenderer: PS2Backend.Renderer?,
        val samples: Int,
        val slowSamples: Int,
        val lastFps: Float,
        val lastDrawCallsPerFrame: Float,
        val phase: Phase,
        val baselineFps: Float,
        val probeFps: Float,
        val frameLimiterEnabled: Boolean,
        val note: String
    )

    data class Observation(
        val state: State,
        val queuedRendererChange: Boolean,
        /** Always null while SmartPerf is measurement-only. */
        val frameLimitOverride: Boolean?
    )

    private data class Session(
        var state: State = emptyState(),
        var fpsSum: Float = 0f,
        var fpsCount: Int = 0
    )

    private const val LEGACY_PREFS = "ps2_game_tuning_v1"
    private const val MIN_SAMPLE_FRAMES = 120
    private const val SLOW_FPS = 24f
    private const val REQUIRED_SLOW_SAMPLES = 5
    private const val BASELINE_SAMPLES = 3

    private val sessions = ConcurrentHashMap<String, Session>()

    fun apply(
        context: Context,
        gameIdentity: String,
        plan: PS2SmartPerf.Plan,
        autoRendererRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): PS2SmartPerf.Plan {
        // Remove any unsafe persisted renderer left by early Alpha 5 builds.
        clearLegacyRenderer(context, gameIdentity)
        return plan
    }

    fun observe(
        context: Context,
        gameIdentity: String,
        telemetry: PS2Backend.Telemetry,
        activeRenderer: PS2Backend.Renderer,
        adaptiveRequested: Boolean,
        frameLimitRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): Observation {
        val stateKey = key(gameIdentity)
        val session = sessions.getOrPut(stateKey) { Session() }
        val previous = session.state

        if (telemetry.sampleFrames < MIN_SAMPLE_FRAMES || telemetry.measuredFps <= 0f) {
            return Observation(previous, queuedRendererChange = false, frameLimitOverride = null)
        }

        session.fpsSum += telemetry.measuredFps
        session.fpsCount++
        val baseline = session.fpsSum / session.fpsCount.coerceAtLeast(1)
        val slow = telemetry.measuredFps < SLOW_FPS
        val slowSamples = if (slow) previous.slowSamples + 1 else 0
        val sustainedSlow = slowSamples >= REQUIRED_SLOW_SAMPLES

        val next = previous.copy(
            forcedRenderer = null,
            samples = previous.samples + 1,
            slowSamples = slowSamples,
            lastFps = telemetry.measuredFps,
            lastDrawCallsPerFrame = telemetry.drawCallsPerFrame,
            phase = if (session.fpsCount >= BASELINE_SAMPLES) Phase.LOCKED else Phase.MEASURING,
            baselineFps = baseline,
            probeFps = -1f,
            frameLimiterEnabled = frameLimitRequested,
            note = when {
                !adaptiveRequested ->
                    "SmartPerf passivo: configuração manual preservada"
                sustainedSlow ->
                    "slow-motion sustentado em ${formatFps(telemetry.measuredFps)} FPS; nenhum ajuste automático aplicado"
                session.fpsCount < BASELINE_SAMPLES ->
                    "medindo baseline ${session.fpsCount}/$BASELINE_SAMPLES • ${activeRenderer.name}"
                else ->
                    "baseline ${formatFps(baseline)} FPS • monitoramento passivo • ${activeRenderer.name}"
            }
        )
        session.state = next
        return Observation(next, queuedRendererChange = false, frameLimitOverride = null)
    }

    fun read(context: Context, gameIdentity: String): State {
        clearLegacyRenderer(context, gameIdentity)
        return sessions[key(gameIdentity)]?.state ?: emptyState()
    }

    fun clear(context: Context, gameIdentity: String) {
        val stateKey = key(gameIdentity)
        sessions.remove(stateKey)
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
        phase = Phase.MEASURING,
        baselineFps = -1f,
        probeFps = -1f,
        frameLimiterEnabled = true,
        note = "SmartPerf aguardando telemetria"
    )

    private fun key(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun formatFps(value: Float): String = String.format("%.1f", value)
}
