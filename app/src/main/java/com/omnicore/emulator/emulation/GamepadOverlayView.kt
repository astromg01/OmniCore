package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.settings.InputSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

class GamepadOverlayView(context: Context) : View(context) {
    private data class Region(
        val key: String,
        val id: Int,
        val label: String,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val wide: Boolean = false
    )

    private var config = InputSettings.resolve(context)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 238, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * resources.displayMetrics.density
    }
    private val analogBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 185, 255)
        style = Paint.Style.FILL
    }
    private val analogRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val analogKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 222, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val editorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 31, 34, 58)
        style = Paint.Style.FILL
    }
    private val editorAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 113, 91, 255)
        style = Paint.Style.FILL
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
    private var lastAnalogX = Float.NaN
    private var lastAnalogY = Float.NaN

    private var editMode = false
    private var editPointerId = -1
    private var editTargetKey: String? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout(w, h)
    }

    private fun rebuildLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        config = InputSettings.resolve(context)
        val base = min(w, h).toFloat()
        val scale = config.touchScale
        val r = base * 0.060f * scale
        val small = base * 0.048f * scale
        val dpadR = base * 0.048f * scale
        textPaint.textSize = base * 0.032f * scale

        val analog = InputSettings.resolveControlPosition(context, "analog", 0.145f, 0.73f)
        analogCx = w * analog.x
        analogCy = h * analog.y
        analogRadius = base * 0.105f * scale
        analogKnobX = analogCx
        analogKnobY = analogCy

        val defaults = listOf(
            region("dpad_up", 4, "▲", 0.315f, 0.638f, dpadR),
            region("dpad_down", 5, "▼", 0.315f, 0.802f, dpadR),
            region("dpad_left", 6, "◀", 0.233f, 0.72f, dpadR),
            region("dpad_right", 7, "▶", 0.397f, 0.72f, dpadR),
            region("triangle", 9, "△", 0.845f, 0.55f, r),
            region("cross", 0, "×", 0.845f, 0.83f, r),
            region("square", 1, "□", 0.775f, 0.69f, r),
            region("circle", 8, "○", 0.915f, 0.69f, r),
            region("select", 2, "SELECT", 0.465f, 0.84f, small, wide = true),
            region("start", 3, "START", 0.565f, 0.84f, small, wide = true),
            region("l1", 10, "L1", 0.095f, 0.13f, small, wide = true),
            region("l2", 12, "L2", 0.215f, 0.13f, small, wide = true),
            region("r2", 13, "R2", 0.785f, 0.13f, small, wide = true),
            region("r1", 11, "R1", 0.905f, 0.13f, small, wide = true)
        )
        regions = defaults.map { default ->
            val pos = InputSettings.resolveControlPosition(context, default.key, default.cx, default.cy)
            default.copy(cx = w * pos.x, cy = h * pos.y)
        }
    }

    private fun region(
        key: String,
        id: Int,
        label: String,
        x: Float,
        y: Float,
        radius: Float,
        wide: Boolean = false
    ) = Region(key, id, label, x, y, radius, wide)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val opacity = config.touchOpacity
        analogBasePaint.alpha = scaledAlpha(58, opacity)
        analogRingPaint.alpha = scaledAlpha(130, opacity)
        analogKnobPaint.alpha = scaledAlpha(130, opacity)
        strokePaint.alpha = scaledAlpha(145, opacity)
        textPaint.alpha = scaledAlpha(255, opacity)

        canvas.drawCircle(analogCx, analogCy, analogRadius, analogBasePaint)
        canvas.drawCircle(analogCx, analogCy, analogRadius, analogRingPaint)
        canvas.drawCircle(analogKnobX, analogKnobY, analogRadius * 0.44f, analogKnobPaint)
        textPaint.textSize = min(width, height) * 0.025f * config.touchScale
        canvas.drawText("L", analogCx, analogCy - analogRadius - textPaint.textSize * 0.35f, textPaint)

        textPaint.textSize = min(width, height) * 0.032f * config.touchScale
        visibleRegions().forEach { region ->
            val active = region.id in committedButtons
            val alpha = scaledAlpha(if (active) 145 else 62, opacity)
            fillPaint.color = Color.argb(alpha, if (active) 218 else 235, if (active) 214 else 238, 255)
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

        textPaint.alpha = 255
        drawEditorUi(canvas)
    }

    private fun scaledAlpha(base: Int, opacity: Float): Int =
        (base * opacity.coerceIn(0.35f, 1f)).toInt().coerceIn(0, 255)

    private fun drawEditorUi(canvas: Canvas) {
        val main = editorButtonRect(0)
        val radius = main.height() / 2f
        canvas.drawRoundRect(main, radius, radius, if (editMode) editorAccentPaint else editorPaint)
        textPaint.textSize = min(width, height) * 0.0175f
        canvas.drawText(
            if (editMode) "CONCLUIR" else "EDITAR CONTROLES",
            main.centerX(),
            textBaseline(main.centerY()),
            textPaint
        )

        if (!editMode) return

        val dpad = editorButtonRect(1)
        canvas.drawRoundRect(dpad, dpad.height() / 2f, dpad.height() / 2f, editorPaint)
        canvas.drawText(if (config.showDpad) "SETAS: ON" else "SETAS: OFF", dpad.centerX(), textBaseline(dpad.centerY()), textPaint)

        val reset = editorButtonRect(2)
        canvas.drawRoundRect(reset, reset.height() / 2f, reset.height() / 2f, editorPaint)
        canvas.drawText("RESTAURAR", reset.centerX(), textBaseline(reset.centerY()), textPaint)

        textPaint.textSize = min(width, height) * 0.018f
        canvas.drawText("Arraste qualquer controle para reposicionar", width * 0.5f, height * 0.29f, textPaint)
    }

    private fun editorButtonRect(index: Int): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val buttonW = if (index == 0) base * 0.32f else base * 0.23f
        val buttonH = base * 0.072f
        val centerX = when (index) {
            1 -> width * 0.22f
            2 -> width * 0.78f
            else -> width * 0.50f
        }
        val centerY = height * 0.20f
        return RectF(centerX - buttonW / 2f, centerY - buttonH / 2f, centerX + buttonW / 2f, centerY + buttonH / 2f)
    }

    private fun textBaseline(cy: Float): Float = cy - (textPaint.ascent() + textPaint.descent()) / 2f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleEditorTouch(event)) return true
        if (editMode) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (analogPointerId == -1 && insideAnalog(x, y, 1.55f)) {
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
                findButtonAt(event.getX(pointerIndex), event.getY(pointerIndex), 1.34f)?.let { add(it.id) }
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

    private fun handleEditorTouch(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val actionX = event.getX(actionIndex)
        val actionY = event.getY(actionIndex)

        if (event.actionMasked == MotionEvent.ACTION_DOWN && editorButtonRect(0).contains(actionX, actionY)) {
            setEditMode(!editMode)
            return true
        }

        if (!editMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (editorButtonRect(1).contains(actionX, actionY)) {
                    config = config.copy(showDpad = !config.showDpad)
                    InputSettings.saveShowDpad(context, config.showDpad)
                    regionPressed = regionPressed.filterNot { it in 4..7 }.toSet()
                    commitButtons()
                    scheduleRedraw()
                    return true
                }
                if (editorButtonRect(2).contains(actionX, actionY)) {
                    InputSettings.resetControlPositions(context)
                    rebuildLayout(width, height)
                    scheduleRedraw()
                    Toast.makeText(context, "Layout touch restaurado", Toast.LENGTH_SHORT).show()
                    return true
                }
                if (editPointerId == -1) {
                    editTargetKey = findEditTarget(actionX, actionY)
                    if (editTargetKey != null) {
                        editPointerId = event.getPointerId(actionIndex)
                        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        moveEditTarget(actionX, actionY)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (editPointerId != -1) {
                    val index = event.findPointerIndex(editPointerId)
                    if (index >= 0) moveEditTarget(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == editPointerId) {
                    persistEditTarget()
                    editPointerId = -1
                    editTargetKey = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                editPointerId = -1
                editTargetKey = null
                rebuildLayout(width, height)
                scheduleRedraw()
            }
        }
        return true
    }

    fun toggleEditMode() {
        setEditMode(!editMode)
    }

    fun isEditing(): Boolean = editMode

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        editPointerId = -1
        editTargetKey = null
        releaseAll()
        if (enabled) {
            Toast.makeText(context, "Modo de edição: arraste os controles. SETAS esconde ou mostra o D-pad.", Toast.LENGTH_LONG).show()
        }
        scheduleRedraw()
    }

    private fun findEditTarget(x: Float, y: Float): String? {
        if (insideAnalog(x, y, 1.55f)) return "analog"
        return findButtonAt(x, y, 1.45f)?.key
    }

    private fun findButtonAt(x: Float, y: Float, hitScale: Float): Region? =
        visibleRegions()
            .asSequence()
            .filter { contains(it, x, y, hitScale) }
            .minByOrNull { hitDistance(it, x, y) }

    private fun hitDistance(region: Region, x: Float, y: Float): Double {
        if (!region.wide) {
            return hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) / region.radius.coerceAtLeast(1f)
        }
        val nx = abs(x - region.cx) / (region.radius * 1.75f).coerceAtLeast(1f)
        val ny = abs(y - region.cy) / (region.radius * 0.92f).coerceAtLeast(1f)
        return hypot(nx.toDouble(), ny.toDouble())
    }

    private fun moveEditTarget(x: Float, y: Float) {
        val key = editTargetKey ?: return
        val margin = min(width, height) * 0.055f
        val nx = x.coerceIn(margin, width - margin)
        val ny = y.coerceIn(margin * 1.5f, height - margin)
        if (key == "analog") {
            analogCx = nx
            analogCy = ny
            analogKnobX = nx
            analogKnobY = ny
        } else {
            regions = regions.map { if (it.key == key) it.copy(cx = nx, cy = ny) else it }
        }
        scheduleRedraw()
    }

    private fun persistEditTarget() {
        val key = editTargetKey ?: return
        if (width <= 0 || height <= 0) return
        if (key == "analog") {
            InputSettings.saveControlPosition(context, key, analogCx / width, analogCy / height)
        } else {
            val region = regions.firstOrNull { it.key == key } ?: return
            InputSettings.saveControlPosition(context, key, region.cx / width, region.cy / height)
        }
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

    private fun visibleRegions(): List<Region> =
        if (config.showDpad) regions else regions.filterNot { it.id in 4..7 }

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

        val analogChanged = lastAnalogX.isNaN() || abs(dx - lastAnalogX) > 0.0025f || abs(dy - lastAnalogY) > 0.0025f
        when (config.analogMode) {
            InputSettings.AnalogMode.NATIVE -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                analogDpadPressed = emptySet()
            }
            InputSettings.AnalogMode.DPAD -> {
                if (analogChanged) NativeBridge.setAnalog(0, 0f, 0f)
                analogDpadPressed = dpadProjection(dx, dy)
            }
            InputSettings.AnalogMode.SMART -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                analogDpadPressed = dpadProjection(dx, dy)
            }
        }
        lastAnalogX = dx
        lastAnalogY = dy
        commitButtons()
        scheduleRedraw()
    }

    private fun dpadProjection(x: Float, y: Float): Set<Int> = buildSet {
        val threshold = 0.42f
        if (y <= -threshold) add(4)
        if (y >= threshold) add(5)
        if (x <= -threshold) add(6)
        if (x >= threshold) add(7)
    }

    private fun contains(region: Region, x: Float, y: Float, hitScale: Float): Boolean {
        return if (region.wide) {
            val halfW = region.radius * 1.75f * hitScale
            val halfH = region.radius * 0.92f * hitScale
            x in (region.cx - halfW)..(region.cx + halfW) && y in (region.cy - halfH)..(region.cy + halfH)
        } else {
            hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) <= region.radius * hitScale
        }
    }

    private fun commitButtons() {
        val next = regionPressed + analogDpadPressed
        if (next == committedButtons) return
        (committedButtons - next).forEach { NativeBridge.setButton(it, false) }
        (next - committedButtons).forEach { NativeBridge.setButton(it, true) }
        committedButtons = next
        scheduleRedraw()
    }

    private fun scheduleRedraw() {
        if (isAttachedToWindow) postInvalidateOnAnimation() else invalidate()
    }
}
