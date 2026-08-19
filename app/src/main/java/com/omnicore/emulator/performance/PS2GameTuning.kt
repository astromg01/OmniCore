package com.omnicore.emulator.performance

import android.content.Context
import android.os.PowerManager
import com.omnicore.emulator.core.ps2.PS2Backend
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Measurement-first per-game PS2 session governor.
 *
 * Alpha 5 originally persisted alternate renderers after low FPS. Physical-device
 * feedback proved that unsafe: a bootable game could become a black screen on the
 * next launch. Renderer selection is now completely outside this governor.
 *
 * The only automatic runtime experiment here is Play!'s own frame limiter. It is
 * benchmarked in the current process, compared against a baseline and immediately
 * restored when it does not produce a measurable gain. Nothing is persisted.
 */
object PS2GameTuning {
    enum class Phase { BASELINE, LIMITER_PROBE, LOCKED }

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
        /** null = keep current limiter state; otherwise apply this session-only value. */
        val frameLimitOverride: Boolean?
    )

    private data class Session(
        var state: State = emptyState(),
        var baselineSum: Float = 0f,
        var baselineCount: Int = 0,
        var probeSum: Float = 0f,
        var probeCount: Int = 0
    )

    private const val LEGACY_PREFS = "ps2_game_tuning_v1"
    private const val MIN_SAMPLE_FRAMES = 120
    private const val SLOW_FPS = 24f
    // Kept as the Alpha 5 sustained-slow diagnostic threshold. It no longer
    // changes or persists any renderer.
    private const val REQUIRED_SLOW_SAMPLES = 5

    private const val BASELINE_SAMPLES = 3
    private const val PROBE_SAMPLES = 3
    private const val LIMITER_PROBE_TRIGGER_FPS = 45f
    private const val MIN_PROBE_GAIN_RATIO = 1.08f
    private const val MIN_PROBE_GAIN_FPS = 1.5f

    private val sessions = ConcurrentHashMap<String, Session>()

    fun apply(
        context: Context,
        gameIdentity: String,
        plan: PS2SmartPerf.Plan,
        autoRendererRequested: Boolean,
        caps: PS2Backend.Capabilities
    ): PS2SmartPerf.Plan {
        // Retire any Alpha 5 persisted renderer. The current launch plan always
        // comes from explicit settings/device capability, never telemetry history.
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

        val slow = telemetry.measuredFps < SLOW_FPS
        val slowSamples = if (slow) previous.slowSamples + 1 else 0
        val common = previous.copy(
            forcedRenderer = null,
            samples = previous.samples + 1,
            slowSamples = slowSamples,
            lastFps = telemetry.measuredFps,
            lastDrawCallsPerFrame = telemetry.drawCallsPerFrame
        )

        if (!adaptiveRequested || !frameLimitRequested) {
            val next = common.copy(
                phase = Phase.LOCKED,
                frameLimiterEnabled = frameLimitRequested,
                note = "SmartPerf passivo: renderer e limiter seguem a configuração manual"
            )
            session.state = next
            return Observation(next, false, null)
        }

        val unsafePressure = telemetry.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            telemetry.memoryPressure >= 0.90f
        if (unsafePressure) {
            val mustRestore = !previous.frameLimiterEnabled
            val next = common.copy(
                phase = Phase.LOCKED,
                frameLimiterEnabled = true,
                note = "benchmark cancelado por pressão térmica/memória; limiter original restaurado"
            )
            session.state = next
            return Observation(next, false, if (mustRestore) true else null)
        }

        return when (previous.phase) {
            Phase.BASELINE -> observeBaseline(session, common, activeRenderer)
            Phase.LIMITER_PROBE -> observeLimiterProbe(session, common, activeRenderer)
            Phase.LOCKED -> {
                val sustained = slowSamples >= REQUIRED_SLOW_SAMPLES
                val next = common.copy(
                    note = when {
                        sustained && previous.frameLimiterEnabled ->
                            "slow-motion sustentado; limiter não trouxe ganho e renderer ${activeRenderer.name} permanece fixo"
                        sustained ->
                            "slow-motion sustentado; melhor resultado medido mantém limiter OFF só nesta sessão"
                        else -> previous.note
                    }
                )
                session.state = next
                Observation(next, false, null)
            }
        }
    }

    private fun observeBaseline(
        session: Session,
        common: State,
        activeRenderer: PS2Backend.Renderer
    ): Observation {
        session.baselineSum += common.lastFps
        session.baselineCount++
        val avg = session.baselineSum / session.baselineCount.coerceAtLeast(1)

        if (session.baselineCount < BASELINE_SAMPLES) {
            val next = common.copy(
                baselineFps = avg,
                frameLimiterEnabled = true,
                note = "SmartPerf V2 medindo baseline ${session.baselineCount}/$BASELINE_SAMPLES • ${activeRenderer.name}"
            )
            session.state = next
            return Observation(next, false, null)
        }

        if (avg >= LIMITER_PROBE_TRIGGER_FPS) {
            val next = common.copy(
                phase = Phase.LOCKED,
                baselineFps = avg,
                frameLimiterEnabled = true,
                note = "baseline estável; nenhum ajuste automático necessário"
            )
            session.state = next
            return Observation(next, false, null)
        }

        val next = common.copy(
            phase = Phase.LIMITER_PROBE,
            baselineFps = avg,
            frameLimiterEnabled = false,
            note = "baseline ${formatFps(avg)} FPS; testando limiter OFF sem mudar renderer/resolução"
        )
        session.state = next
        session.probeSum = 0f
        session.probeCount = 0
        return Observation(next, false, false)
    }

    private fun observeLimiterProbe(
        session: Session,
        common: State,
        activeRenderer: PS2Backend.Renderer
    ): Observation {
        session.probeSum += common.lastFps
        session.probeCount++
        val probeAvg = session.probeSum / session.probeCount.coerceAtLeast(1)
        val baseline = max(session.state.baselineFps, 0.1f)

        if (session.probeCount < PROBE_SAMPLES) {
            val next = common.copy(
                phase = Phase.LIMITER_PROBE,
                baselineFps = baseline,
                probeFps = probeAvg,
                frameLimiterEnabled = false,
                note = "benchmark limiter OFF ${session.probeCount}/$PROBE_SAMPLES • ${formatFps(probeAvg)} FPS"
            )
            session.state = next
            return Observation(next, false, null)
        }

        val ratio = probeAvg / baseline
        val absoluteGain = probeAvg - baseline
        val useful = ratio >= MIN_PROBE_GAIN_RATIO && absoluteGain >= MIN_PROBE_GAIN_FPS

        val next = common.copy(
            phase = Phase.LOCKED,
            baselineFps = baseline,
            probeFps = probeAvg,
            frameLimiterEnabled = !useful,
            note = if (useful) {
                "SmartPerf V2: limiter OFF ganhou ${formatPercent((ratio - 1f) * 100f)} nesta sessão; renderer ${activeRenderer.name} mantido"
            } else {
                "SmartPerf V2: limiter OFF não ajudou (${formatFps(baseline)}→${formatFps(probeAvg)} FPS); configuração original restaurada"
            }
        )
        session.state = next
        return Observation(next, false, if (useful) null else true)
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
        phase = Phase.BASELINE,
        baselineFps = -1f,
        probeFps = -1f,
        frameLimiterEnabled = true,
        note = "SmartPerf V2 aguardando telemetria"
    )

    private fun key(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun formatFps(value: Float): String = String.format("%.1f", value)
    private fun formatPercent(value: Float): String = String.format("%.0f%%", value)
}
