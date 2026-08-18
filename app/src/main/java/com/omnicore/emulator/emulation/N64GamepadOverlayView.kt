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
 * artwork. The layout can be edited directly on top of the running game.
 */
class N64GamepadOverlayView(
    context: Context,
    private val config: N64InputSettings.Config
) : View(context) {

    private data class ButtonRegion(
        val key: String,
        val id: Int,
        val label: String,
        val accent: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 1f
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.25f
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 118, 226, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.6f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val editorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        textSize = resources.displayMetrics.scaledDensity * 12f
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

    private var editMode = false
    private var editPointerId = INVALID_POINTER
    private var editTargetKey: String? = null
    private var selectedKey: String? = null

    // Alternate Mupen mapping: A=B(0), B=Y(1), L=SELECT(2), Start=3,
    // C-down=A(8), C-up=X(9), C-left=L(10), C-right=R(11), Z=L2(12), R=R2(13).
    private val buttons = arrayOf(
        ButtonRegion("a", 0, "A", accent = true),
        ButtonRegion("b", 1, "B"),
        ButtonRegion("c_down", 8, "C↓", accent = true),
        ButtonRegion("c_up", 9, "C↑", accent = true),
        ButtonRegion("c_left", 10, "C←", accent = true),
        ButtonRegion("c_right", 11, "C→", accent = true),
        ButtonRegion("z", 12, "Z"),
        ButtonRegion("l", 2, "L"),
        ButtonRegion("r", 13, "R"),
        ButtonRegion("start", 3, "START"),
        ButtonRegion("dpad_up", 4, "↑"),
        ButtonRegion("dpad_down", 5, "↓"),
        ButtonRegion("dpad_left", 6, "←"),
        ButtonRegion("dpad_right", 7, "→")
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
        rebuildLayout(w, h)
    }

    private fun rebuildLayout(w: Int = width, h: Int = height) {
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

        val analogDefault = when (config.overlayPreset) {
            N64InputSettings.OverlayPreset.CLEAN -> 0.135f to 0.76f
            N64InputSettings.OverlayPreset.STANDARD -> 0.17f to 0.74f
            N64InputSettings.OverlayPreset.COMPACT -> 0.105f to 0.80f
        }
        val analogPos = N64InputSettings.resolveControlPosition(
            context, ANALOG_KEY, analogDefault.first, analogDefault.second
        )
        analogCenterX = w * analogPos.x
        analogCenterY = h * analogPos.y
        analogRadius = short * 0.108f * scale * N64InputSettings.resolveControlScale(context, ANALOG_KEY)

        val aX = when (config.overlayPreset) {
            N64InputSettings.OverlayPreset.CLEAN -> 0.90f
            N64InputSettings.OverlayPreset.STANDARD -> 0.87f
            N64InputSettings.OverlayPreset.COMPACT -> 0.93f
        }
        place("a", aX, 0.76f, standard * 1.08f)
        place("b", aX - 0.10f, 0.82f, standard)

        val cNormX = 0.84f
        val cNormY = 0.47f
        val cSpacingX = cRadius * 1.48f / w
        val cSpacingY = cRadius * 1.48f / h
        place("c_down", cNormX, cNormY + cSpacingY, cRadius)
        place("c_up", cNormX, cNormY - cSpacingY, cRadius)
        place("c_left", cNormX - cSpacingX, cNormY, cRadius)
        place("c_right", cNormX + cSpacingX, cNormY, cRadius)

        place("z", 0.39f, 0.88f, standard * 0.86f)
        place("l", 0.075f, 0.11f, small)
        place("r", 0.925f, 0.11f, small)
        place("start", 0.52f, 0.91f, small * 1.03f)

        val dNormX = 0.275f
        val dNormY = 0.70f
        val dRadius = small * 0.78f
        val dSpacingX = dRadius * 1.42f / w
        val dSpacingY = dRadius * 1.42f / h
        place("dpad_up", dNormX, dNormY - dSpacingY, dRadius)
        place("dpad_down", dNormX, dNormY + dSpacingY, dRadius)
        place("dpad_left", dNormX - dSpacingX, dNormY, dRadius)
        place("dpad_right", dNormX + dSpacingX, dNormY, dRadius)
        postInvalidateOnAnimation()
    }

    private fun place(key: String, defaultX: Float, defaultY: Float, baseRadius: Float) {
        val button = buttons.firstOrNull { it.key == key } ?: return
        val pos = N64InputSettings.resolveControlPosition(context, key, defaultX, defaultY)
        button.x = width * pos.x
        button.y = height * pos.y
        button.radius = baseRadius * N64InputSettings.resolveControlScale(context, key)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val opacity = effectiveOpacity()
        drawAnalog(canvas, opacity)
        for (button in buttons) {
            if (!config.showDpad && button.id in 4..7 && !editMode) continue
            drawButton(canvas, button, opacity)
        }
        if (editMode) drawEditor(canvas)
    }

    private fun effectiveOpacity(): Float {
        if (editMode) return 0.96f
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
        if (editMode && selectedKey == ANALOG_KEY) {
            canvas.drawCircle(analogCenterX, analogCenterY, analogRadius * 1.12f, editPaint)
        }
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
        if (editMode && selectedKey == button.key) {
            canvas.drawCircle(button.x, button.y, button.radius * 1.18f, editPaint)
        }
        textPaint.alpha = scaledAlpha(if (active) 255 else 215, opacity)
        textPaint.textSize = (button.radius * if (button.label.length > 2) 0.44f else 0.63f).coerceAtLeast(9f)
        val baseline = button.y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(button.label, button.x, baseline, textPaint)
        textPaint.alpha = 255
    }

    private fun drawEditor(canvas: Canvas) {
        fillPaint.color = Color.argb(150, 8, 11, 18)
        canvas.drawRoundRect(18f, 18f, min(width * 0.58f, 560f), 86f, 18f, 18f, fillPaint)
        canvas.drawText("EDITOR N64 • arraste um controle", 34f, 47f, editorTextPaint)
        val selected = selectedControlLabel() ?: "toque em um controle para selecionar"
        editorTextPaint.isFakeBoldText = false
        canvas.drawText("Selecionado: $selected", 34f, 72f, editorTextPaint)
        editorTextPaint.isFakeBoldText = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return onEditorTouch(event)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun onEditorTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (editPointerId != INVALID_POINTER) return true
                val index = event.actionIndex
                val key = findEditTarget(event.getX(index), event.getY(index)) ?: return true
                editPointerId = event.getPointerId(index)
                editTargetKey = key
                selectedKey = key
                haptic()
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                val pointer = editPointerId
                if (pointer == INVALID_POINTER) return true
                val index = event.findPointerIndex(pointer)
                if (index < 0) return true
                moveEditTarget(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == editPointerId) finishEditDrag()
            }
            MotionEvent.ACTION_CANCEL -> finishEditDrag()
        }
        return true
    }

    private fun findEditTarget(x: Float, y: Float): String? {
        var bestKey: String? = null
        var bestDistance = Float.MAX_VALUE
        val analogDistance = hypot(x - analogCenterX, y - analogCenterY)
        if (analogDistance <= analogRadius * 1.55f) {
            bestKey = ANALOG_KEY
            bestDistance = analogDistance
        }
        for (button in buttons) {
            val distance = hypot(x - button.x, y - button.y)
            if (distance <= button.radius * 1.65f && distance < bestDistance) {
                bestKey = button.key
                bestDistance = distance
            }
        }
        return bestKey
    }

    private fun moveEditTarget(x: Float, y: Float) {
        val key = editTargetKey ?: return
        val clampedX = x.coerceIn(width * 0.035f, width * 0.965f)
        val clampedY = y.coerceIn(height * 0.045f, height * 0.955f)
        if (key == ANALOG_KEY) {
            analogCenterX = clampedX
            analogCenterY = clampedY
        } else {
            buttons.firstOrNull { it.key == key }?.let {
                it.x = clampedX
                it.y = clampedY
            }
        }
        postInvalidateOnAnimation()
    }

    private fun finishEditDrag() {
        val key = editTargetKey
        if (key != null && width > 0 && height > 0) {
            if (key == ANALOG_KEY) {
                N64InputSettings.saveControlPosition(context, key, analogCenterX / width, analogCenterY / height)
            } else {
                buttons.firstOrNull { it.key == key }?.let {
                    N64InputSettings.saveControlPosition(context, key, it.x / width, it.y / height)
                }
            }
        }
        editPointerId = INVALID_POINTER
        editTargetKey = null
        postInvalidateOnAnimation()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        releaseAll()
        editMode = enabled
        editPointerId = INVALID_POINTER
        editTargetKey = null
        if (!enabled) selectedKey = null
        postInvalidateOnAnimation()
    }

    fun isEditMode(): Boolean = editMode

    fun selectedControlLabel(): String? {
        val key = selectedKey ?: return null
        if (key == ANALOG_KEY) return "Analógico"
        return buttons.firstOrNull { it.key == key }?.label
    }

    fun adjustSelectedScale(delta: Float): Boolean {
        val key = selectedKey ?: return false
        val current = N64InputSettings.resolveControlScale(context, key)
        N64InputSettings.saveControlScale(context, key, current + delta)
        rebuildLayout()
        return true
    }

    fun resetEditedLayout() {
        N64InputSettings.resetTouchLayout(context)
        selectedKey = null
        rebuildLayout()
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
        private const val ANALOG_KEY = "analog"
        private const val INVALID_POINTER = -1
        private const val ACTIVE_HOLD_MS = 1050L
    }
}
