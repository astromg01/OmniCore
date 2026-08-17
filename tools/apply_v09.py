from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)

# Input settings are intentionally separate from PS1 core settings: the same
# frontend input policy can be reused by future console backends.
input_settings = r'''package com.omnicore.emulator.settings

import android.content.Context

object InputSettings {
    enum class AnalogMode(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Analógico nativo + D-pad para jogos antigos"),
        NATIVE("native", "Nativo", "Envia somente eixos analógicos DualShock"),
        DPAD("dpad", "D-pad", "Stick touch funciona como direcional digital")
    }

    data class Config(
        val analogMode: AnalogMode,
        val touchOpacity: Float,
        val touchScale: Float,
        val haptics: Boolean
    )

    private const val PREFS = "input_settings"
    private const val KEY_ANALOG_MODE = "analog_mode"
    private const val KEY_TOUCH_OPACITY = "touch_opacity"
    private const val KEY_TOUCH_SCALE = "touch_scale"
    private const val KEY_HAPTICS = "haptics"

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeRaw = prefs.getString(KEY_ANALOG_MODE, AnalogMode.SMART.storage)
        return Config(
            analogMode = AnalogMode.entries.firstOrNull { it.storage == modeRaw } ?: AnalogMode.SMART,
            touchOpacity = prefs.getFloat(KEY_TOUCH_OPACITY, 0.82f).coerceIn(0.35f, 1f),
            touchScale = prefs.getFloat(KEY_TOUCH_SCALE, 1f).coerceIn(0.80f, 1.20f),
            haptics = prefs.getBoolean(KEY_HAPTICS, false)
        )
    }

    fun saveAnalogMode(context: Context, mode: AnalogMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ANALOG_MODE, mode.storage).apply()
    }

    fun saveTouchOpacity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TOUCH_OPACITY, value.coerceIn(0.35f, 1f)).apply()
    }

    fun saveTouchScale(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TOUCH_SCALE, value.coerceIn(0.80f, 1.20f)).apply()
    }

    fun saveHaptics(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAPTICS, enabled).apply()
    }
}
'''
write("app/src/main/java/com/omnicore/emulator/settings/InputSettings.kt", input_settings)

# Rebuild the touch overlay around independent button sources. This prevents
# analog-to-D-pad compatibility mapping from fighting with multitouch D-pad input.
gamepad = r'''package com.omnicore.emulator.emulation

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
'''
write("app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt", gamepad)

# EmulationActivity: actual Android joystick axis path + input mode label.
emu = read("app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt")
emu = replace_once(emu, "import android.view.Gravity\n", "import android.view.Gravity\nimport android.view.InputDevice\n", "InputDevice import")
emu = replace_once(emu, "import com.omnicore.emulator.settings.Ps1Settings\n", "import com.omnicore.emulator.settings.InputSettings\nimport com.omnicore.emulator.settings.Ps1Settings\n", "InputSettings import")
emu = replace_once(
    emu,
    '            text = "${ps1Config.preset.label} • ${if (ps1Config.dualShock) "DualShock" else "Digital"} • $biosLabel"\n',
    '            val inputMode = InputSettings.resolve(this@EmulationActivity).analogMode.label\n            text = "${ps1Config.preset.label} • ${if (ps1Config.dualShock) "DualShock" else "Digital"} • $inputMode • $biosLabel"\n',
    "input mode status label"
)
marker = '''    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
'''
insert = r'''    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (started && isJoystick && event.action == MotionEvent.ACTION_MOVE) {
            val input = InputSettings.resolve(this)
            val lx = normalizedAxis(event, MotionEvent.AXIS_X)
            val ly = normalizedAxis(event, MotionEvent.AXIS_Y)
            val rx = normalizedAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
            val ry = normalizedAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)

            if (input.analogMode == InputSettings.AnalogMode.DPAD) NativeBridge.setAnalog(0, 0f, 0f)
            else NativeBridge.setAnalog(0, lx, ly)
            NativeBridge.setAnalog(1, rx, ry)

            if (input.analogMode != InputSettings.AnalogMode.NATIVE) {
                val threshold = 0.42f
                NativeBridge.setButton(4, ly <= -threshold)
                NativeBridge.setButton(5, ly >= threshold)
                NativeBridge.setButton(6, lx <= -threshold)
                NativeBridge.setButton(7, lx >= threshold)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun normalizedAxis(event: MotionEvent, primary: Int, fallback: Int? = null): Float {
        fun value(axis: Int): Float? {
            val range = event.device?.getMotionRange(axis, event.source) ?: return null
            val raw = event.getAxisValue(axis)
            val flat = range.flat.coerceAtLeast(0.08f)
            if (kotlin.math.abs(raw) <= flat) return 0f
            val sign = if (raw < 0f) -1f else 1f
            val normalized = ((kotlin.math.abs(raw) - flat) / (1f - flat)).coerceIn(0f, 1f)
            return normalized * sign
        }
        return value(primary) ?: fallback?.let(::value) ?: 0f
    }

'''
emu = replace_once(emu, marker, insert + marker, "generic joystick path")
write("app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt", emu)

