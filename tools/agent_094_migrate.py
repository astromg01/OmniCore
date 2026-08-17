from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing pattern: {label}")
    return text.replace(old, new, 1)


def replace_regex(text: str, pattern: str, new: str, label: str) -> str:
    updated, count = re.subn(pattern, new, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"regex pattern count {count}: {label}")
    return updated


# Version bump.
gradle_path = Path("app/build.gradle.kts")
gradle = gradle_path.read_text()
gradle = replace_once(gradle, 'versionCode = 14\n        versionName = "0.9.3"', 'versionCode = 15\n        versionName = "0.9.4"', "version bump")
gradle_path.write_text(gradle)

# Per-control scale persistence.
settings_path = Path("app/src/main/java/com/omnicore/emulator/settings/InputSettings.kt")
settings = settings_path.read_text()
settings = replace_once(
    settings,
    '    private const val POSITION_PREFIX = "control_position_"\n    private const val GAME_PREFIX = "game_"',
    '    private const val POSITION_PREFIX = "control_position_"\n    private const val SCALE_PREFIX = "control_scale_"\n    private const val GAME_PREFIX = "game_"',
    "scale prefix"
)
settings = replace_once(
    settings,
    '''    fun resetControlPositions(context: Context, gameKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gameKey?.let { gamePrefix(it) + POSITION_PREFIX } ?: POSITION_PREFIX
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key -> editor.remove(key) }
        editor.apply()
    }
''',
    '''    fun resolveControlScale(context: Context, key: String, gameKey: String? = null): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val globalKey = SCALE_PREFIX + key
        val gameScaleKey = gameKey?.let { gamePrefix(it) + globalKey }
        return when {
            gameScaleKey != null && prefs.contains(gameScaleKey) -> prefs.getFloat(gameScaleKey, 1f)
            prefs.contains(globalKey) -> prefs.getFloat(globalKey, 1f)
            else -> 1f
        }.coerceIn(0.65f, 1.45f)
    }

    fun saveControlScale(context: Context, key: String, value: Float, gameKey: String? = null) {
        val prefix = gameKey?.let(::gamePrefix).orEmpty()
        edit(context).putFloat("$prefix$SCALE_PREFIX$key", value.coerceIn(0.65f, 1.45f)).apply()
    }

    fun resetControlScales(context: Context, gameKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gameKey?.let { gamePrefix(it) + SCALE_PREFIX } ?: SCALE_PREFIX
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    fun resetControlPositions(context: Context, gameKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gameKey?.let { gamePrefix(it) + POSITION_PREFIX } ?: POSITION_PREFIX
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key -> editor.remove(key) }
        editor.apply()
    }
''',
    "control scale functions"
)
settings_path.write_text(settings)

