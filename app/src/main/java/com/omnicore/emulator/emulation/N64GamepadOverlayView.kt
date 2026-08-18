package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.omnicore.emulator.core.n64.N64NativeBridge
import com.omnicore.emulator.settings.N64InputSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Nintendo 64 touch controller focused on comfortable phone play.
 * Visual regions stay intentionally light while hit areas are larger than the
 * artwork. Pointer ownership remains stable and button fingers may slide between
 * neighbouring buttons without breaking another simultaneous pointer.
 */
class N64GamepadOverlayView(
    context: Context,
    private val config: N64InputSettings.Config
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
        strokeWidth = resources.displayMetrics.density * 1.25f
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
    private var activeUntilMs = 0L

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

    private val fadeRunnable = Runnable { postInvalidateOnAnimation() }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeRunnable)
        releaseAll()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val short = min(w, h).toFloat().coerceAtLeast(1f)
        val presetScale = when (config.overlayPreset) {
            N64InputSettings.OverlayPreset.CLEAN -> 0.90f
            N64InputSettings.OverlayPreset.STANDARD -> 1f
            N64InputSettings.OverlayPreset.COMPACT -> 0.80f
        }
        val scale = config.touchScale * presetScale
        val standard = short * 0.057f * scale
        val small = short * 0.044f * scale
        val cRadius = short * 0.041f * scale

        val analogPos = when (config.overlayPreset) {
            N64InputSettings.OverlayPreset.CLEAN -> 0.135f to 0.76f
            N64InputSettings.OverlayPreset.STANDARD -> 0.17f to 0.74f
            N64InputSettings.OverlayPreset.COMPACT -> 0.105f to 0.80f
        }
        analogCenterX = w * analogPos.first
        analogCenterY = h * analogPos.second
        analogRadius = short * 0.108f * scale

        val aX = when (config.overlayPreset) {
            N64InputSettings.OverlayPreset.CLEAN -> 0.90f
            N64InputSettings.OverlayPreset.STANDARD -> 0.87f
            N64InputSettings.OverlayPreset.COMPACT -> 0.93f
        }
        place(0, w * aX, h * 0.76f, standard * 1.08f)
        place(1, w * (aX - 0.10f), h * 0.82f, standard)

        val cx = w * 0.84f
        val cy = h * 0.47f
        place(8, cx, cy + cRadius * 1.48f, cRadius)
        place(9, cx, cy - cRadius * 1.48f, cRadius)
        place(10, cx - cRadius * 1.48f, cy, cRadius)
        place(11, cx + cRadius * 1.48f, cy, cRadius)

        place(12, w * 0.39f, h * 0.88f, standard * 0.86f)
        place(2, w * 0.075f, h * 0.11f, small)
        place(13, w * 0.925f, h * 0.11f, small)
        place(3, w * 0.52f, h * 0.91f, small * 1.03f)

        val dx = w * 0.275f
        val dy = h * 0.70f
        val dr = small * 0.78f
        place(4, dx, dy - dr * 1.42f, dr)
        place(5, dx, dy + dr * 1.42f, dr)
        place(6, dx - dr * 1.42f, dy, dr)
        place(7, dx + dr * 1.42f, dy, dr)
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
        val opacity = effectiveOpacity()
        drawAnalog(canvas, opacity)
        for (button in buttons) {
            if (!config.showDpad && button.id in 4..7) continue
            drawButton(canvas, button, opacity)
        }
    }

    private fun effectiveOpacity(): Float {
        val now = SystemClock.uptimeMillis()
        val active = analogPointerId != INVALID_POINTER || pointerButtons.size() > 0 || now <= activeUntilMs
        val idleFactor = if (config.dynamicOpacity && !active) 0.42f else 1f
        return (config.touchOpacity * idleFactor).coerceIn(0.12f, 1f)
    }

    private fun scaledAlpha(base: Int, opacity: Float): Int =
        (base * opacity).toInt().coerceIn(0, 255)

    private fun drawAnalog(canvas: Canvas, opacity: Float) {
        fillPaint.color = Color.argb(scaledAlpha(48, opacity), 226, 228, 242)
        strokePaint.color = Color.argb(scaledAlpha(132, opacity), 240, 241, 255)
        canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, fillPaint)
        canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, strokePaint)

        val knobRadius = analogRadius * 0.40f
        fillPaint.color = Color.argb(
            scaledAlpha(if (analogPointerId == INVALID_POINTER) 95 else 165, opacity),
            236, 237, 250
        )
        canvas.drawCircle(
            analogCenterX + analogX * analogRadius * 0.54f,
            analogCenterY + analogY * analogRadius * 0.54f,
            knobRadius,
            fillPaint
        )
    }

    private fun drawButton(canvas: Canvas, button: ButtonRegion, opacity: Float) {
        val active = pressed.getOrElse(button.id) { false }
        val fillBase = if (active) 156 else 34
        val strokeBase = if (active) 220 else 126
        fillPaint.color = if (button.accent) {
            Color.argb(scaledAlpha(fillBase, opacity), 226, 187, 48)
        } else {
            Color.argb(scaledAlpha(fillBase, opacity), 226, 228, 242)
        }
        strokePaint.color = if (button.accent) {
            Color.argb(scaledAlpha(strokeBase, opacity), 255, 222, 88)
        } else {
            Color.argb(scaledAlpha(strokeBase, opacity), 242, 243, 255)
        }
        canvas.drawCircle(button.x, button.y, button.radius, fillPaint)
        canvas.drawCircle(button.x, button.y, button.radius, strokePaint)
        textPaint.alpha = scaledAlpha(if (active) 255 else 215, opacity)
        textPaint.textSize = (button.radius * if (button.label.length > 2) 0.44f else 0.63f).coerceAtLeast(9f)
        val baseline = button.y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(button.label, button.x, baseline, textPaint)
        textPaint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        markActive()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                capture(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(index)
                    if (pointerId == analogPointerId) {
                        updateAnalog(event.getX(index), event.getY(index))
                    } else {
                        retargetButton(pointerId, event.getX(index), event.getY(index))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun markActive() {
        activeUntilMs = SystemClock.uptimeMillis() + ACTIVE_HOLD_MS
        removeCallbacks(fadeRunnable)
        postDelayed(fadeRunnable, ACTIVE_HOLD_MS + 40L)
        postInvalidateOnAnimation()
    }

    private fun capture(pointerId: Int, x: Float, y: Float) {
        if (analogPointerId == INVALID_POINTER && insideAnalog(x, y)) {
            analogPointerId = pointerId
            updateAnalog(x, y)
            haptic()
            return
        }
        val button = findButton(x, y, currentId = -1) ?: return
        if (button.id in pressed.indices && pressed[button.id]) return
        pointerButtons.put(pointerId, button.id)
        setPressed(button.id, true)
        haptic()
    }

    private fun retargetButton(pointerId: Int, x: Float, y: Float) {
        val index = pointerButtons.indexOfKey(pointerId)
        val currentId = if (index >= 0) pointerButtons.valueAt(index) else -1
        val candidate = findButton(x, y, currentId) ?: return
        if (candidate.id == currentId) return

        if (currentId >= 0) setPressed(currentId, false)
        pointerButtons.put(pointerId, candidate.id)
        setPressed(candidate.id, true)
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
        postInvalidateOnAnimation()
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

    private fun insideAnalog(x: Float, y: Float): Boolean =
        hypot(x - analogCenterX, y - analogCenterY) <= analogRadius * 1.42f

    private fun updateAnalog(x: Float, y: Float) {
        val dx = x - analogCenterX
        val dy = y - analogCenterY
        val length = hypot(dx, dy)
        val scale = if (length > analogRadius && length > 0f) analogRadius / length else 1f
        val nextX = (dx * scale / analogRadius).coerceIn(-1f, 1f)
        val nextY = (dy * scale / analogRadius).coerceIn(-1f, 1f)
        if (abs(nextX - analogX) < 0.0025f && abs(nextY - analogY) < 0.0025f) return
        analogX = nextX
        analogY = nextY
        N64NativeBridge.setAnalog(analogX, analogY)
        postInvalidateOnAnimation()
    }

    private fun findButton(x: Float, y: Float, currentId: Int): ButtonRegion? {
        var best: ButtonRegion? = null
        var bestDistance = Float.MAX_VALUE
        for (button in buttons) {
            if (!config.showDpad && button.id in 4..7) continue
            if (button.id in pressed.indices && pressed[button.id] && button.id != currentId) continue
            val distance = hypot(x - button.x, y - button.y)
            val multiplier = when {
                button.id in 8..11 -> 1.34f
                button.id in 4..7 -> 1.30f
                else -> 1.46f
            }
            if (distance <= button.radius * multiplier && distance < bestDistance) {
                best = button
                bestDistance = distance
            }
        }
        return best
    }

    private fun haptic() {
        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val INVALID_POINTER = -1
        private const val ACTIVE_HOLD_MS = 1050L
    }
}
