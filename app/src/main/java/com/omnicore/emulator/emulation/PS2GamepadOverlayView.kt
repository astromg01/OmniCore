package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.omnicore.emulator.settings.PS2InputSettings
import com.virtualapplications.play.InputManagerConstants
import kotlin.math.hypot
import kotlin.math.min

/**
 * Clear OmniCore DualShock 2 touch surface.
 *
 * PS2 input remains isolated behind callbacks. The overlay intentionally uses
 * original OmniCore geometry instead of copying proprietary controller artwork.
 */
class PS2GamepadOverlayView(
    context: Context,
    private val config: PS2InputSettings.Config,
    private val onButton: (Int, Boolean) -> Unit,
    private val onAxis: (Int, Float) -> Unit
) : View(context) {
    private enum class Shape { CIRCLE, PILL, SHOULDER, DPAD }

    private data class ButtonRegion(
        val key: String,
        val id: Int,
        val label: String,
        val accent: Int = 0,
        val shape: Shape = Shape.CIRCLE,
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 1f
    )

    private data class Stick(
        val key: String,
        val axisX: Int,
        val axisY: Int,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var radius: Float = 1f,
        var valueX: Float = 0f,
        var valueY: Float = 0f,
        var pointerId: Int = INVALID_POINTER
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.35f
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 92, 220, 255)
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
    private val rect = RectF()

    private val leftStick = Stick(
        LEFT_STICK_KEY,
        InputManagerConstants.ANALOG_LEFT_X,
        InputManagerConstants.ANALOG_LEFT_Y
    )
    private val rightStick = Stick(
        RIGHT_STICK_KEY,
        InputManagerConstants.ANALOG_RIGHT_X,
        InputManagerConstants.ANALOG_RIGHT_Y
    )

    private val buttons = arrayOf(
        ButtonRegion("up", InputManagerConstants.BUTTON_UP, "↑", shape = Shape.DPAD),
        ButtonRegion("down", InputManagerConstants.BUTTON_DOWN, "↓", shape = Shape.DPAD),
        ButtonRegion("left", InputManagerConstants.BUTTON_LEFT, "←", shape = Shape.DPAD),
        ButtonRegion("right", InputManagerConstants.BUTTON_RIGHT, "→", shape = Shape.DPAD),
        ButtonRegion("select", InputManagerConstants.BUTTON_SELECT, "SELECT", shape = Shape.PILL),
        ButtonRegion("start", InputManagerConstants.BUTTON_START, "START", shape = Shape.PILL),
        ButtonRegion("square", InputManagerConstants.BUTTON_SQUARE, "□", ACCENT_PINK),
        ButtonRegion("triangle", InputManagerConstants.BUTTON_TRIANGLE, "△", ACCENT_GREEN),
        ButtonRegion("circle", InputManagerConstants.BUTTON_CIRCLE, "○", ACCENT_RED),
        ButtonRegion("cross", InputManagerConstants.BUTTON_CROSS, "✕", ACCENT_BLUE),
        ButtonRegion("l1", InputManagerConstants.BUTTON_L1, "L1", shape = Shape.SHOULDER),
        ButtonRegion("l2", InputManagerConstants.BUTTON_L2, "L2", shape = Shape.SHOULDER),
        ButtonRegion("l3", InputManagerConstants.BUTTON_L3, "L3", shape = Shape.PILL),
        ButtonRegion("r1", InputManagerConstants.BUTTON_R1, "R1", shape = Shape.SHOULDER),
        ButtonRegion("r2", InputManagerConstants.BUTTON_R2, "R2", shape = Shape.SHOULDER),
        ButtonRegion("r3", InputManagerConstants.BUTTON_R3, "R3", shape = Shape.PILL)
    )

    private val pointerButtons = SparseIntArray()
    private val pressed = BooleanArray(20)
    private var activeUntilMs = 0L

    private var editMode = false
    private var editPointerId = INVALID_POINTER
    private var editTargetKey: String? = null
    private var selectedKey: String? = null
    private val fadeRunnable = Runnable { postInvalidateOnAnimation() }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
            PS2InputSettings.OverlayPreset.CLEAN -> 0.96f
            PS2InputSettings.OverlayPreset.STANDARD -> 1f
            PS2InputSettings.OverlayPreset.COMPACT -> 0.82f
        }
        val scale = config.touchScale * presetScale
        val normal = short * 0.050f * scale
        val small = short * 0.037f * scale
        val face = short * 0.052f * scale
        val stickRadius = short * 0.091f * scale

        // Clear baseline: gameplay center stays open while thumbs naturally sit
        // in the lower left/right thirds.
        placeStick(leftStick, 0.19f, 0.78f, stickRadius)
        placeStick(rightStick, 0.68f, 0.78f, stickRadius)

        val dX = 0.13f
        val dY = 0.58f
        val dRadius = normal * 0.82f
        val dSpaceX = dRadius * 1.36f / w
        val dSpaceY = dRadius * 1.36f / h
        place("up", dX, dY - dSpaceY, dRadius)
        place("down", dX, dY + dSpaceY, dRadius)
        place("left", dX - dSpaceX, dY, dRadius)
        place("right", dX + dSpaceX, dY, dRadius)

        val fX = 0.87f
        val fY = 0.59f
        val fSpaceX = face * 1.43f / w
        val fSpaceY = face * 1.43f / h
        place("triangle", fX, fY - fSpaceY, face)
        place("cross", fX, fY + fSpaceY, face)
        place("square", fX - fSpaceX, fY, face)
        place("circle", fX + fSpaceX, fY, face)

        // Shoulder controls form a predictable upper rail rather than floating
        // circles, leaving the middle of the game image unobstructed.
        place("l2", 0.10f, 0.105f, small * 1.08f)
        place("l1", 0.25f, 0.105f, small * 1.08f)
        place("r1", 0.75f, 0.105f, small * 1.08f)
        place("r2", 0.90f, 0.105f, small * 1.08f)

        place("select", 0.455f, 0.86f, small * 0.88f)
        place("start", 0.545f, 0.86f, small * 0.88f)
        place("l3", 0.31f, 0.89f, small * 0.72f)
        place("r3", 0.79f, 0.89f, small * 0.72f)
        postInvalidateOnAnimation()
    }

    private fun placeStick(stick: Stick, defaultX: Float, defaultY: Float, baseRadius: Float) {
        val pos = PS2InputSettings.resolveControlPosition(context, stick.key, defaultX, defaultY)
        stick.centerX = width * pos.x
        stick.centerY = height * pos.y
        stick.radius = baseRadius * PS2InputSettings.resolveControlScale(context, stick.key)
    }

    private fun place(key: String, defaultX: Float, defaultY: Float, baseRadius: Float) {
        val button = buttons.firstOrNull { it.key == key } ?: return
        val pos = PS2InputSettings.resolveControlPosition(context, key, defaultX, defaultY)
        button.x = width * pos.x
        button.y = height * pos.y
        button.radius = baseRadius * PS2InputSettings.resolveControlScale(context, key)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val opacity = effectiveOpacity()
        drawStick(canvas, leftStick, opacity)
        if (config.showRightStick || editMode) drawStick(canvas, rightStick, opacity)
        for (button in buttons) {
            if (!config.showDpad && isDpad(button.id) && !editMode) continue
            if (!config.showL3R3 && isStickClick(button.id) && !editMode) continue
            drawButton(canvas, button, opacity)
        }
        if (editMode) drawEditor(canvas)
    }

    private fun effectiveOpacity(): Float {
        if (editMode) return 0.98f
        val now = SystemClock.uptimeMillis()
        val active = leftStick.pointerId != INVALID_POINTER || rightStick.pointerId != INVALID_POINTER ||
            pointerButtons.size() > 0 || now <= activeUntilMs
        // The old 40% idle fade made controls hard to read. Clear mode keeps
        // enough contrast to locate a button instantly without covering gameplay.
        val idleFactor = if (config.dynamicOpacity && !active) 0.62f else 1f
        return (config.touchOpacity * idleFactor).coerceIn(0.12f, 1f)
    }

    private fun drawStick(canvas: Canvas, stick: Stick, opacity: Float) {
        fillPaint.color = Color.argb(alpha(44, opacity), 215, 222, 240)
        strokePaint.color = Color.argb(alpha(155, opacity), 244, 247, 255)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, fillPaint)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, strokePaint)

        guidePaint.color = Color.argb(alpha(60, opacity), 235, 240, 255)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius * 0.56f, guidePaint)

        val knobRadius = stick.radius * 0.39f
        fillPaint.color = Color.argb(
            alpha(if (stick.pointerId == INVALID_POINTER) 112 else 185, opacity),
            235,
            240,
            252
        )
        strokePaint.color = Color.argb(alpha(175, opacity), 255, 255, 255)
        val knobX = stick.centerX + stick.valueX * stick.radius * 0.56f
        val knobY = stick.centerY + stick.valueY * stick.radius * 0.56f
        canvas.drawCircle(knobX, knobY, knobRadius, fillPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, strokePaint)

        if (editMode && selectedKey == stick.key) {
            canvas.drawCircle(stick.centerX, stick.centerY, stick.radius * 1.12f, editPaint)
        }
    }

    private fun drawButton(canvas: Canvas, button: ButtonRegion, opacity: Float) {
        val isPressed = pressed.getOrElse(button.id) { false }
        val rgb = accentRgb(button.accent)
        fillPaint.color = Color.argb(alpha(if (isPressed) 168 else 48, opacity), rgb[0], rgb[1], rgb[2])
        strokePaint.color = Color.argb(alpha(if (isPressed) 245 else 168, opacity), rgb[0], rgb[1], rgb[2])

        val radius = button.radius
        when (button.shape) {
            Shape.CIRCLE -> {
                canvas.drawCircle(button.x, button.y, radius, fillPaint)
                canvas.drawCircle(button.x, button.y, radius, strokePaint)
            }
            Shape.DPAD -> {
                val halfW = radius * 0.78f
                val halfH = radius * 0.70f
                rect.set(button.x - halfW, button.y - halfH, button.x + halfW, button.y + halfH)
                canvas.drawRoundRect(rect, radius * 0.23f, radius * 0.23f, fillPaint)
                canvas.drawRoundRect(rect, radius * 0.23f, radius * 0.23f, strokePaint)
            }
            Shape.PILL -> {
                val halfW = radius * 1.35f
                val halfH = radius * 0.58f
                rect.set(button.x - halfW, button.y - halfH, button.x + halfW, button.y + halfH)
                canvas.drawRoundRect(rect, halfH, halfH, fillPaint)
                canvas.drawRoundRect(rect, halfH, halfH, strokePaint)
            }
            Shape.SHOULDER -> {
                val halfW = radius * 1.58f
                val halfH = radius * 0.63f
                rect.set(button.x - halfW, button.y - halfH, button.x + halfW, button.y + halfH)
                canvas.drawRoundRect(rect, radius * 0.28f, radius * 0.28f, fillPaint)
                canvas.drawRoundRect(rect, radius * 0.28f, radius * 0.28f, strokePaint)
            }
        }

        if (editMode && selectedKey == button.key) {
            canvas.drawCircle(button.x, button.y, radius * 1.35f, editPaint)
        }

        textPaint.alpha = alpha(if (isPressed) 255 else 238, opacity)
        textPaint.textSize = when (button.shape) {
            Shape.CIRCLE, Shape.DPAD -> (radius * 0.67f).coerceAtLeast(10f)
            Shape.PILL, Shape.SHOULDER -> (radius * 0.43f).coerceAtLeast(9f)
        }
        val baseline = button.y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(button.label, button.x, baseline, textPaint)
        textPaint.alpha = 255
    }

    private fun drawEditor(canvas: Canvas) {
        fillPaint.color = Color.argb(178, 7, 11, 19)
        rect.set(18f, 18f, min(width * 0.64f, 620f), 92f)
        canvas.drawRoundRect(rect, 18f, 18f, fillPaint)
        canvas.drawText("EDITOR PS2 • arraste para posicionar", 34f, 49f, editorTextPaint)
        editorTextPaint.isFakeBoldText = false
        canvas.drawText("Selecionado: ${selectedKey ?: "toque em um controle"}", 34f, 76f, editorTextPaint)
        editorTextPaint.isFakeBoldText = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return onEditorTouch(event)
        markActive()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                capture(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    when (id) {
                        leftStick.pointerId -> updateStick(leftStick, event.getX(i), event.getY(i))
                        rightStick.pointerId -> updateStick(rightStick, event.getX(i), event.getY(i))
                        else -> retargetButton(id, event.getX(i), event.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun capture(pointerId: Int, x: Float, y: Float) {
        val leftDistance = hypot(x - leftStick.centerX, y - leftStick.centerY)
        if (leftDistance <= leftStick.radius * 1.30f && leftStick.pointerId == INVALID_POINTER) {
            leftStick.pointerId = pointerId
            haptic()
            updateStick(leftStick, x, y)
            return
        }
        if (config.showRightStick) {
            val rightDistance = hypot(x - rightStick.centerX, y - rightStick.centerY)
            if (rightDistance <= rightStick.radius * 1.30f && rightStick.pointerId == INVALID_POINTER) {
                rightStick.pointerId = pointerId
                haptic()
                updateStick(rightStick, x, y)
                return
            }
        }
        val button = findButton(x, y) ?: return
        pointerButtons.put(pointerId, button.id)
        setPressed(button.id, true)
        haptic()
    }

    private fun retargetButton(pointerId: Int, x: Float, y: Float) {
        val oldId = pointerButtons.get(pointerId, NO_BUTTON)
        val next = findButton(x, y)?.id ?: NO_BUTTON
        if (oldId == next) return
        if (oldId != NO_BUTTON && !buttonHeldByAnotherPointer(oldId, pointerId)) setPressed(oldId, false)
        if (next == NO_BUTTON) {
            pointerButtons.delete(pointerId)
        } else {
            pointerButtons.put(pointerId, next)
            setPressed(next, true)
        }
    }

    private fun findButton(x: Float, y: Float): ButtonRegion? {
        var best: ButtonRegion? = null
        var bestDistance = Float.MAX_VALUE
        for (button in buttons) {
            if (!config.showDpad && isDpad(button.id)) continue
            if (!config.showL3R3 && isStickClick(button.id)) continue
            val distance = hypot(x - button.x, y - button.y)
            val hitScale = when (button.shape) {
                Shape.SHOULDER -> 1.70f
                Shape.PILL -> 1.55f
                else -> 1.42f
            }
            if (distance <= button.radius * hitScale && distance < bestDistance) {
                best = button
                bestDistance = distance
            }
        }
        return best
    }

    private fun updateStick(stick: Stick, x: Float, y: Float) {
        val dx = (x - stick.centerX) / stick.radius.coerceAtLeast(1f)
        val dy = (y - stick.centerY) / stick.radius.coerceAtLeast(1f)
        val magnitude = hypot(dx, dy).coerceAtMost(1f)
        if (magnitude <= config.analogDeadzone) {
            setStick(stick, 0f, 0f)
            return
        }
        var normalized = ((magnitude - config.analogDeadzone) / (1f - config.analogDeadzone)).coerceIn(0f, 1f)
        if (config.precisionAnalog) normalized *= 0.80f + 0.20f * normalized
        normalized = (normalized * config.analogSensitivity).coerceIn(0f, 1f)
        val inv = if (magnitude > 0.0001f) normalized / magnitude else 0f
        setStick(stick, (dx * inv).coerceIn(-1f, 1f), (dy * inv).coerceIn(-1f, 1f))
    }

    private fun setStick(stick: Stick, x: Float, y: Float) {
        if (stick.valueX == x && stick.valueY == y) return
        stick.valueX = x
        stick.valueY = y
        onAxis(stick.axisX, x)
        onAxis(stick.axisY, y)
        postInvalidateOnAnimation()
    }

    private fun release(pointerId: Int) {
        when (pointerId) {
            leftStick.pointerId -> {
                leftStick.pointerId = INVALID_POINTER
                setStick(leftStick, 0f, 0f)
            }
            rightStick.pointerId -> {
                rightStick.pointerId = INVALID_POINTER
                setStick(rightStick, 0f, 0f)
            }
            else -> {
                val id = pointerButtons.get(pointerId, NO_BUTTON)
                pointerButtons.delete(pointerId)
                if (id != NO_BUTTON && !buttonHeldByAnotherPointer(id, pointerId)) setPressed(id, false)
            }
        }
        postInvalidateOnAnimation()
    }

    fun releaseAll() {
        pointerButtons.clear()
        leftStick.pointerId = INVALID_POINTER
        rightStick.pointerId = INVALID_POINTER
        setStick(leftStick, 0f, 0f)
        setStick(rightStick, 0f, 0f)
        for (id in pressed.indices) if (pressed[id]) setPressed(id, false)
        postInvalidateOnAnimation()
    }

    private fun setPressed(id: Int, down: Boolean) {
        if (id !in pressed.indices || pressed[id] == down) return
        pressed[id] = down
        onButton(id, down)
        postInvalidateOnAnimation()
    }

    private fun buttonHeldByAnotherPointer(buttonId: Int, excluding: Int): Boolean {
        for (i in 0 until pointerButtons.size()) {
            if (pointerButtons.keyAt(i) != excluding && pointerButtons.valueAt(i) == buttonId) return true
        }
        return false
    }

    private fun markActive() {
        activeUntilMs = SystemClock.uptimeMillis() + 950L
        removeCallbacks(fadeRunnable)
        postDelayed(fadeRunnable, 1000L)
        postInvalidateOnAnimation()
    }

    private fun haptic() {
        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun isEditMode(): Boolean = editMode

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        releaseAll()
        editMode = enabled
        editPointerId = INVALID_POINTER
        editTargetKey = null
        if (!enabled) selectedKey = null
        postInvalidateOnAnimation()
    }

    fun adjustSelectedScale(delta: Float): Boolean {
        val key = selectedKey ?: return false
        val current = PS2InputSettings.resolveControlScale(context, key)
        PS2InputSettings.saveControlScale(context, key, current + delta)
        rebuildLayout()
        return true
    }

    fun resetEditedLayout() {
        PS2InputSettings.resetTouchLayout(context)
        selectedKey = null
        rebuildLayout()
    }

    private fun onEditorTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (editPointerId != INVALID_POINTER) return true
                val i = event.actionIndex
                val key = findEditTarget(event.getX(i), event.getY(i)) ?: return true
                editPointerId = event.getPointerId(i)
                editTargetKey = key
                selectedKey = key
                haptic()
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                val pointer = editPointerId
                if (pointer == INVALID_POINTER) return true
                val i = event.findPointerIndex(pointer)
                if (i >= 0) moveEditTarget(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == editPointerId) finishEditDrag()
            }
            MotionEvent.ACTION_CANCEL -> finishEditDrag()
        }
        return true
    }

    private fun findEditTarget(x: Float, y: Float): String? {
        var best: String? = null
        var distanceBest = Float.MAX_VALUE
        for (stick in arrayOf(leftStick, rightStick)) {
            val distance = hypot(x - stick.centerX, y - stick.centerY)
            if (distance <= stick.radius * 1.55f && distance < distanceBest) {
                best = stick.key
                distanceBest = distance
            }
        }
        for (button in buttons) {
            val distance = hypot(x - button.x, y - button.y)
            if (distance <= button.radius * 1.75f && distance < distanceBest) {
                best = button.key
                distanceBest = distance
            }
        }
        return best
    }

    private fun moveEditTarget(x: Float, y: Float) {
        val key = editTargetKey ?: return
        val cx = x.coerceIn(width * 0.03f, width * 0.97f)
        val cy = y.coerceIn(height * 0.04f, height * 0.96f)
        when (key) {
            LEFT_STICK_KEY -> {
                leftStick.centerX = cx
                leftStick.centerY = cy
            }
            RIGHT_STICK_KEY -> {
                rightStick.centerX = cx
                rightStick.centerY = cy
            }
            else -> buttons.firstOrNull { it.key == key }?.let {
                it.x = cx
                it.y = cy
            }
        }
        postInvalidateOnAnimation()
    }

    private fun finishEditDrag() {
        val key = editTargetKey
        if (key != null && width > 0 && height > 0) {
            val x: Float
            val y: Float
            when (key) {
                LEFT_STICK_KEY -> {
                    x = leftStick.centerX / width
                    y = leftStick.centerY / height
                }
                RIGHT_STICK_KEY -> {
                    x = rightStick.centerX / width
                    y = rightStick.centerY / height
                }
                else -> {
                    val button = buttons.firstOrNull { it.key == key } ?: return clearEditPointer()
                    x = button.x / width
                    y = button.y / height
                }
            }
            PS2InputSettings.saveControlPosition(context, key, x, y)
        }
        clearEditPointer()
    }

    private fun clearEditPointer() {
        editPointerId = INVALID_POINTER
        editTargetKey = null
        postInvalidateOnAnimation()
    }

    private fun isDpad(id: Int): Boolean = id in InputManagerConstants.BUTTON_UP..InputManagerConstants.BUTTON_RIGHT

    private fun isStickClick(id: Int): Boolean =
        id == InputManagerConstants.BUTTON_L3 || id == InputManagerConstants.BUTTON_R3

    private fun alpha(base: Int, opacity: Float): Int = (base * opacity).toInt().coerceIn(0, 255)

    private fun accentRgb(accent: Int): IntArray = when (accent) {
        ACCENT_PINK -> intArrayOf(255, 126, 212)
        ACCENT_GREEN -> intArrayOf(121, 235, 157)
        ACCENT_RED -> intArrayOf(255, 122, 122)
        ACCENT_BLUE -> intArrayOf(108, 188, 255)
        else -> intArrayOf(235, 240, 250)
    }

    companion object {
        private const val INVALID_POINTER = -1
        private const val NO_BUTTON = -1
        private const val LEFT_STICK_KEY = "left_stick"
        private const val RIGHT_STICK_KEY = "right_stick"
        private const val ACCENT_PINK = 1
        private const val ACCENT_GREEN = 2
        private const val ACCENT_RED = 3
        private const val ACCENT_BLUE = 4
    }
}
