package com.omnicore.emulator.emulation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.core.ps2.PS2Backend
import com.omnicore.emulator.core.ps2.PlayPS2Backend
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.PS2GameTuning
import com.omnicore.emulator.performance.PS2SmartPerf
import com.omnicore.emulator.settings.PS2InputSettings
import com.omnicore.emulator.settings.PS2Settings
import com.virtualapplications.play.InputManagerConstants
import kotlin.math.abs
import kotlin.math.hypot

/** Isolated PlayStation 2 gameplay Activity. */
class PS2EmulationActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: PS2GamepadOverlayView
    private lateinit var statusView: TextView
    private lateinit var classicBoot: PS2ClassicBootView
    private lateinit var backend: PlayPS2Backend
    private lateinit var currentGame: GameEntry
    private lateinit var ps2Config: PS2Settings.Config
    private lateinit var inputConfig: PS2InputSettings.Config
    private lateinit var launchPlan: PS2SmartPerf.Plan
    private lateinit var capabilities: PS2Backend.Capabilities

    @Volatile private var destroyed = false
    @Volatile private var booting = false
    @Volatile private var started = false

    private var classicReady = false
    private var controlsVisible = true
    private var manualPaused = false
    private var bootThread: Thread? = null
    private lateinit var perfThread: HandlerThread
    private lateinit var perfHandler: Handler

    /**
     * Performance sampling stays off the UI thread and is measurement-only.
     * The PCSX2 baseline keeps automatic renderer/limiter/resolution changes
     * disabled so telemetry can never destabilize a running game.
     */
    private val perfSampler = object : Runnable {
        override fun run() {
            if (destroyed || !started) return
            val telemetry = backend.telemetry()
            PS2GameTuning.observe(
                context = this@PS2EmulationActivity,
                gameIdentity = gameIdentity(),
                telemetry = telemetry,
                activeRenderer = launchPlan.renderer,
                adaptiveRequested = ps2Config.preset == PS2Settings.Preset.AUTO,
                frameLimitRequested = ps2Config.frameLimit,
                caps = capabilities
            )
            if (!destroyed && started) perfHandler.postDelayed(this, PERF_SAMPLE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Do not force Android Sustained Performance Mode. It targets stable
        // long-duration clocks rather than peak emulator throughput and can
        // reduce performance early on lower-end devices.
        enterImmersiveMode()

        perfThread = HandlerThread("OmniCore-PS2-Perf", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        perfHandler = Handler(perfThread.looper)

        currentGame = gameFromIntent() ?: run {
            Toast.makeText(this, "Imagem PlayStation 2 inválida.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        ps2Config = PS2Settings.resolve(this)
        inputConfig = PS2InputSettings.resolve(this)
        backend = PlayPS2Backend(this)
        capabilities = backend.probe()
        launchPlan = PS2SmartPerf.initial(this, capabilities, ps2Config)
        launchPlan = PS2GameTuning.apply(
            this,
            gameIdentity(),
            launchPlan,
            ps2Config.renderer == PS2Settings.RendererMode.AUTO,
            capabilities
        )

        buildUi()
        if (!capabilities.available) {
            classicReady = true
            classicBoot.visibility = View.GONE
            statusView.visibility = View.VISIBLE
            statusView.text = "PS2 BACKEND INDISPONÍVEL\n${capabilities.notes}"
            return
        }

        if (ps2Config.bootStyle == PS2Settings.BootStyle.CLASSIC) {
            controls.visibility = View.GONE
            statusView.visibility = View.GONE
            classicBoot.start {
                if (destroyed) return@start
                classicReady = true
                classicBoot.visibility = View.GONE
                controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                statusView.visibility = View.VISIBLE
                statusView.text = "PS2 • preparando ${currentGame.title}…"
                attemptBoot()
            }
        } else {
            classicReady = true
            classicBoot.visibility = View.GONE
            controls.visibility = View.VISIBLE
            statusView.visibility = View.VISIBLE
            attemptBoot()
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@PS2EmulationActivity)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        controls = PS2GamepadOverlayView(
            context = this,
            config = inputConfig,
            onButton = { id, pressed -> backend.setButton(id, pressed) },
            onAxis = { id, value -> backend.setAxis(id, value) }
        )
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 10, 12, 24))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 6
            setPadding(dp(14), dp(8), dp(14), dp(8))
            text = "PS2 • preparando ${currentGame.title}…"
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(10) }
        )

        val menuButton = TextView(this).apply {
            text = "⋮"
            setTextColor(Color.argb(235, 245, 245, 255))
            textSize = 26f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(68, 20, 22, 36))
            setOnClickListener { showQuickMenu(this) }
        }
        root.addView(
            menuButton,
            FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                rightMargin = dp(8)
            }
        )

        classicBoot = PS2ClassicBootView(this)
        root.addView(
            classicBoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        configureDisplayPacing(holder.surface)
        backend.attachSurface(holder.surface)
        attemptBoot()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        configureDisplayPacing(holder.surface)
        backend.attachSurface(holder.surface)
        attemptBoot()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (::perfHandler.isInitialized) perfHandler.removeCallbacks(perfSampler)
        controls.releaseAll()
        backend.releaseAllInput()
        if (started) backend.pause()
        backend.attachSurface(null)
    }

    private fun attemptBoot() {
        if (!classicReady || destroyed || started || booting) return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return

        capabilities = backend.probe()
        if (!capabilities.available) {
            statusView.visibility = View.VISIBLE
            statusView.text = "PS2 BACKEND INDISPONÍVEL\n${capabilities.notes}"
            return
        }

        launchPlan = PS2SmartPerf.initial(this, capabilities, ps2Config)
        launchPlan = PS2GameTuning.apply(
            this,
            gameIdentity(),
            launchPlan,
            ps2Config.renderer == PS2Settings.RendererMode.AUTO,
            capabilities
        )
        booting = true
        statusView.visibility = View.VISIBLE
        statusView.text = buildString {
            append("PS2 • PCSX2 ")
            append(capabilities.backendVersion)
            append(" • ")
            append(launchPlan.renderer)
            append(" • ")
            append(launchPlan.internalResolutionFactor)
            append("×")
            if (launchPlan.widescreen) append(" • 16:9")
        }

        bootThread = Thread({
            val result = backend.boot(
                PS2Backend.BootRequest(
                    imagePath = currentGame.uri,
                    gameKey = gameIdentity(),
                    config = launchPlan.asRuntimeConfig()
                )
            )
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                booting = false
                when (result) {
                    is PS2Backend.BootResult.Started -> {
                        started = true
                        statusView.text = buildString {
                            append("PS2 BOOT OK • ${result.renderer} • ${launchPlan.internalResolutionFactor}×")
                            if (launchPlan.widescreen) append(" • 16:9")
                        }
                        perfHandler.removeCallbacks(perfSampler)
                        perfHandler.postDelayed(perfSampler, PERF_SAMPLE_MS)
                        statusView.postDelayed({
                            if (!destroyed && started) statusView.visibility = View.GONE
                        }, 1700L)
                    }
                    is PS2Backend.BootResult.Rejected -> {
                        statusView.visibility = View.VISIBLE
                        statusView.text = "PS2 BOOT REJEITADO\n${result.reason}"
                    }
                    is PS2Backend.BootResult.Failed -> {
                        statusView.visibility = View.VISIBLE
                        statusView.text = "PS2 BOOT FALHOU\n${result.reason}"
                    }
                }
            }
        }, "OmniCore-PS2-Boot").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun showQuickMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            val editing = controls.isEditMode()
            menu.add(0, MENU_PAUSE, 0, if (manualPaused) "Continuar jogo" else "Pausar jogo")

            val saveMenu = menu.addSubMenu("Salvar estado")
            val loadMenu = menu.addSubMenu("Carregar estado")
            for (slot in 1..5) {
                saveMenu.add(0, MENU_SAVE_BASE + slot, slot, "Slot $slot")
                loadMenu.add(0, MENU_LOAD_BASE + slot, slot, "Slot $slot")
            }

            if (editing) {
                menu.add(0, MENU_EDIT_DONE, 30, "Concluir edição")
                menu.add(0, MENU_EDIT_BIGGER, 31, "Aumentar selecionado")
                menu.add(0, MENU_EDIT_SMALLER, 32, "Diminuir selecionado")
                menu.add(0, MENU_EDIT_RESET, 33, "Restaurar layout")
            } else {
                menu.add(0, MENU_EDIT_START, 30, "Editar controles touch")
            }
            menu.add(0, MENU_CONTROLS, 40, if (controlsVisible) "Ocultar controles" else "Mostrar controles")
            menu.add(0, MENU_PERF, 50, "Desempenho agora")
            menu.add(0, MENU_STATUS, 51, "Mostrar status")
            menu.add(0, MENU_RESET_TUNING, 52, "Resetar medição desta sessão")
            menu.add(0, MENU_EXIT, 99, "Sair do jogo")

            setOnMenuItemClickListener { item ->
                when {
                    item.itemId in (MENU_SAVE_BASE + 1)..(MENU_SAVE_BASE + 5) -> {
                        val slot = item.itemId - MENU_SAVE_BASE - 1
                        val ok = backend.saveState(slot)
                        Toast.makeText(this@PS2EmulationActivity, if (ok) "Estado PS2 salvo • Slot ${slot + 1}" else "Não foi possível salvar agora.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId in (MENU_LOAD_BASE + 1)..(MENU_LOAD_BASE + 5) -> {
                        val slot = item.itemId - MENU_LOAD_BASE - 1
                        controls.releaseAll()
                        val ok = backend.loadState(slot)
                        Toast.makeText(this@PS2EmulationActivity, if (ok) "Estado PS2 carregado • Slot ${slot + 1}" else "Não foi possível carregar esse slot.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId == MENU_PAUSE -> {
                        manualPaused = !manualPaused
                        controls.releaseAll()
                        backend.releaseAllInput()
                        if (started) {
                            if (manualPaused) backend.pause() else backend.resume()
                        }
                        true
                    }
                    item.itemId == MENU_EDIT_START -> {
                        controlsVisible = true
                        controls.visibility = View.VISIBLE
                        controls.setEditMode(true)
                        if (started) backend.pause()
                        Toast.makeText(this@PS2EmulationActivity, "Arraste os controles. Use ⋮ para tamanho e concluir.", Toast.LENGTH_LONG).show()
                        true
                    }
                    item.itemId == MENU_EDIT_DONE -> {
                        controls.setEditMode(false)
                        if (started && !manualPaused) backend.resume()
                        Toast.makeText(this@PS2EmulationActivity, "Layout PS2 salvo.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId == MENU_EDIT_BIGGER -> {
                        if (!controls.adjustSelectedScale(+0.08f)) Toast.makeText(this@PS2EmulationActivity, "Selecione um controle primeiro.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId == MENU_EDIT_SMALLER -> {
                        if (!controls.adjustSelectedScale(-0.08f)) Toast.makeText(this@PS2EmulationActivity, "Selecione um controle primeiro.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId == MENU_EDIT_RESET -> {
                        controls.resetEditedLayout()
                        Toast.makeText(this@PS2EmulationActivity, "Layout PS2 restaurado.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId == MENU_CONTROLS -> {
                        controlsVisible = !controlsVisible
                        controls.setEditMode(false)
                        controls.releaseAll()
                        backend.releaseAllInput()
                        controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                        if (started && !manualPaused) backend.resume()
                        true
                    }
                    item.itemId == MENU_PERF -> {
                        showPerformanceStatus()
                        true
                    }
                    item.itemId == MENU_STATUS -> {
                        val tuning = PS2GameTuning.read(this@PS2EmulationActivity, gameIdentity())
                        statusView.text = "PS2 • ${launchPlan.mode} • ${launchPlan.renderer} • ${launchPlan.internalResolutionFactor}×\n${launchPlan.reason}\n${tuning.note}"
                        statusView.visibility = View.VISIBLE
                        true
                    }
                    item.itemId == MENU_RESET_TUNING -> {
                        PS2GameTuning.clear(this@PS2EmulationActivity, gameIdentity())
                        Toast.makeText(this@PS2EmulationActivity, "Medição desta sessão resetada.", Toast.LENGTH_SHORT).show()
                        true
                    }
                    item.itemId == MENU_EXIT -> {
                        finish()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showPerformanceStatus() {
        val telemetry = backend.telemetry()
        val decision = PS2SmartPerf.adapt(launchPlan, telemetry)
        val tuning = PS2GameTuning.read(this, gameIdentity())
        val thermal = telemetry.thermalStatus
        val memoryPct = (telemetry.memoryPressure * 100).toInt().coerceIn(0, 100)
        val fps = if (telemetry.measuredFps > 0f) String.format("%.1f", telemetry.measuredFps) else "--"
        val draws = if (telemetry.drawCallsPerFrame >= 0f) String.format("%.0f", telemetry.drawCallsPerFrame) else "--"
        Toast.makeText(
            this,
            "PS2 ${launchPlan.renderer} • $fps FPS • $draws draws/frame • térmico $thermal • memória $memoryPct% • ${decision.pressure} • ${tuning.note}",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isGamepadSource(event.source)) {
            val id = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> InputManagerConstants.BUTTON_CROSS
                KeyEvent.KEYCODE_BUTTON_B -> InputManagerConstants.BUTTON_CIRCLE
                KeyEvent.KEYCODE_BUTTON_X -> InputManagerConstants.BUTTON_SQUARE
                KeyEvent.KEYCODE_BUTTON_Y -> InputManagerConstants.BUTTON_TRIANGLE
                KeyEvent.KEYCODE_BUTTON_L1 -> InputManagerConstants.BUTTON_L1
                KeyEvent.KEYCODE_BUTTON_L2 -> InputManagerConstants.BUTTON_L2
                KeyEvent.KEYCODE_BUTTON_THUMBL -> InputManagerConstants.BUTTON_L3
                KeyEvent.KEYCODE_BUTTON_R1 -> InputManagerConstants.BUTTON_R1
                KeyEvent.KEYCODE_BUTTON_R2 -> InputManagerConstants.BUTTON_R2
                KeyEvent.KEYCODE_BUTTON_THUMBR -> InputManagerConstants.BUTTON_R3
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_Z -> InputManagerConstants.BUTTON_SELECT
                KeyEvent.KEYCODE_BUTTON_START -> InputManagerConstants.BUTTON_START
                KeyEvent.KEYCODE_DPAD_UP -> InputManagerConstants.BUTTON_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> InputManagerConstants.BUTTON_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> InputManagerConstants.BUTTON_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> InputManagerConstants.BUTTON_RIGHT
                else -> -1
            }
            if (id >= 0) {
                backend.setButton(id, event.action == KeyEvent.ACTION_DOWN)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE && isGamepadSource(event.source)) {
            val left = shapePhysicalStick(
                centeredAxisRaw(event, MotionEvent.AXIS_X),
                centeredAxisRaw(event, MotionEvent.AXIS_Y)
            )
            backend.setAxis(InputManagerConstants.ANALOG_LEFT_X, left.first)
            backend.setAxis(InputManagerConstants.ANALOG_LEFT_Y, left.second)

            val right = shapePhysicalStick(
                chooseAxisRaw(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX),
                chooseAxisRaw(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
            )
            backend.setAxis(InputManagerConstants.ANALOG_RIGHT_X, right.first)
            backend.setAxis(InputManagerConstants.ANALOG_RIGHT_Y, right.second)

            val lTrigger = rawPositiveAxis(event, MotionEvent.AXIS_LTRIGGER)
            val rTrigger = rawPositiveAxis(event, MotionEvent.AXIS_RTRIGGER)
            if (lTrigger >= 0f) backend.setButton(InputManagerConstants.BUTTON_L2, lTrigger > 0.35f)
            if (rTrigger >= 0f) backend.setButton(InputManagerConstants.BUTTON_R2, rTrigger > 0.35f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun centeredAxisRaw(event: MotionEvent, axis: Int): Float {
        val raw = event.getAxisValue(axis).coerceIn(-1f, 1f)
        val range = event.device?.getMotionRange(axis, event.source) ?: return raw
        return if (abs(raw) <= range.flat) 0f else raw
    }

    private fun chooseAxisRaw(event: MotionEvent, primary: Int, fallback: Int): Float {
        val pRange = event.device?.getMotionRange(primary, event.source)
        return if (pRange != null) centeredAxisRaw(event, primary) else centeredAxisRaw(event, fallback)
    }

    private fun shapePhysicalStick(x: Float, y: Float): Pair<Float, Float> {
        val magnitude = hypot(x, y).coerceAtMost(1f)
        val deadzone = inputConfig.analogDeadzone.coerceIn(0.03f, 0.30f)
        if (magnitude <= deadzone) return 0f to 0f

        var normalized = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        if (inputConfig.precisionAnalog) normalized *= 0.72f + 0.28f * normalized
        normalized = (normalized * inputConfig.analogSensitivity).coerceIn(0f, 1f)
        val scale = if (magnitude > 0.0001f) normalized / magnitude else 0f
        return (x * scale).coerceIn(-1f, 1f) to (y * scale).coerceIn(-1f, 1f)
    }

    /** Returns -1 when this device doesn't expose the axis. */
    private fun rawPositiveAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return -1f
        val raw = event.getAxisValue(axis)
        if (raw <= range.flat) return 0f
        val span = (range.max - range.flat).coerceAtLeast(0.001f)
        return ((raw - range.flat) / span).coerceIn(0f, 1f)
    }

    private fun isGamepadSource(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    override fun onPause() {
        if (::perfHandler.isInitialized) perfHandler.removeCallbacks(perfSampler)
        if (::controls.isInitialized) controls.releaseAll()
        if (::backend.isInitialized) {
            backend.releaseAllInput()
            if (started) backend.pause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        if (::backend.isInitialized) {
            if (started && !manualPaused && !controls.isEditMode()) {
                backend.resume()
                if (::perfHandler.isInitialized) {
                    perfHandler.removeCallbacks(perfSampler)
                    perfHandler.postDelayed(perfSampler, PERF_SAMPLE_MS)
                }
            } else if (::surfaceView.isInitialized) attemptBoot()
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (::perfHandler.isInitialized) perfHandler.removeCallbacksAndMessages(null)
        if (::classicBoot.isInitialized) classicBoot.cancel()
        if (::controls.isInitialized) controls.releaseAll()
        if (::backend.isInitialized) backend.stop()
        if (::perfThread.isInitialized) perfThread.quitSafely()
        bootThread = null
        super.onDestroy()
    }

    private fun configureDisplayPacing(surface: Surface) {
        if (!surface.isValid) return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> surface.setFrameRate(
                    60f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> surface.setFrameRate(
                    60f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            }
        }
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun gameIdentity(): String = "${currentGame.fileName.lowercase()}|${currentGame.uri}"

    private fun gameFromIntent(): GameEntry? {
        val id = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()
        val uri = intent.getStringExtra(EXTRA_GAME_URI).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        if (id.isBlank() || uri.isBlank() || fileName.isBlank()) return null
        return GameEntry(
            id = id,
            title = title.ifBlank { fileName.substringBeforeLast('.') },
            fileName = fileName,
            uri = uri,
            system = ConsoleSystem.PLAYSTATION_2,
            sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L),
            folderUri = null,
            companionUris = emptyList()
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_GAME_ID = "ps2_game_id"
        private const val EXTRA_GAME_TITLE = "ps2_game_title"
        private const val EXTRA_GAME_URI = "ps2_game_uri"
        private const val EXTRA_FILE_NAME = "ps2_file_name"
        private const val EXTRA_SIZE_BYTES = "ps2_size_bytes"

        private const val PERF_SAMPLE_MS = 3000L

        private const val MENU_PAUSE = 1
        private const val MENU_SAVE_BASE = 100
        private const val MENU_LOAD_BASE = 200
        private const val MENU_EDIT_START = 300
        private const val MENU_EDIT_DONE = 301
        private const val MENU_EDIT_BIGGER = 302
        private const val MENU_EDIT_SMALLER = 303
        private const val MENU_EDIT_RESET = 304
        private const val MENU_CONTROLS = 400
        private const val MENU_PERF = 500
        private const val MENU_STATUS = 501
        private const val MENU_RESET_TUNING = 502
        private const val MENU_EXIT = 999

        fun intent(context: Context, game: GameEntry): Intent =
            Intent(context, PS2EmulationActivity::class.java).apply {
                putExtra(EXTRA_GAME_ID, game.id)
                putExtra(EXTRA_GAME_TITLE, game.title)
                putExtra(EXTRA_GAME_URI, game.uri)
                putExtra(EXTRA_FILE_NAME, game.fileName)
                putExtra(EXTRA_SIZE_BYTES, game.sizeBytes)
            }
    }
}
