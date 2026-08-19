package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Original OmniCore classic PS2-era boot sequence.
 *
 * This is deliberately not a reproduction of Sony's BIOS animation: no Sony or
 * PlayStation logo, BIOS geometry, copyrighted audio, font or proprietary asset
 * is embedded. It uses an original deep-blue void, perspective pillars, particles
 * and OmniCore branding before handing off to the real PS2 backend.
 */
class PS2ClassicBootView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.25f
    }
    private val finePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.7f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.14f
    }

    private var startedAt = 0L
    private var running = false
    private var finished = false
    private var onFinished: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            invalidate()
            if (elapsed >= DURATION_MS) {
                running = false
                if (!finished) {
                    finished = true
                    onFinished?.invoke()
                }
            } else {
                postDelayed(this, 16L)
            }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(onComplete: () -> Unit) {
        removeCallbacks(tick)
        onFinished = onComplete
        startedAt = SystemClock.uptimeMillis()
        running = true
        finished = false
        visibility = VISIBLE
        post(tick)
    }

    fun cancel() {
        running = false
        removeCallbacks(tick)
        onFinished = null
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val elapsed = if (startedAt == 0L) 0L else SystemClock.uptimeMillis() - startedAt
        val t = (elapsed / DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val short = min(width, height).toFloat()
        val cx = width * 0.5f
        val cy = height * 0.49f

        canvas.drawColor(Color.rgb(0, 0, 3))

        val atmosphere = smoothStep(0.02f, 0.30f, t) * (1f - smoothStep(0.90f, 1f, t))
        paint.shader = RadialGradient(
            cx,
            cy,
            short * (0.20f + 0.38f * t),
            intArrayOf(
                Color.argb((150 * atmosphere).toInt(), 12, 56, 172),
                Color.argb((72 * atmosphere).toInt(), 5, 20, 72),
                Color.argb((18 * atmosphere).toInt(), 0, 5, 20),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.34f, 0.69f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        drawHorizon(canvas, t, short, cx, cy, atmosphere)
        drawPerspectiveField(canvas, t, short, cx, cy, atmosphere)
        drawParticles(canvas, t, short, cx, cy, atmosphere)
        drawCorePulse(canvas, t, short, cx, cy)
        drawBrand(canvas, t, short, cx)

        if (t > 0.965f) {
            val blackout = smoothStep(0.965f, 1f, t)
            paint.color = Color.argb((255 * blackout).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private fun drawHorizon(
        canvas: Canvas,
        t: Float,
        short: Float,
        cx: Float,
        cy: Float,
        alpha: Float
    ) {
        val reveal = smoothStep(0.08f, 0.32f, t) * alpha
        if (reveal <= 0f) return

        val horizonY = cy + short * 0.12f
        paint.shader = LinearGradient(
            0f,
            horizonY - short * 0.04f,
            0f,
            horizonY + short * 0.05f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((48 * reveal).toInt(), 34, 106, 255),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY - short * 0.04f, width.toFloat(), horizonY + short * 0.05f, paint)
        paint.shader = null

        linePaint.color = Color.argb((85 * reveal).toInt(), 72, 142, 255)
        canvas.drawLine(cx - short * 0.36f, horizonY, cx + short * 0.36f, horizonY, linePaint)
    }

    private fun drawPerspectiveField(
        canvas: Canvas,
        t: Float,
        short: Float,
        cx: Float,
        cy: Float,
        alpha: Float
    ) {
        val reveal = smoothStep(0.12f, 0.48f, t) * (1f - smoothStep(0.83f, 0.98f, t)) * alpha
        if (reveal <= 0f) return

        for (i in -6..6) {
            val normalized = i / 6f
            val sway = sin(t * 5.4f + i * 0.63f) * short * 0.007f
            val x = cx + normalized * short * 0.35f + sway
            val depth = 1f - abs(normalized) * 0.34f
            val rise = short * (0.070f + depth * 0.12f) * (0.72f + 0.28f * sin(t * 4.2f + i))
            val baseY = cy + short * 0.12f
            val topY = baseY - rise
            val half = short * (0.007f + depth * 0.005f)

            paint.color = Color.argb((28 * reveal).toInt(), 38, 103, 255)
            canvas.drawRect(x - half, topY, x + half, baseY, paint)
            linePaint.color = Color.argb((145 * reveal).toInt(), 77, 157, 255)
            canvas.drawLine(x - half, topY, x - half, baseY, linePaint)
            canvas.drawLine(x + half, topY, x + half, baseY, linePaint)
            canvas.drawLine(x - half, topY, x + half, topY, linePaint)
        }

        finePaint.color = Color.argb((55 * reveal).toInt(), 78, 138, 245)
        for (ring in 1..5) {
            val y = cy + short * (0.13f + ring * 0.032f)
            val half = short * (0.08f + ring * 0.062f)
            canvas.drawLine(cx - half, y, cx + half, y, finePaint)
        }
    }

    private fun drawParticles(
        canvas: Canvas,
        t: Float,
        short: Float,
        cx: Float,
        cy: Float,
        alpha: Float
    ) {
        val reveal = smoothStep(0.04f, 0.36f, t) * alpha
        paint.style = Paint.Style.FILL
        for (i in 0 until 42) {
            val seed = i * 0.7548777f
            val angle = seed * 6.28318f + t * (0.8f + (i % 5) * 0.12f)
            val radius = short * (0.055f + (i % 11) * 0.027f) * (0.64f + t * 0.58f)
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle * 0.83f) * radius * 0.56f
            val twinkle = 0.55f + 0.45f * sin(t * 13f + i * 1.31f)
            paint.color = Color.argb((96 * reveal * twinkle).toInt().coerceIn(0, 255), 95, 174, 255)
            canvas.drawCircle(x, y, short * (0.0024f + (i % 3) * 0.0007f), paint)
        }
    }

    private fun drawCorePulse(canvas: Canvas, t: Float, short: Float, cx: Float, cy: Float) {
        val reveal = smoothStep(0.18f, 0.52f, t) * (1f - smoothStep(0.74f, 0.90f, t))
        if (reveal <= 0f) return
        val pulse = 0.72f + 0.28f * sin(t * 16f)
        val radius = short * (0.018f + 0.012f * pulse)
        paint.shader = RadialGradient(
            cx,
            cy - short * 0.01f,
            radius * 4.6f,
            intArrayOf(
                Color.argb((235 * reveal).toInt(), 225, 244, 255),
                Color.argb((145 * reveal).toInt(), 76, 162, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.26f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy - short * 0.01f, radius * 4.6f, paint)
        paint.shader = null
    }

    private fun drawBrand(canvas: Canvas, t: Float, short: Float, cx: Float) {
        val reveal = smoothStep(0.46f, 0.69f, t)
        val fade = 1f - smoothStep(0.88f, 0.98f, t)
        val alpha = (reveal * fade).coerceIn(0f, 1f)
        if (alpha <= 0f) return

        textPaint.alpha = (248 * alpha).toInt().coerceIn(0, 255)
        textPaint.textSize = short * 0.052f
        textPaint.isFakeBoldText = true
        canvas.drawText("OMNICORE", cx, height * 0.73f, textPaint)

        textPaint.alpha = (195 * alpha).toInt().coerceIn(0, 255)
        textPaint.textSize = short * 0.019f
        textPaint.isFakeBoldText = false
        canvas.drawText("CLASSIC PS2 MODE", cx, height * 0.775f, textPaint)

        val bootReveal = smoothStep(0.70f, 0.80f, t)
        textPaint.alpha = (145 * alpha * bootReveal).toInt().coerceIn(0, 255)
        textPaint.textSize = short * 0.014f
        canvas.drawText("INITIALIZING RUNTIME", cx, height * 0.815f, textPaint)

        textPaint.isFakeBoldText = true
        textPaint.alpha = 255
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value >= edge1) 1f else 0f
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        const val DURATION_MS = 3250L
    }
}
