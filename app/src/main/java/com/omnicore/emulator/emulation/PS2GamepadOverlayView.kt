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
import com.omnicore.emulator.settings.PS2InputSettings
import com.virtualapplications.play.InputManagerConstants
import kotlin.math.hypot
import kotlin.math.min

/**
 * OmniCore DualShock 2 touch surface.
 *
 * Touch semantics are PS2-native and are delivered through callbacks so this
 * view never depends directly on a third-party emulator implementation.
 */
class PS2GamepadOverlayView(
    context: Context,
    private val config: PS2InputSettings.Config,
    private val onButton: (Int, Boolean) -> Unit,
    private val onAxis: (Int, Float) -> Unit
) : View(context) {
    private data class ButtonRegion(
        val key: String,
        val id: Int,
        val label: String,
        val accent: Int = 0,
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
        strokeWidth = resources.displayMetrics.density * 1.25f
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 90, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
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
        ButtonRegion("up", InputManagerConstants.BUTTON_UP, "↑"),
        ButtonRegion("down", InputManagerConstants.BUTTON_DOWN, "↓"),
        ButtonRegion("left", InputManagerConstants.BUTTON_LEFT, "←"),
        ButtonRegion("right", InputManagerConstants.BUTTON_RIGHT, "→"),
        ButtonRegion("select", InputManagerConstants.BUTTON_SELECT, "SELECT"),
        ButtonRegion("start", InputManagerConstants.BUTTON_START, "START"),
        ButtonRegion("square", InputManagerConstants.BUTTON_SQUARE, "□", ACCENT_PINK),
        ButtonRegion("triangle", InputManagerConstants.BUTTON_TRIANGLE, "△", ACCENT_GREEN),
        ButtonRegion("circle", InputManagerConstants.BUTTON_CIRCLE, "○", ACCENT_RED),
        ButtonRegion("cross", InputManagerConstants.BUTTON_CROSS, "✕", ACCENT_BLUE),
        ButtonRegion("l1", InputManagerConstants.BUTTON_L1, "L1"),
        ButtonRegion("l2", InputManagerConstants.BUTTON_L2, "L2"),
        ButtonRegion("l3", InputManagerConstants.BUTTON_L3, "L3"),
        ButtonRegion("r1", InputManagerConstants.BUTTON_R1, "R1"),
        ButtonRegion("r2", InputManagerConstants.BUTTON_R2, "R2"),
        ButtonRegion("r3", InputManagerConstants.BUTTON_R3, "R3")
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
            PS2InputSettings.OverlayPreset.CLEAN -> 0.90f
            PS2InputSettings.OverlayPreset.STANDARD -> 1f
            PS2InputSettings.OverlayPreset.COMPACT -> 0.80f
        }
        val scale = config.touchScale * presetScale
        val normal = short * 0.052f * scale
        val small = short * 0.040f * scale
        val face = short * 0.052f * scale
        val stickRadius = short * 0.095f * scale

        placeStick(leftStick, 0.20f, 0.78f, stickRadius)
        placeStick(rightStick, 0.70f, 0.78f, stickRadius)

        val dX = 0.12f
        val dY = 0.54f
        val dRadius = normal * 0.78f
        val dSpaceX = dRadius * 1.45f / w
        val dSpaceY = dRadius * 1.45f / h
        place("up", dX, dY - dSpaceY, dRadius)
        place("down", dX, dY + dSpaceY, dRadius)
        place("left", dX - dSpaceX, dY, dRadius)
        place("right", dX + dSpaceX, dY, dRadius)

        val fX = 0.88f
        val fY = 0.57f
        val fSpaceX = face * 1.48f / w
        val fSpaceY = face * 1.48f / h
        place("triangle", fX, fY - fSpaceY, face)
        place("cross", fX, fY + fSpaceY, face)
        place("square", fX - fSpaceX, fY, face)
        place("circle", fX + fSpaceX, fY, face)

        place("l1", 0.12f, 0.10f, small * 1.14f)
        place("l2", 0.25f, 0.10f, small * 1.14f)
        place("r2", 0.75f, 0.10f, small * 1.14f)
        place("r1", 0.88f, 0.10f, small * 1.14f)
        place("select", 0.45f, 0.87f, small * 0.88f)
        place("start", 0.55f, 0.87f, small * 0.88f)
        place("l3", 0.32f, 0.88f, small * 0.78f)
        place("r3", 0.80f, 0.88f, small * 0.78f)
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
            if (!config.showDpad && button.id in InputManagerConstants.BUTTON_UP..InputManagerConstants.BUTTON_RIGHT && !editMode) continue
            if (!config.showL3R3 && (button.id == InputManagerConstants.BUTTON_L3 || button.id == InputManagerConstants.BUTTON_R3) && !editMode) continue
            drawButton(canvas, button, opacity)
        }
        if (editMode) drawEditor(canvas)
    }

    private fun effectiveOpacity(): Float {
        if (editMode) return 0.97f
        val now = SystemClock.uptimeMillis()
        val active = leftStick.pointerId != INVALID_POINTER || rightStick.pointerId != INVALID_POINTER ||
            pointerButtons.size() > 0 || now <= activeUntilMs
        val idleFactor = if (config.dynamicOpacity && !active) 0.40f else 1f
        return (config.touchOpacity * idleFactor).coerceIn(0.10f, 1f)
    }

    private fun drawStick(canvas: Canvas, stick: Stick, opacity: Float) {
        fillPaint.color = Color.argb(alpha(42, opacity), 222, 226, 242)
        strokePaint.color = Color.argb(alpha(128, opacity), 244, 246, 255)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, fillPaint)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, strokePaint)
        val knobRadius = stick.radius * 0.40f
        fillPaint.color = Color.argb(alpha(if (stick.pointerId == INVALID_POINTER) 96 else 168, opacity), 238, 240, 250)
        canvas.drawCircle(
            stick.centerX + stick.valueX * stick.radius * 0.56f,
            stick.centerY + stick.valueY * stick.radius * 0.56f,
            knobRadius,
            fillPaint
        )
        if (editMode && selectedKey == stick.key) {
            canvas.drawCircle(stick.centerX, stick.centerY, stick.radius * 1.12f, editPaint)
        }
    }

    private fun drawButton(canvas: Canvas, button: ButtonRegion, opacity: Float) {
        val isPressed = pressed.getOrElse(button.id) { false }
        val rgb = accentRgb(button.accent)
        fillPaint.color = Color.argb(alpha(if (isPressed) 156 else 34, opacity), rgb[0], rgb[1], rgb[2])
        strokePaint.color = Color.argb(alpha(if (isPressed) 235 else 132, opacity), rgb[0], rgb[1], rgb[2])
        canvas.drawCircle(button.x, button.y, button.radius, fillPaint)
        canvas.drawCircle(button.x, button.y, button.radius, strokePaint)
        if (editMode && selectedKey == button.key) {
            canvas.drawCircle(button.x, button.y, button.radius * 1.18f, editPaint)
        }
        textPaint.alpha = alpha(if (isPressed) 255 else 222, opacity)
        textPaint.textSize = (button.radius * if (button.label.length > 2) 0.40f else 0.66f).coerceAtLeast(9f)
        val baseline = button.y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(button.label, button.x, baseline, textPaint)
        textPaint.alpha = 255
    }

    private fun drawEditor(canvas: Canvas) {
        fillPaint.color = Color.argb(156, 8, 11, 18)
        canvas.drawRoundRect(18f, 18f, min(width * 0.62f, 600f), 88f, 18f, 18f, fillPaint)
        canvas.drawText("EDITOR PS2 • arraste um controle", 34f, 48f, editorTextPaint)
        editorTextPaint.isFakeBoldText = false
        canvas.drawText("Selecionado: ${selectedKey ?: "toque em um controle"}", 34f, 73f, editorTextPaint)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(event.getPointerId(event.actionIndex))
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
        if (next == NO_BUTTON) pointerButtons.delete(pointerId) else {
            pointerButtons.put(pointerId, next)
            setPressed(next, true)
        }
    }

    private fun findButton(x: Float, y: Float): ButtonRegion? {
        var best: ButtonRegion? = null
        var bestDistance = Float.MAX_VALUE
        for (button in buttons) {
            if (!config.showDpad && button.id in InputManagerConstants.BUTTON_UP..InputManagerConstants.BUTTON_RIGHT) continue
            if (!config.showL3R3 && (button.id == InputManagerConstants.BUTTON_L3 || button.id == InputManagerConstants.BUTTON_R3)) continue
            val distance = hypot(x - button.x, y - button.y)
            if (distance <= button.radius * 1.45f && distance < bestDistance) {
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
        if (config.precisionAnalog) normalized *= 0.78f + 0.22f * normalized
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
        activeUntilMs = SystemClock.uptimeMillis() + 850L
        removeCallbacks(fadeRunnable)
        postDelayed(fadeRunnable, 900L)
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
            if (distance <= button.radius * 1.65f && distance < distanceBest) {
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
            LEFT_STICK_KEY -> { leftStick.centerX = cx; leftStick.centerY = cy }
            RIGHT_STICK_KEY -> { rightStick.centerX = cx; rightStick.centerY = cy }
            else -> buttons.firstOrNull { it.key == key }?.let { it.x = cx; it.y = cy }
        }
        postInvalidateOnAnimation()
    }

    private fun finishEditDrag() {
        val key = editTargetKey
        if (key != null && width > 0 && height > 0) {
            val x: Float
            val y: Float
            when (key) {
                LEFT_STICK_KEY -> { x = leftStick.centerX / width; y = leftStick.centerY / height }
                RIGHT_STICK_KEY -> { x = rightStick.centerX / width; y = rightStick.centerY / height }
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

    private fun alpha(base: Int, opacity: Float): Int = (base * opacity).toInt().coerceIn(0, 255)

    private fun accentRgb(accent: Int): IntArray = when (accent) {
        ACCENT_PINK -> intArrayOf(255, 125, 210)
        ACCENT_GREEN -> intArrayOf(118, 235, 155)
        ACCENT_RED -> intArrayOf(255, 118, 118)
        ACCENT_BLUE -> intArrayOf(105, 185, 255)
        else -> intArrayOf(236, 239, 249)
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