# Frontend polish: searchable/sortable library, remove confirmation and input controls.
ui = read("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt")
ui = replace_once(ui, "import androidx.compose.material3.NavigationBarItem\n", "import androidx.compose.material3.NavigationBarItem\nimport androidx.compose.material3.OutlinedTextField\n", "OutlinedTextField import")
ui = replace_once(ui, "import com.omnicore.emulator.settings.Ps1Settings\n", "import com.omnicore.emulator.settings.InputSettings\nimport com.omnicore.emulator.settings.Ps1Settings\n", "UI InputSettings import")
ui = replace_once(ui, "private enum class HubScreen { LIBRARY, CORES, TUNING }\n", "private enum class HubScreen { LIBRARY, CORES, TUNING }\nprivate enum class LibrarySort { RECENT, TITLE, SIZE }\n", "LibrarySort enum")

start = ui.index("@Composable\nprivate fun HubLibrary(")
end = ui.index("@Composable\nprivate fun EngineHero()", start)
new_library = r'''@Composable
private fun HubLibrary(
    games: List<GameEntry>,
    selected: ConsoleSystem?,
    onFilter: (ConsoleSystem?) -> Unit,
    onImport: () -> Unit,
    onPlay: (GameEntry) -> Unit,
    onRemove: (GameEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var pendingRemoval by remember { mutableStateOf<GameEntry?>(null) }
    val shown = remember(games, selected, query, sort) {
        val filtered = games.asSequence()
            .filter { selected == null || it.system == selected }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.fileName.contains(query.trim(), ignoreCase = true) }
            .toList()
        when (sort) {
            LibrarySort.RECENT -> filtered.sortedByDescending { it.addedAt }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item { EngineHero() }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Buscar na biblioteca") },
                    placeholder = { Text("Nome do jogo ou arquivo") }
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selected == null, onClick = { onFilter(null) }, label = { Text("Todos") }) }
                    items(ConsoleSystem.entries) { system ->
                        FilterChip(selected = selected == system, onClick = { onFilter(system) }, label = { Text(system.shortName) })
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = sort == LibrarySort.RECENT, onClick = { sort = LibrarySort.RECENT }, label = { Text("Recentes") }) }
                    item { FilterChip(selected = sort == LibrarySort.TITLE, onClick = { sort = LibrarySort.TITLE }, label = { Text("A–Z") }) }
                    item { FilterChip(selected = sort == LibrarySort.SIZE, onClick = { sort = LibrarySort.SIZE }, label = { Text("Tamanho") }) }
                }
            }
            if (shown.isEmpty()) {
                item {
                    HubCard {
                        Text(if (games.isEmpty()) "Sua biblioteca está pronta" else "Nenhum jogo encontrado", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (games.isEmpty()) "Adicione uma pasta PS1 ou um arquivo compatível. O PS1 é o primeiro motor funcional do OmniCore."
                            else "Tente limpar a busca ou mudar o filtro de sistema.",
                            color = HubSoft
                        )
                        if (games.isEmpty()) Button(onClick = onImport) { Text("Adicionar jogo") }
                    }
                }
            } else {
                items(shown, key = { it.id }) { game ->
                    Card(
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0x223B4262), RoundedCornerShape(19.dp)),
                        colors = CardDefaults.cardColors(containerColor = HubPanel),
                        shape = RoundedCornerShape(19.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)
                        ) {
                            Box(
                                Modifier.size(60.dp).clip(RoundedCornerShape(17.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF332B66), Color(0xFF17394B)))),
                                contentAlignment = Alignment.Center
                            ) { Text(game.system.shortName, color = Color.White, fontWeight = FontWeight.Black) }
                            Column(Modifier.weight(1f)) {
                                Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                Text(
                                    buildString {
                                        append(game.system.displayName)
                                        if (game.folderUri != null) append(" • pasta")
                                        if (game.sizeBytes > 0) append(" • ").append(formatHubBytes(game.sizeBytes))
                                    },
                                    color = Color(0xFF747D9A), style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { pendingRemoval = game }) { Text("Remover") }
                            Button(onClick = { onPlay(game) }) { Text("Jogar") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(5.dp)) }
        }

        pendingRemoval?.let { game ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                containerColor = HubPanelStrong,
                title = { Text("Remover da biblioteca?") },
                text = { Text("${game.title} será removido apenas da biblioteca. O arquivo do jogo não será apagado.", color = HubSoft) },
                confirmButton = {
                    TextButton(onClick = { onRemove(game); pendingRemoval = null }) { Text("Remover") }
                },
                dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancelar") } }
            )
        }
    }
}

'''
ui = ui[:start] + new_library + ui[end:]

