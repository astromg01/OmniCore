package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.omnicore.emulator.core.n64.N64NativeBridge
import kotlin.math.hypot
import kotlin.math.min

/**
 * Nintendo 64 touch controller. Pointer ownership is stable from DOWN to UP:
 * moving the analog stick never steals or releases a simultaneously held button.
 */
class N64GamepadOverlayView(
    context: Context,
    private val haptics: Boolean
) : View(context) {

    private data class ButtonRegion(
        val id: Int,
        val label: String,
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 1f,
        val accent: Boolean = false
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.35f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var analogX = 0f
    private var analogY = 0f
    private var analogCenterX = 0f
    private var analogCenterY = 0f
    private var analogRadius = 1f
    private var analogPointerId = INVALID_POINTER

    private val pointerButtons = SparseIntArray()
    private val pressed = BooleanArray(16)

    // Alternate Mupen mapping: A=B(0), B=Y(1), L=SELECT(2), Start=3,
    // C-down=A(8), C-up=X(9), C-left=L(10), C-right=R(11), Z=L2(12), R=R2(13).
    private val buttons = arrayOf(
        ButtonRegion(0, "A", accent = true),
        ButtonRegion(1, "B"),
        ButtonRegion(8, "C↓", accent = true),
        ButtonRegion(9, "C↑", accent = true),
        ButtonRegion(10, "C←", accent = true),
        ButtonRegion(11, "C→", accent = true),
        ButtonRegion(12, "Z"),
        ButtonRegion(2, "L"),
        ButtonRegion(13, "R"),
        ButtonRegion(3, "START"),
        ButtonRegion(4, "↑"),
        ButtonRegion(5, "↓"),
        ButtonRegion(6, "←"),
        ButtonRegion(7, "→")
    )

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val short = min(w, h).toFloat().coerceAtLeast(1f)
        val standard = short * 0.060f
        val small = short * 0.047f
        val cRadius = short * 0.043f

        analogCenterX = w * 0.18f
        analogCenterY = h * 0.73f
        analogRadius = short * 0.135f

        place(0, w * 0.86f, h * 0.72f, standard * 1.12f)
        place(1, w * 0.75f, h * 0.80f, standard)

        val cx = w * 0.84f
        val cy = h * 0.43f
        place(8, cx, cy + cRadius * 1.42f, cRadius)
        place(9, cx, cy - cRadius * 1.42f, cRadius)
        place(10, cx - cRadius * 1.42f, cy, cRadius)
        place(11, cx + cRadius * 1.42f, cy, cRadius)

        place(12, w * 0.33f, h * 0.84f, standard * 0.92f)
        place(2, w * 0.10f, h * 0.12f, small)
        place(13, w * 0.90f, h * 0.12f, small)
        place(3, w * 0.50f, h * 0.87f, small * 1.08f)

        val dx = w * 0.19f
        val dy = h * 0.43f
        val dr = small * 0.84f
        place(4, dx, dy - dr * 1.35f, dr)
        place(5, dx, dy + dr * 1.35f, dr)
        place(6, dx - dr * 1.35f, dy, dr)
        place(7, dx + dr * 1.35f, dy, dr)
    }

    private fun place(id: Int, x: Float, y: Float, radius: Float) {
        buttons.firstOrNull { it.id == id }?.let {
            it.x = x
            it.y = y
            it.radius = radius
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fillPaint.color = Color.argb(52, 226, 228, 242)
        strokePaint.color = Color.argb(115, 240, 241, 255)
        canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, fillPaint)
        canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, strokePaint)

        val knobRadius = analogRadius * 0.43f
        fillPaint.color = Color.argb(if (analogPointerId == INVALID_POINTER) 72 else 125, 236, 237, 250)
        canvas.drawCircle(
            analogCenterX + analogX * analogRadius * 0.52f,
            analogCenterY + analogY * analogRadius * 0.52f,
            knobRadius,
            fillPaint
        )

        for (button in buttons) drawButton(canvas, button)
    }

    private fun drawButton(canvas: Canvas, button: ButtonRegion) {
        val active = pressed.getOrElse(button.id) { false }
        val alpha = if (active) 150 else 55
        fillPaint.color = if (button.accent) {
            Color.argb(alpha, 226, 187, 48)
        } else {
            Color.argb(alpha, 226, 228, 242)
        }
        strokePaint.color = if (button.accent) {
            Color.argb(155, 255, 222, 88)
        } else {
            Color.argb(115, 242, 243, 255)
        }
        canvas.drawCircle(button.x, button.y, button.radius, fillPaint)
        canvas.drawCircle(button.x, button.y, button.radius, strokePaint)
        textPaint.textSize = (button.radius * if (button.label.length > 2) 0.46f else 0.65f).coerceAtLeast(9f)
        val baseline = button.y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(button.label, button.x, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                capture(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                if (analogPointerId != INVALID_POINTER) {
                    val index = event.findPointerIndex(analogPointerId)
                    if (index >= 0) updateAnalog(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun capture(pointerId: Int, x: Float, y: Float) {
        if (analogPointerId == INVALID_POINTER && insideAnalog(x, y)) {
            analogPointerId = pointerId
            updateAnalog(x, y)
            haptic()
            return
        }
        val button = findButton(x, y) ?: return
        if (button.id in pressed.indices && pressed[button.id]) return
        pointerButtons.put(pointerId, button.id)
        setPressed(button.id, true)
        haptic()
    }

    private fun release(pointerId: Int) {
        if (pointerId == analogPointerId) {
            analogPointerId = INVALID_POINTER
            analogX = 0f
            analogY = 0f
            N64NativeBridge.setAnalog(0f, 0f)
            postInvalidateOnAnimation()
            return
        }
        val index = pointerButtons.indexOfKey(pointerId)
        if (index >= 0) {
            val id = pointerButtons.valueAt(index)
            pointerButtons.removeAt(index)
            setPressed(id, false)
        }
    }

    fun releaseAll() {
        analogPointerId = INVALID_POINTER
        analogX = 0f
        analogY = 0f
        N64NativeBridge.setAnalog(0f, 0f)
        for (i in pressed.indices) {
            if (pressed[i]) N64NativeBridge.setButton(i, false)
            pressed[i] = false
        }
        pointerButtons.clear()
        postInvalidateOnAnimation()
    }

    private fun setPressed(id: Int, value: Boolean) {
        if (id !in pressed.indices || pressed[id] == value) return
        pressed[id] = value
        N64NativeBridge.setButton(id, value)
        postInvalidateOnAnimation()
    }

    private fun insideAnalog(x: Float, y: Float): Boolean {
        return hypot(x - analogCenterX, y - analogCenterY) <= analogRadius * 1.18f
    }

    private fun updateAnalog(x: Float, y: Float) {
        val dx = x - analogCenterX
        val dy = y - analogCenterY
        val length = hypot(dx, dy)
        val scale = if (length > analogRadius && length > 0f) analogRadius / length else 1f
        val nextX = (dx * scale / analogRadius).coerceIn(-1f, 1f)
        val nextY = (dy * scale / analogRadius).coerceIn(-1f, 1f)
        if (kotlin.math.abs(nextX - analogX) < 0.0025f && kotlin.math.abs(nextY - analogY) < 0.0025f) return
        analogX = nextX
        analogY = nextY
        N64NativeBridge.setAnalog(analogX, analogY)
        postInvalidateOnAnimation()
    }

    private fun findButton(x: Float, y: Float): ButtonRegion? {
        var best: ButtonRegion? = null
        var bestDistance = Float.MAX_VALUE
        for (button in buttons) {
            if (button.id in pressed.indices && pressed[button.id]) continue
            val distance = hypot(x - button.x, y - button.y)
            if (distance <= button.radius * 1.22f && distance < bestDistance) {
                best = button
                bestDistance = distance
            }
        }
        return best
    }

    private fun haptic() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val INVALID_POINTER = -1
    }
}