# Touch/runtime overlay fixes and optimizations.
overlay_path = Path("app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt")
overlay = overlay_path.read_text()
overlay = replace_once(overlay, 'import android.os.SystemClock\n', 'import android.os.SystemClock\nimport android.util.SparseIntArray\n', "SparseIntArray import")
overlay = replace_once(
    overlay,
    '''    private var menuVisible = false
    private var activeUntilMs = 0L
    private var gesturePeak = 0
    private var gestureCaptured = false
    private var legacyStatusView: TextView? = null
    private var wasRunning = false
''',
    '''    private var menuVisible = false
    private var activeUntilMs = 0L
    private var fadeScheduledAtMs = 0L
    private var fourFingerGestureLatched = false
    private val buttonPointerTargets = SparseIntArray()
    private var selectedEditKey: String? = null
    private var legacyStatusView: TextView? = null
    private var cheatsAppliedForSession = false
    private val scratchRect = RectF()
    private val dpadProjectionCache: Array<Set<Int>> = Array(16) { mask ->
        buildSet {
            if (mask and 1 != 0) add(4)
            if (mask and 2 != 0) add(5)
            if (mask and 4 != 0) add(6)
            if (mask and 8 != 0) add(7)
        }
    }
''',
    "touch ownership fields"
)
overlay = replace_once(
    overlay,
    '    private val fadeRunnable = Runnable { scheduleRedraw() }',
    '''    private val fadeRunnable = Runnable {
        fadeScheduledAtMs = 0L
        scheduleRedraw()
    }''',
    "fade runnable"
)
overlay = replace_regex(
    overlay,
    r'''    private val cheatApplyGuard = object : Runnable \{.*?^    \}\n\n    init \{''',
    '''    private val cheatApplyGuard = object : Runnable {
        override fun run() {
            if (cheatsAppliedForSession || !isAttachedToWindow) return
            if (NativeBridge.isRunning()) {
                applyStoredCheats()
                cheatsAppliedForSession = true
            } else {
                postDelayed(this, 250)
            }
        }
    }

    init {''',
    "one-shot cheat apply"
)
overlay = replace_once(
    overlay,
    '''            if (status != null) {
                config = InputSettings.resolveForGame(context, gameKey)
                val text = status.text?.toString().orEmpty()
''',
    '''            if (status != null) {
                val text = status.text?.toString().orEmpty()
''',
    "remove prefs polling"
)
overlay = replace_once(
    overlay,
    '''    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        config = InputSettings.resolveForGame(context, gameKey)
        if (config.controlsVisible || editMode) drawControls(canvas)
''',
    '''    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (config.controlsVisible || editMode) drawControls(canvas)
''',
    "remove prefs from onDraw"
)
overlay = replace_once(
    overlay,
    '''        analogCx = w * analog.x
        analogCy = h * analog.y
        analogRadius = base * 0.105f * scale
''',
    '''        analogCx = w * analog.x
        analogCy = h * analog.y
        val analogScale = InputSettings.resolveControlScale(context, "analog", gameKey)
        analogRadius = base * 0.105f * scale * analogScale
''',
    "analog individual scale"
)
overlay = replace_once(
    overlay,
    '''            val d = xy(key, fx, fy)
            val saved = InputSettings.resolveControlPosition(context, key, d.first, d.second, gameKey)
            return Region(key, id, label, w * saved.x, h * saved.y, radius, wide)
''',
    '''            val d = xy(key, fx, fy)
            val saved = InputSettings.resolveControlPosition(context, key, d.first, d.second, gameKey)
            val individualScale = InputSettings.resolveControlScale(context, key, gameKey)
            return Region(key, id, label, w * saved.x, h * saved.y, radius * individualScale, wide)
''',
    "button individual scale"
)
overlay = replace_once(
    overlay,
    '''        visibleRegions().forEach { region ->
            val active = region.id in committedButtons
''',
    '''        regions.forEach { region ->
            if (!isRegionVisible(region)) return@forEach
            val active = region.id in committedButtons
''',
    "draw visible regions without allocation"
)
overlay = replace_once(
    overlay,
    '''                val rect = RectF(region.cx - halfW, region.cy - halfH, region.cx + halfW, region.cy + halfH)
                canvas.drawRoundRect(rect, halfH, halfH, fillPaint)
                canvas.drawRoundRect(rect, halfH, halfH, strokePaint)
''',
    '''                scratchRect.set(region.cx - halfW, region.cy - halfH, region.cx + halfW, region.cy + halfH)
                canvas.drawRoundRect(scratchRect, halfH, halfH, fillPaint)
                canvas.drawRoundRect(scratchRect, halfH, halfH, strokePaint)
''',
    "draw rect reuse"
)
overlay = replace_once(
    overlay,
    '        canvas.drawText("3 dedos: menu • 4 dedos: overlay", panel.centerX(), panel.bottom - min(width, height) * 0.018f, textPaint)',
    '        canvas.drawText("4 dedos: ocultar/mostrar overlay", panel.centerX(), panel.bottom - min(width, height) * 0.018f, textPaint)',
    "quick menu gesture hint"
)
overlay = replace_regex(
    overlay,
    r'''    private fun drawEditorUi\(canvas: Canvas\) \{.*?^    \}\n\n    private fun menuButtonRect''',
    '''    private fun drawEditorUi(canvas: Canvas) {
        textPaint.textSize = min(width, height) * 0.016f
        for (index in 0..4) {
            val rect = editorButtonRect(index)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, if (index == 0) chromeAccentPaint else chromePaint)
            val label = when (index) {
                0 -> "CONCLUIR"
                1 -> if (config.showDpad) "SETAS ON" else "SETAS OFF"
                2 -> "RESTAURAR"
                3 -> "TAM −"
                else -> "TAM +"
            }
            canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY()), textPaint)
        }
        textPaint.textSize = min(width, height) * 0.016f
        val selected = selectedEditKey
        val info = if (selected == null) {
            "Toque ou arraste um controle para selecioná-lo"
        } else {
            val scale = (InputSettings.resolveControlScale(context, selected, gameKey) * 100f).toInt()
            "Selecionado: ${selected.replace('_', ' ').uppercase()} • tamanho $scale%"
        }
        canvas.drawText(info, width * 0.5f, height * 0.225f, textPaint)
    }

    private fun menuButtonRect''',
    "editor size controls"
)
overlay = replace_regex(
    overlay,
    r'''    private fun editorButtonRect\(index: Int\): RectF \{.*?^    \}\n\n    private fun textBaseline''',
    '''    private fun editorButtonRect(index: Int): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val buttonW = when (index) {
            0 -> base * 0.25f
            3, 4 -> base * 0.18f
            else -> base * 0.21f
        }
        val buttonH = base * 0.062f
        val centerX = when (index) {
            1 -> width * 0.20f
            2 -> width * 0.80f
            3 -> width * 0.39f
            4 -> width * 0.61f
            else -> width * 0.50f
        }
        val centerY = if (index >= 3) height * 0.165f else height * 0.085f
        return RectF(centerX - buttonW / 2f, centerY - buttonH / 2f, centerX + buttonW / 2f, centerY + buttonH / 2f)
    }

    private fun textBaseline''',
    "editor button geometry"
)
overlay = replace_regex(
    overlay,
    r'''    override fun onTouchEvent\(event: MotionEvent\): Boolean \{.*?^    private fun handleMultiFingerGesture\(event: MotionEvent\): Boolean \{.*?^    \}\n\n    private fun handleQuickMenuTouch''',
    '''    override fun onTouchEvent(event: MotionEvent): Boolean {
        boostInteraction()
        if (handleMultiFingerGesture(event)) return true
        if (handleQuickMenuTouch(event)) return true
        if (editMode) return handleEditorTouch(event)
        if (!config.controlsVisible) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                if (analogPointerId == -1 && insideAnalog(x, y, 1.55f)) {
                    analogPointerId = pointerId
                    buttonPointerTargets.delete(pointerId)
                    if (config.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else {
                    findButtonAt(x, y, 1.34f)?.let { region ->
                        buttonPointerTargets.put(pointerId, region.id)
                        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == analogPointerId) {
                    analogPointerId = -1
                    updateAnalog(analogCx, analogCy)
                }
                buttonPointerTargets.delete(pointerId)
            }
            MotionEvent.ACTION_CANCEL -> {
                buttonPointerTargets.clear()
                regionPressed = emptySet()
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

        syncPressedButtonsFromPointers()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    private fun syncPressedButtonsFromPointers() {
        if (buttonPointerTargets.size() == 0) {
            if (regionPressed.isNotEmpty()) {
                regionPressed = emptySet()
                commitButtons()
            }
            return
        }
        val next = HashSet<Int>(buttonPointerTargets.size())
        for (index in 0 until buttonPointerTargets.size()) next.add(buttonPointerTargets.valueAt(index))
        if (next != regionPressed) {
            regionPressed = next
            commitButtons()
        }
    }

    private fun handleMultiFingerGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> fourFingerGestureLatched = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!fourFingerGestureLatched && event.pointerCount >= 4 && event.eventTime - event.downTime <= 500L) {
                    fourFingerGestureLatched = true
                    releaseAll()
                    config = config.copy(controlsVisible = !config.controlsVisible)
                    persistGameConfig()
                    showToast(if (config.controlsVisible) "Overlay visível" else "Modo ultra imersivo")
                    scheduleRedraw()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fourFingerGestureLatched = false
        }
        return fourFingerGestureLatched && event.pointerCount >= 4
    }

    private fun handleQuickMenuTouch''',
    "stable multitouch and remove 3 finger gesture"
)
overlay = replace_once(
    overlay,
    '''    private fun boostInteraction() {
        activeUntilMs = SystemClock.uptimeMillis() + 650L
        removeCallbacks(fadeRunnable)
        postDelayed(fadeRunnable, 680L)
    }
''',
    '''    private fun boostInteraction() {
        val now = SystemClock.uptimeMillis()
        activeUntilMs = now + 650L
        val desiredFade = now + 680L
        if (fadeScheduledAtMs == 0L || desiredFade - fadeScheduledAtMs > 420L) {
            removeCallbacks(fadeRunnable)
            fadeScheduledAtMs = desiredFade
            postDelayed(fadeRunnable, 680L)
        }
    }
''',
    "coalesced fade scheduling"
)
overlay = replace_once(
    overlay,
    '''            if (editorButtonRect(2).contains(actionX, actionY)) {
                InputSettings.resetControlPositions(context, gameKey)
                rebuildLayout(width, height)
                showToast("Layout deste jogo restaurado")
                return true
            }
''',
    '''            if (editorButtonRect(2).contains(actionX, actionY)) {
                InputSettings.resetControlPositions(context, gameKey)
                InputSettings.resetControlScales(context, gameKey)
                selectedEditKey = null
                rebuildLayout(width, height)
                showToast("Layout e tamanhos deste jogo restaurados")
                return true
            }
            if (editorButtonRect(3).contains(actionX, actionY)) {
                adjustSelectedControlScale(-0.10f)
                return true
            }
            if (editorButtonRect(4).contains(actionX, actionY)) {
                adjustSelectedControlScale(0.10f)
                return true
            }
''',
    "editor reset and size buttons"
)
overlay = replace_once(
    overlay,
    '''                    editTargetKey = findEditTarget(actionX, actionY)
                    if (editTargetKey != null) {
                        editPointerId = event.getPointerId(actionIndex)
''',
    '''                    editTargetKey = findEditTarget(actionX, actionY)
                    selectedEditKey = editTargetKey
                    if (editTargetKey != null) {
                        editPointerId = event.getPointerId(actionIndex)
''',
    "editor selection"
)
overlay = replace_once(
    overlay,
    '''        editPointerId = -1
        editTargetKey = null
        releaseAll()
        if (enabled) showToast("EDITAR CONTROLES: arraste e toque em CONCLUIR quando terminar")
''',
    '''        editPointerId = -1
        editTargetKey = null
        if (!enabled) selectedEditKey = null
        releaseAll()
        if (enabled) showToast("EDITAR: arraste controles; selecione um e use TAM − / TAM +")
''',
    "edit mode guidance"
)
overlay = replace_once(
    overlay,
    '''    private fun persistGameConfig() {
        InputSettings.saveGameConfig(context, gameKey, config)
    }

    private fun findEditTarget''',
    '''    private fun persistGameConfig() {
        InputSettings.saveGameConfig(context, gameKey, config)
    }

    private fun adjustSelectedControlScale(delta: Float) {
        val key = selectedEditKey
        if (key == null) {
            showToast("Toque em um controle primeiro")
            return
        }
        val current = InputSettings.resolveControlScale(context, key, gameKey)
        val next = (current + delta).coerceIn(0.65f, 1.45f)
        InputSettings.saveControlScale(context, key, next, gameKey)
        rebuildLayout(width, height)
        scheduleRedraw()
    }

    private fun findEditTarget''',
    "selected control scaling"
)
overlay = replace_regex(
    overlay,
    r'''    private fun findButtonAt\(x: Float, y: Float, hitScale: Float\): Region\? =\n        visibleRegions\(\)\.asSequence\(\)\.filter \{ contains\(it, x, y, hitScale\) \}\.minByOrNull \{ hitDistance\(it, x, y\) \}\n''',
    '''    private fun findButtonAt(x: Float, y: Float, hitScale: Float): Region? {
        var best: Region? = null
        var bestDistance = Double.MAX_VALUE
        for (region in regions) {
            if (!isRegionVisible(region) || !contains(region, x, y, hitScale)) continue
            val distance = hitDistance(region, x, y)
            if (distance < bestDistance) {
                best = region
                bestDistance = distance
            }
        }
        return best
    }
''',
    "allocation-free button hit test"
)
overlay = replace_once(
    overlay,
    '''    fun releaseAll() {
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
        commitButtons()
        analogPointerId = -1
        updateAnalog(analogCx, analogCy)
    }

    private fun visibleRegions(): List<Region> = regions.filter { region ->
        when {
            region.id in 4..7 && !config.showDpad -> false
            region.id in 10..13 && !config.showShoulders -> false
            region.id in 2..3 && !config.showStartSelect -> false
            else -> true
        }
    }
''',
    '''    fun releaseAll() {
        buttonPointerTargets.clear()
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
        commitButtons()
        analogPointerId = -1
        updateAnalog(analogCx, analogCy)
    }

    private fun isRegionVisible(region: Region): Boolean = when {
        region.id in 4..7 && !config.showDpad -> false
        region.id in 10..13 && !config.showShoulders -> false
        region.id in 2..3 && !config.showStartSelect -> false
        else -> true
    }
''',
    "region visibility and release ownership"
)
overlay = replace_regex(
    overlay,
    r'''    private fun updateAnalog\(x: Float, y: Float\) \{.*?^    \}\n\n    private fun dpadProjection\(x: Float, y: Float\): Set<Int> = buildSet \{.*?^    \}\n''',
    '''    private fun updateAnalog(x: Float, y: Float) {
        if (analogRadius <= 0f) return
        var dx = (x - analogCx) / analogRadius
        var dy = (y - analogCy) / analogRadius
        val magnitude = sqrt(dx * dx + dy * dy)
        if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
        val deadzone = 0.11f
        if (magnitude < deadzone) {
            dx = 0f; dy = 0f
        } else if (magnitude > 0f) {
            val remapped = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            val remapScale = remapped / magnitude.coerceAtLeast(0.0001f)
            dx *= remapScale; dy *= remapScale
        }
        analogKnobX = analogCx + dx * analogRadius * 0.72f
        analogKnobY = analogCy + dy * analogRadius * 0.72f
        val analogChanged = lastAnalogX.isNaN() || abs(dx - lastAnalogX) > 0.0025f || abs(dy - lastAnalogY) > 0.0025f
        val nextDigital = when (config.analogMode) {
            InputSettings.AnalogMode.NATIVE -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                emptySet()
            }
            InputSettings.AnalogMode.DPAD -> {
                if (analogChanged) NativeBridge.setAnalog(0, 0f, 0f)
                dpadProjection(dx, dy)
            }
            InputSettings.AnalogMode.SMART -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                dpadProjection(dx, dy)
            }
        }
        if (nextDigital != analogDpadPressed) {
            analogDpadPressed = nextDigital
            commitButtons()
        }
        lastAnalogX = dx
        lastAnalogY = dy
        scheduleRedraw()
    }

    private fun dpadProjection(x: Float, y: Float): Set<Int> {
        val threshold = 0.42f
        var mask = 0
        if (y <= -threshold) mask = mask or 1
        if (y >= threshold) mask = mask or 2
        if (x <= -threshold) mask = mask or 4
        if (x >= threshold) mask = mask or 8
        return dpadProjectionCache[mask]
    }
''',
    "cached dpad projection"
)
overlay_path.write_text(overlay)

