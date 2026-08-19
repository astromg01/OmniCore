package com.omnicore.emulator.emulation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.omnicore.emulator.core.ps2.PS2Backend
import com.omnicore.emulator.core.ps2.PlayPS2Backend
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.PS2SmartPerf

/**
 * Isolated PS2 Boot Bridge Activity.
 *
 * Boot Bridge 1 proves the real Play! lifecycle: VM creation, Android Surface,
 * content URI boot, pause/resume and process isolation. Touch/input and advanced
 * renderer preferences remain separate gates instead of being mixed into boot.
 */
class PS2EmulationActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var backend: PlayPS2Backend
    private lateinit var currentGame: GameEntry

    @Volatile
    private var destroyed = false

    @Volatile
    private var booting = false

    @Volatile
    private var started = false

    private var bootThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        currentGame = gameFromIntent() ?: run {
            finish()
            return
        }
        backend = PlayPS2Backend(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).also { view ->
            view.holder.addCallback(this)
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 10, 12, 24))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            text = "PS2 Boot Bridge • preparando ${currentGame.title}…"
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(18) }
        )
        setContentView(root)

        val capabilities = backend.probe()
        if (!capabilities.available) {
            statusView.text = "PS2 BACKEND INDISPONÍVEL\n${capabilities.notes}"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        backend.attachSurface(holder.surface)
        tryBoot()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        backend.attachSurface(holder.surface)
        tryBoot()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (started) backend.pause()
        backend.attachSurface(null)
    }

    private fun tryBoot() {
        if (destroyed || started || booting) return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return

        val capabilities = backend.probe()
        if (!capabilities.available) {
            statusView.text = "PS2 BACKEND INDISPONÍVEL\n${capabilities.notes}"
            return
        }

        val plan = PS2SmartPerf.initial(this, capabilities)
        booting = true
        statusView.visibility = View.VISIBLE
        statusView.text = buildString {
            append("PS2 Boot Bridge • Play! ")
            append(capabilities.backendVersion)
            append("\nAbrindo ")
            append(currentGame.title)
            append(" • ")
            append(plan.renderer)
            append(" • qualidade ≥ ")
            append(plan.qualityFloorScale)
            append('x')
        }

        bootThread = Thread({
            val result = backend.boot(
                PS2Backend.BootRequest(
                    imagePath = currentGame.uri,
                    gameKey = currentGame.id,
                    config = plan.asRuntimeConfig()
                )
            )
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                booting = false
                when (result) {
                    is PS2Backend.BootResult.Started -> {
                        started = true
                        statusView.text = "PS2 BOOT OK • ${result.backend} • ${result.renderer}"
                        statusView.postDelayed({
                            if (!destroyed && started) statusView.visibility = View.GONE
                        }, 1800L)
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

    override fun onPause() {
        if (started) backend.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::backend.isInitialized) {
            if (started) backend.resume() else if (::surfaceView.isInitialized) tryBoot()
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (::backend.isInitialized) backend.stop()
        bootThread = null
        super.onDestroy()
    }

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
