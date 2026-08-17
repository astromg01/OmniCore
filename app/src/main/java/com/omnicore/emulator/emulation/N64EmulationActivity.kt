package com.omnicore.emulator.emulation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.core.n64.N64NativeBridge
import com.omnicore.emulator.core.n64.N64RomPreparer
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.N64SmartPerf
import com.omnicore.emulator.settings.N64InputSettings
import com.omnicore.emulator.settings.N64Settings
import com.omnicore.emulator.storage.N64Storage
import java.io.File
import kotlin.math.abs

/** Nintendo 64 owns a separate Android surface, input layer and runtime policy. */
class N64EmulationActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: AspectSurfaceView
    private lateinit var controls: N64GamepadOverlayView
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var prepareThread: Thread? = null
    @Volatile private var destroyed = false
    private var started = false
    private var runOkPolls = 0
    private var lastMessage = ""
    private var lastAdaptAt = 0L
    private var controlsVisible = true

    private var preparedRom: File? = null
    private var storagePaths: N64Storage.Paths? = null
    private lateinit var requestedConfig: N64Settings.Config
    private lateinit var inputConfig: N64InputSettings.Config
    private lateinit var launchDecision: N64SmartPerf.Decision
    private var pendingDecision: N64SmartPerf.Decision? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (started) {
                val message = N64NativeBridge.lastMessage()
                if (message.isNotBlank() && message != lastMessage) {
                    lastMessage = message
                    statusView.text = message
                    statusView.visibility = View.VISIBLE
                    runOkPolls = 0
                }

                val telemetry = N64NativeBridge.telemetry()
                val now = SystemClock.elapsedRealtime()
                if (telemetry.sampleWindowFrames >= 90 && now - lastAdaptAt >= 2500L) {
                    lastAdaptAt = now
                    pendingDecision = N64SmartPerf.adapt(
                        this@N64EmulationActivity,
                        requestedConfig,
                        telemetry.smartPerf()
                    )
                }

                if (message.startsWith("N64 RUN OK")) {
                    runOkPolls++
                    if (runOkPolls >= 5) statusView.visibility = View.GONE
                } else if (message.contains(" E0") || message.contains("BOOT E") || message.contains("RUNTIME E")) {
                    statusView.setBackgroundColor(Color.argb(230, 92, 16, 28))
                }
            }
            handler.postDelayed(this, if (started) 700L else 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val game = gameFromIntent() ?: run {
            Toast.makeText(this, "ROM Nintendo 64 inválida.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        requestedConfig = N64Settings.resolve(this)
        inputConfig = N64InputSettings.resolve(this)
        launchDecision = N64SmartPerf.initial(this, requestedConfig)

        buildUi(game.title)
        handler.post(statusPoll)
        prepareGameAsync(game)
    }

    private fun buildUi(title: String) {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(3, 4, 8)) }
        setContentView(root)

        surfaceView = AspectSurfaceView(this).apply {
            setWillNotDraw(true)
            holder.addCallback(this@N64EmulationActivity)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        controls = N64GamepadOverlayView(this, inputConfig.haptics)
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            text = "Preparando $title • ${launchDecision.level.name}…"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(Color.argb(190, 15, 17, 29))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(10) }
        )

        val menu = TextView(this).apply {
            text = "⋮"
            setTextColor(Color.argb(225, 245, 245, 255))
            textSize = 26f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(78, 20, 22, 36))
            setOnClickListener { showQuickMenu(this) }
        }
        root.addView(
            menu,
            FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                rightMargin = dp(8)
            }
        )
    }

    private fun showQuickMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(if (controlsVisible) "Ocultar controles" else "Mostrar controles")
            menu.add("Mostrar status")
            menu.add("Sair do jogo")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Ocultar controles", "Mostrar controles" -> {
                        controlsVisible = !controlsVisible
                        if (!controlsVisible) controls.releaseAll()
                        controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                        true
                    }
                    "Mostrar status" -> {
                        statusView.text = N64NativeBridge.lastMessage()
                        statusView.visibility = View.VISIBLE
                        runOkPolls = 0
                        true
                    }
                    "Sair do jogo" -> {
                        finish()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun prepareGameAsync(game: GameEntry) {
        statusView.text = "N64 • validando e preparando ROM…"
        prepareThread = Thread({
            val result = runCatching {
                val paths = N64Storage.prepare(applicationContext)
                val prepared = N64RomPreparer.prepare(applicationContext, game).getOrThrow()
                paths to prepared
            }
            if (destroyed) return@Thread
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.onSuccess { (paths, prepared) ->
                    storagePaths = paths
                    preparedRom = prepared.file
                    statusView.text = buildString {
                        append("N64 • ")
                        append(prepared.sourceOrder.label)
                        append(" → z64")
                        if (prepared.reusedCache) append(" • cache")
                    }
                    tryStartSession()
                }.onFailure { error ->
                    statusView.text = "N64 BOOT E00 • ${error.message ?: "falha ao preparar ROM"}"
                    statusView.setBackgroundColor(Color.argb(230, 92, 16, 28))
                    Toast.makeText(this, error.message ?: "Falha ao preparar ROM N64.", Toast.LENGTH_LONG).show()
                }
            }
        }, "OmniCore-N64Prepare").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    private fun tryStartSession() {
        if (destroyed || started) return
        val rom = preparedRom ?: return
        val paths = storagePaths ?: return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return

        val decision = pendingDecision ?: launchDecision
        statusView.text = "N64 • ${decision.level.name} / ${decision.effective.cpuMode.label} / GLES3 + AAudio…"
        started = N64NativeBridge.start(surface, rom, paths, decision, inputConfig)
        if (!started) {
            statusView.text = "N64 BOOT E00 • runtime recusou iniciar a sessão"
            statusView.setBackgroundColor(Color.argb(230, 92, 16, 28))
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        tryStartSession()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (::controls.isInitialized) controls.releaseAll()
        if (started) {
            N64NativeBridge.stop()
            started = false
        }
    }

    override fun onPause() {
        if (::controls.isInitialized) controls.releaseAll()
        if (started) N64NativeBridge.setPaused(true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        if (started) N64NativeBridge.setPaused(false)
        else if (::surfaceView.isInitialized) tryStartSession()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.releaseAll()
        N64NativeBridge.stop()
        prepareThread = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isGamepadSource(event.source)) {
            val retroId = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> 0
                KeyEvent.KEYCODE_BUTTON_B -> 1
                KeyEvent.KEYCODE_BUTTON_X -> 10
                KeyEvent.KEYCODE_BUTTON_Y -> 9
                KeyEvent.KEYCODE_BUTTON_L1 -> 2
                KeyEvent.KEYCODE_BUTTON_R1 -> 13
                KeyEvent.KEYCODE_BUTTON_L2 -> 12
                KeyEvent.KEYCODE_BUTTON_R2 -> 11
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_Z -> 8
                KeyEvent.KEYCODE_BUTTON_START -> 3
                KeyEvent.KEYCODE_DPAD_UP -> 4
                KeyEvent.KEYCODE_DPAD_DOWN -> 5
                KeyEvent.KEYCODE_DPAD_LEFT -> 6
                KeyEvent.KEYCODE_DPAD_RIGHT -> 7
                else -> -1
            }
            if (retroId >= 0) {
                N64NativeBridge.setButton(retroId, event.action == KeyEvent.ACTION_DOWN)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE && isGamepadSource(event.source)) {
            val x = centeredAxis(event, MotionEvent.AXIS_X)
            val y = centeredAxis(event, MotionEvent.AXIS_Y)
            var cX = 0f
            var cY = 0f
            if (inputConfig.cButtonMode == N64InputSettings.CButtonMode.RIGHT_STICK) {
                cX = centeredAxis(event, MotionEvent.AXIS_Z)
                cY = centeredAxis(event, MotionEvent.AXIS_RZ)
                if (abs(cX) < 0.001f && abs(cY) < 0.001f) {
                    cX = centeredAxis(event, MotionEvent.AXIS_RX)
                    cY = centeredAxis(event, MotionEvent.AXIS_RY)
                }
            }
            N64NativeBridge.setAnalog(x, y, cX, cY)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun centeredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source)
        val value = event.getAxisValue(axis)
        val flat = range?.flat ?: 0.05f
        return if (abs(value) > flat) value.coerceIn(-1f, 1f) else 0f
    }

    private fun isGamepadSource(source: Int): Boolean {
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun gameFromIntent(): GameEntry? {
        val id = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()
        val uri = intent.getStringExtra(EXTRA_GAME_URI).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        if (id.isBlank() || uri.isBlank() || fileName.isBlank()) return null
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("z64", "n64", "v64")) return null
        return GameEntry(
            id = id,
            title = title.ifBlank { fileName.substringBeforeLast('.') },
            fileName = fileName,
            uri = uri,
            system = ConsoleSystem.NINTENDO_64,
            sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L),
            folderUri = null,
            companionUris = emptyList()
        )
    }

    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class AspectSurfaceView(context: Context) : SurfaceView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
            var width = maxWidth
            var height = (width * 3f / 4f).toInt()
            if (height > maxHeight) {
                height = maxHeight
                width = (height * 4f / 3f).toInt()
            }
            setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    }

    companion object {
        private const val EXTRA_GAME_ID = "n64_game_id"
        private const val EXTRA_GAME_TITLE = "n64_game_title"
        private const val EXTRA_GAME_URI = "n64_game_uri"
        private const val EXTRA_FILE_NAME = "n64_file_name"
        private const val EXTRA_SIZE_BYTES = "n64_size_bytes"

        fun intent(context: Context, game: GameEntry): Intent = Intent(context, N64EmulationActivity::class.java).apply {
            putExtra(EXTRA_GAME_ID, game.id)
            putExtra(EXTRA_GAME_TITLE, game.title)
            putExtra(EXTRA_GAME_URI, game.uri)
            putExtra(EXTRA_FILE_NAME, game.fileName)
            putExtra(EXTRA_SIZE_BYTES, game.sizeBytes)
        }
    }
}