# Main Compose frontend: move startup storage/core probing off the UI thread and async library saves.
ui_path = Path("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt")
ui = ui_path.read_text()
ui = replace_once(ui, 'import androidx.compose.runtime.Composable\n', 'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n', "LaunchedEffect import")
ui = replace_once(ui, 'import androidx.compose.runtime.remember\n', 'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n', "scope import")
ui = replace_once(ui, 'import java.util.UUID\n', 'import java.util.UUID\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n', "coroutine imports")
ui = replace_once(
    ui,
    '''    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    var games by remember { mutableStateOf(store.load()) }
    var filter by remember { mutableStateOf<ConsoleSystem?>(null) }
    var screen by remember { mutableStateOf(HubScreen.LIBRARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var importDialog by remember { mutableStateOf(false) }
    var biosCount by remember { mutableIntStateOf(Ps1Files.biosFiles(context).size) }
''',
    '''    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    val ioScope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var filter by remember { mutableStateOf<ConsoleSystem?>(null) }
    var screen by remember { mutableStateOf(HubScreen.LIBRARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var importDialog by remember { mutableStateOf(false) }
    var biosCount by remember { mutableIntStateOf(0) }
    var ps1Ready by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val startup = withContext(Dispatchers.IO) {
            Triple(store.load(), Ps1Files.biosFiles(context).size, NativeBridge.hasPs1Core())
        }
        games = startup.first
        biosCount = startup.second
        ps1Ready = startup.third
    }
''',
    "background startup IO"
)
ui = replace_once(
    ui,
    '''        games = (games + additions).distinctBy { "${it.uri}|${it.folderUri.orEmpty()}" }
        store.save(games)
        message = success
''',
    '''        games = (games + additions).distinctBy { "${it.uri}|${it.folderUri.orEmpty()}" }
        val snapshot = games
        ioScope.launch(Dispatchers.IO) { store.save(snapshot) }
        message = success
''',
    "async persist"
)
ui = replace_once(ui, '            item { EngineHero() }', '            item { EngineHero(ps1Ready) }', "hero call")
# The hero call lives inside HubLibrary, so thread ps1Ready through its parameters and call site.
ui = replace_once(
    ui,
    '''                    HubScreen.LIBRARY -> HubLibrary(
                        games = games,
                        selected = filter,
''',
    '''                    HubScreen.LIBRARY -> HubLibrary(
                        games = games,
                        selected = filter,
                        ps1Ready = ps1Ready,
''',
    "library ps1 state call"
)
ui = replace_once(
    ui,
    '''private fun HubLibrary(
    games: List<GameEntry>,
    selected: ConsoleSystem?,
''',
    '''private fun HubLibrary(
    games: List<GameEntry>,
    selected: ConsoleSystem?,
    ps1Ready: Boolean?,
''',
    "library ps1 state param"
)
ui = replace_once(
    ui,
    '''                        onRemove = { game ->
                            games = games.filterNot { it.id == game.id }
                            store.save(games)
                        }
''',
    '''                        onRemove = { game ->
                            games = games.filterNot { it.id == game.id }
                            val snapshot = games
                            ioScope.launch(Dispatchers.IO) { store.save(snapshot) }
                        }
''',
    "async remove save"
)
ui = replace_once(
    ui,
    '''@Composable
private fun EngineHero() {
''',
    '''@Composable
private fun EngineHero(ps1Ready: Boolean?) {
''',
    "hero signature"
)
ui = replace_once(
    ui,
    '''            AssistChip(onClick = {}, label = { Text(if (NativeBridge.hasPs1Core()) "ONLINE" else "OFFLINE") })
''',
    '''            AssistChip(onClick = {}, label = {
                Text(when (ps1Ready) { true -> "ONLINE"; false -> "OFFLINE"; null -> "VERIFICANDO" })
            })
''',
    "nonblocking core status"
)
ui_path.write_text(ui)

print("OmniCore 0.9.4 migration applied")
