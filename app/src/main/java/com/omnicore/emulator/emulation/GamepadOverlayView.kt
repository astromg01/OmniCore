package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.omnicore.emulator.core.nativebridge.NativeBridge
import kotlin.math.hypot
import kotlin.math.min

class GamepadOverlayView(context: Context) : View(context) {
    private data class Region(
        val id: Int,
        val label: String,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val wide: Boolean = false
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var regions: List<Region> = emptyList()
    private var pressed: Set<Int> = emptySet()

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val base = min(w, h).toFloat()
        val r = base * 0.068f
        val small = base * 0.052f
        textPaint.textSize = base * 0.038f

        regions = listOf(
            // D-pad
            Region(4, "▲", w * 0.17f, h * 0.57f, r),
            Region(5, "▼", w * 0.17f, h * 0.83f, r),
            Region(6, "◀", w * 0.10f, h * 0.70f, r),
            Region(7, "▶", w * 0.24f, h * 0.70f, r),

            // PlayStation face buttons mapped to the libretro RetroPad.
            Region(9, "△", w * 0.83f, h * 0.53f, r),
            Region(0, "×", w * 0.83f, h * 0.82f, r),
            Region(1, "□", w * 0.76f, h * 0.68f, r),
            Region(8, "○", w * 0.90f, h * 0.68f, r),

            // Center
            Region(2, "SELECT", w * 0.45f, h * 0.82f, small, wide = true),
            Region(3, "START", w * 0.56f, h * 0.82f, small, wide = true),

            // Shoulders
            Region(10, "L1", w * 0.09f, h * 0.14f, small, wide = true),
            Region(12, "L2", w * 0.22f, h * 0.14f, small, wide = true),
            Region(13, "R2", w * 0.78f, h * 0.14f, small, wide = true),
            Region(11, "R1", w * 0.91f, h * 0.14f, small, wide = true)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        regions.forEach { region ->
            val active = region.id in pressed
            fillPaint.color = if (active) {
                Color.argb(135, 255, 255, 255)
            } else {
                Color.argb(72, 255, 255, 255)
            }
            if (region.wide) {
                val halfW = region.radius * 1.55f
                val halfH = region.radius * 0.72f
                val rect = RectF(region.cx - halfW, region.cy - halfH, region.cx + halfW, region.cy + halfH)
                canvas.drawRoundRect(rect, halfH, halfH, fillPaint)
                canvas.drawRoundRect(rect, halfH, halfH, strokePaint)
            } else {
                canvas.drawCircle(region.cx, region.cy, region.radius, fillPaint)
                canvas.drawCircle(region.cx, region.cy, region.radius, strokePaint)
            }
            val baseline = region.cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(region.label, region.cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val excludedIndex = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            MotionEvent.ACTION_CANCEL -> -2
            else -> -1
        }

        val next = if (excludedIndex == -2) {
            emptySet()
        } else {
            buildSet {
                for (pointerIndex in 0 until event.pointerCount) {
                    if (pointerIndex == excludedIndex) continue
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    regions.forEach { region ->
                        if (contains(region, x, y)) add(region.id)
                    }
                }
            }
        }

        updatePressed(next)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun releaseAll() {
        updatePressed(emptySet())
    }

    private fun contains(region: Region, x: Float, y: Float): Boolean {
        return if (region.wide) {
            val halfW = region.radius * 1.7f
            val halfH = region.radius * 0.9f
            x in (region.cx - halfW)..(region.cx + halfW) &&
                y in (region.cy - halfH)..(region.cy + halfH)
        } else {
            hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) <= region.radius
        }
    }

    private fun updatePressed(next: Set<Int>) {
        if (next == pressed) return
        (pressed - next).forEach { NativeBridge.setButton(it, false) }
        (next - pressed).forEach { NativeBridge.setButton(it, true) }
        pressed = next
        invalidate()
    }
}
