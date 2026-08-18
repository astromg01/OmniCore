package com.omnicore.emulator.emulation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Tiny original retro-platformer star used only while the N64 runtime is booting. */
class N64RetroStarView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 216, 48) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(231, 151, 24)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.25f
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 31, 28) }
    private val path = Path()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val pulse = 0.94f + 0.06f * sin(phase * PI * 2.0).toFloat()
        val outer = minOf(width, height) * 0.43f * pulse
        val inner = outer * 0.46f
        val rotation = -PI / 2.0 + sin(phase * PI * 2.0).toFloat() * 0.07f

        path.reset()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = rotation + i * PI / 5.0
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)

        val eyeY = cy - outer * 0.05f
        val eyeW = outer * 0.09f
        val eyeH = outer * 0.22f
        canvas.drawOval(cx - outer * 0.23f - eyeW, eyeY - eyeH, cx - outer * 0.23f + eyeW, eyeY + eyeH, eye)
        canvas.drawOval(cx + outer * 0.23f - eyeW, eyeY - eyeH, cx + outer * 0.23f + eyeW, eyeY + eyeH, eye)
    }
}