ui = replace_once(
    ui,
    '    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }\n',
    '    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }\n    var inputConfig by remember { mutableStateOf(InputSettings.resolve(context)) }\n',
    "inputConfig state"
)
ui = replace_once(
    ui,
    '    fun refresh() { config = Ps1Settings.resolve(context) }\n',
    '    fun refresh() { config = Ps1Settings.resolve(context) }\n    fun refreshInput() { inputConfig = InputSettings.resolve(context) }\n',
    "refreshInput"
)
old_control = '''        item {
            HubSection("Controle", "Analógico esquerdo touch real + D-pad independente.") {
                SettingSwitch("DualShock / analógico", "Ativa o tipo DualShock no core e os eixos analógicos.", config.dualShock) {
                    Ps1Settings.saveDualShock(context, it)
                    refresh()
                }
            }
        }
'''
new_control = '''        item {
            HubSection("Controles", "Compatibilidade para PS1 antigo, DualShock e controles Android.") {
                SettingSwitch("DualShock / analógico", "Ativa o tipo DualShock no core e os eixos analógicos.", config.dualShock) {
                    Ps1Settings.saveDualShock(context, it)
                    refresh()
                }
                Text("Comportamento do analógico esquerdo", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(InputSettings.AnalogMode.entries) { mode ->
                        FilterChip(
                            selected = inputConfig.analogMode == mode,
                            onClick = { InputSettings.saveAnalogMode(context, mode); refreshInput() },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(inputConfig.analogMode.subtitle, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("Tamanho do touch", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0.85f to "85%", 1f to "100%", 1.15f to "115%")) { option ->
                        FilterChip(
                            selected = kotlin.math.abs(inputConfig.touchScale - option.first) < 0.02f,
                            onClick = { InputSettings.saveTouchScale(context, option.first); refreshInput() },
                            label = { Text(option.second) }
                        )
                    }
                }
                Text("Opacidade", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0.55f to "55%", 0.70f to "70%", 0.85f to "85%", 1f to "100%")) { option ->
                        FilterChip(
                            selected = kotlin.math.abs(inputConfig.touchOpacity - option.first) < 0.03f,
                            onClick = { InputSettings.saveTouchOpacity(context, option.first); refreshInput() },
                            label = { Text(option.second) }
                        )
                    }
                }
                SettingSwitch("Feedback tátil", "Vibração curta ao tocar botões e capturar o analógico.", inputConfig.haptics) {
                    InputSettings.saveHaptics(context, it)
                    refreshInput()
                }
                Text("Alterações de tamanho/opacidade entram na próxima sessão de jogo.", color = Color(0xFF737C98), style = MaterialTheme.typography.labelSmall)
            }
        }
'''
ui = replace_once(ui, old_control, new_control, "control section")
write("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt", ui)

# Versioning: preserve runtime v7; this revision is frontend/input focused.
build = read("app/build.gradle.kts")
build = replace_once(build, 'versionCode = 10\n        versionName = "0.8.0"', 'versionCode = 11\n        versionName = "0.9.0"', "app version")
write("app/build.gradle.kts", build)

bridge = read("app/src/main/cpp/native_bridge.cpp")
bridge = replace_once(
    bridge,
    'OmniCore Native Runtime 0.8.0 / libretro host v7 / EGL-GLES presenter',
    'OmniCore Native Runtime 0.9.0 / libretro host v7 / EGL-GLES presenter',
    "runtime version label"
)
write("app/src/main/cpp/native_bridge.cpp", bridge)

print("OmniCore 0.9 input/frontend migration prepared")
