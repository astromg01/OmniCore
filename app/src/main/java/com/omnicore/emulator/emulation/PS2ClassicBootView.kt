package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Original OmniCore PS2-era boot pre-roll.
 *
 * No Sony/PlayStation logo, BIOS animation, sound or proprietary asset is used.
 * The view only evokes the period with abstract blue geometry and then hands
 * control back to the real emulator boot path.
 */
class PS2ClassicBootView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
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
        val fadeIn = (t / 0.22f).coerceIn(0f, 1f)
        val fadeOut = ((1f - t) / 0.18f).coerceIn(0f, 1f)
        val alpha = min(fadeIn, fadeOut)

        paint.shader = RadialGradient(
            width * 0.5f,
            height * 0.52f,
            min(width, height) * (0.28f + t * 0.10f),
            intArrayOf(
                Color.argb((84 * alpha).toInt(), 20, 78, 190),
                Color.argb((28 * alpha).toInt(), 8, 24, 72),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val cx = width * 0.5f
        val cy = height * 0.50f
        val short = min(width, height).toFloat()
        val phase = t * 1.7f
        linePaint.color = Color.argb((165 * alpha).toInt(), 72, 138, 255)

        // Abstract vertical gates — intentionally not the PlayStation logo.
        for (i in -3..3) {
            val depth = (i + 3) / 6f
            val x = cx + i * short * 0.075f + sin((phase + i) * 1.4f) * short * 0.010f
            val base = short * (0.085f + 0.035f * (1f - depth))
            val top = cy - base * (1.0f + 0.45f * sin(phase * 2f + i))
            val bottom = cy + base
            canvas.drawLine(x, top, x, bottom, linePaint)
            canvas.drawLine(x - short * 0.018f, bottom, x + short * 0.018f, bottom, linePaint)
        }

        paint.style = Paint.Style.FILL
        for (i in 0 until 22) {
            val angle = i * 0.91f + phase * 1.9f
            val radius = short * (0.10f + (i % 7) * 0.025f) * (0.85f + t * 0.25f)
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle * 0.82f) * radius * 0.48f
            paint.color = Color.argb((90 * alpha).toInt(), 84, 160, 255)
            canvas.drawCircle(x, y, short * 0.0038f, paint)
        }

        textPaint.alpha = (230 * alpha).toInt().coerceIn(0, 255)
        textPaint.textSize = short * 0.041f
        canvas.drawText("OMNICORE", cx, height * 0.78f, textPaint)
        textPaint.alpha = (175 * alpha).toInt().coerceIn(0, 255)
        textPaint.textSize = short * 0.020f
        textPaint.isFakeBoldText = false
        canvas.drawText("PLAYSTATION 2 RUNTIME", cx, height * 0.825f, textPaint)
        textPaint.isFakeBoldText = true
        textPaint.alpha = 255
    }

    companion object {
        const val DURATION_MS = 1850L
    }
}
