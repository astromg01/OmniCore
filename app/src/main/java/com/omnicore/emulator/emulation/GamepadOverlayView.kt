package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.settings.InputSettings
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

class GamepadOverlayView(context: Context) : View(context) {
    private data class Region(
        val id: Int,
        val label: String,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val wide: Boolean = false
    )

    private val config = InputSettings.resolve(context)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 235, 238, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * resources.displayMetrics.density
    }
    private val analogBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 190, 185, 255)
        style = Paint.Style.FILL
    }
    private val analogRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 205, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val analogKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 225, 222, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var regions: List<Region> = emptyList()
    private var regionPressed: Set<Int> = emptySet()
    private var analogDpadPressed: Set<Int> = emptySet()
    private var committedButtons: Set<Int> = emptySet()
    private var analogCx = 0f
    private var analogCy = 0f
    private var analogRadius = 0f
    private var analogKnobX = 0f
    private var analogKnobY = 0f
    private var analogPointerId = -1

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        alpha = config.touchOpacity
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val base = min(w, h).toFloat()
        val scale = config.touchScale
        val r = base * 0.060f * scale
        val small = base * 0.048f * scale
        val dpadR = base * 0.048f * scale
        textPaint.textSize = base * 0.032f * scale

        analogCx = w * 0.145f
        analogCy = h * 0.73f
        analogRadius = base * 0.105f * scale
        analogKnobX = analogCx
        analogKnobY = analogCy

        val dpx = w * 0.315f
        val dpy = h * 0.72f
        val dStep = base * 0.082f * scale

        regions = listOf(
            Region(4, "▲", dpx, dpy - dStep, dpadR),
            Region(5, "▼", dpx, dpy + dStep, dpadR),
            Region(6, "◀", dpx - dStep, dpy, dpadR),
            Region(7, "▶", dpx + dStep, dpy, dpadR),
            Region(9, "△", w * 0.845f, h * 0.55f, r),
            Region(0, "×", w * 0.845f, h * 0.83f, r),
            Region(1, "□", w * 0.775f, h * 0.69f, r),
            Region(8, "○", w * 0.915f, h * 0.69f, r),
            Region(2, "SELECT", w * 0.465f, h * 0.84f, small, wide = true),
            Region(3, "START", w * 0.565f, h * 0.84f, small, wide = true),
            Region(10, "L1", w * 0.095f, h * 0.13f, small, wide = true),
            Region(12, "L2", w * 0.215f, h * 0.13f, small, wide = true),
            Region(13, "R2", w * 0.785f, h * 0.13f, small, wide = true),
            Region(11, "R1", w * 0.905f, h * 0.13f, small, wide = true)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(analogCx, analogCy, analogRadius, analogBasePaint)
        canvas.drawCircle(analogCx, analogCy, analogRadius, analogRingPaint)
        canvas.drawCircle(analogKnobX, analogKnobY, analogRadius * 0.44f, analogKnobPaint)
        textPaint.textSize = min(width, height) * 0.025f * config.touchScale
        canvas.drawText("L", analogCx, analogCy - analogRadius - textPaint.textSize * 0.35f, textPaint)

        textPaint.textSize = min(width, height) * 0.032f * config.touchScale
        regions.forEach { region ->
            val active = region.id in committedButtons
            fillPaint.color = if (active) Color.argb(145, 218, 214, 255) else Color.argb(62, 235, 238, 255)
            if (region.wide) {
                val halfW = region.radius * 1.60f
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (analogPointerId == -1 && insideAnalog(x, y, 1.25f)) {
                    analogPointerId = event.getPointerId(index)
                    if (config.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == analogPointerId) {
                    analogPointerId = -1
                    updateAnalog(analogCx, analogCy)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                analogPointerId = -1
                updateAnalog(analogCx, analogCy)
            }
        }

        if (analogPointerId != -1) {
            val index = event.findPointerIndex(analogPointerId)
            if (index >= 0 && !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == analogPointerId)) {
                updateAnalog(event.getX(index), event.getY(index))
            }
        }

        val excludedUpIndex = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            else -> -1
        }
        val next = if (event.actionMasked == MotionEvent.ACTION_CANCEL) emptySet() else buildSet {
            for (pointerIndex in 0 until event.pointerCount) {
                if (pointerIndex == excludedUpIndex) continue
                if (event.getPointerId(pointerIndex) == analogPointerId) continue
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                regions.forEach { region -> if (contains(region, x, y)) add(region.id) }
            }
        }
        if (next != regionPressed && config.haptics && (next - regionPressed).isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        regionPressed = next
        commitButtons()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun releaseAll() {
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
        commitButtons()
        analogPointerId = -1
        updateAnalog(analogCx, analogCy)
    }

    private fun insideAnalog(x: Float, y: Float, scale: Float): Boolean =
        hypot((x - analogCx).toDouble(), (y - analogCy).toDouble()) <= analogRadius * scale

    private fun updateAnalog(x: Float, y: Float) {
        if (analogRadius <= 0f) return
        var dx = (x - analogCx) / analogRadius
        var dy = (y - analogCy) / analogRadius
        val magnitude = sqrt(dx * dx + dy * dy)
        if (magnitude > 1f) {
            dx /= magnitude
            dy /= magnitude
        }
        val deadzone = 0.11f
        if (magnitude < deadzone) {
            dx = 0f
            dy = 0f
        } else if (magnitude > 0f) {
            val remapped = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            val remapScale = remapped / magnitude.coerceAtLeast(0.0001f)
            dx *= remapScale
            dy *= remapScale
        }

        analogKnobX = analogCx + dx * analogRadius * 0.72f
        analogKnobY = analogCy + dy * analogRadius * 0.72f

        when (config.analogMode) {
            InputSettings.AnalogMode.NATIVE -> {
                NativeBridge.setAnalog(0, dx, dy)
                analogDpadPressed = emptySet()
            }
            InputSettings.AnalogMode.DPAD -> {
                NativeBridge.setAnalog(0, 0f, 0f)
                analogDpadPressed = dpadProjection(dx, dy)
            }
            InputSettings.AnalogMode.SMART -> {
                NativeBridge.setAnalog(0, dx, dy)
                analogDpadPressed = dpadProjection(dx, dy)
            }
        }
        commitButtons()
        invalidate()
    }

    private fun dpadProjection(x: Float, y: Float): Set<Int> = buildSet {
        val threshold = 0.42f
        if (y <= -threshold) add(4)
        if (y >= threshold) add(5)
        if (x <= -threshold) add(6)
        if (x >= threshold) add(7)
    }

    private fun contains(region: Region, x: Float, y: Float): Boolean {
        return if (region.wide) {
            val halfW = region.radius * 1.75f
            val halfH = region.radius * 0.92f
            x in (region.cx - halfW)..(region.cx + halfW) && y in (region.cy - halfH)..(region.cy + halfH)
        } else {
            hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) <= region.radius * 1.10f
        }
    }

    private fun commitButtons() {
        val next = regionPressed + analogDpadPressed
        if (next == committedButtons) return
        (committedButtons - next).forEach { NativeBridge.setButton(it, false) }
        (next - committedButtons).forEach { NativeBridge.setButton(it, true) }
        committedButtons = next
        invalidate()
    }
}
