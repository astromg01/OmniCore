package com.omnicore.emulator.emulation

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.core.ps1.Ps1Core
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.storage.Ps1Files
import com.omnicore.emulator.storage.SafGameSource
import java.io.File

class EmulationActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: GamepadOverlayView
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var sessionDescriptors: List<ParcelFileDescriptor> = emptyList()
    private var sessionDir: File? = null
    private var preparationThread: Thread? = null
    @Volatile private var destroyed = false
    private var started = false
    private var gamePath: String? = null
    private var gameKey: String = "game"
    private var gameTitle: String = "PlayStation"
    private lateinit var deviceProfile: PerformanceManager.DeviceProfile
    private lateinit var performanceConfig: PerformanceManager.RuntimeConfig
    private var thermalMonitor: ThermalMonitor? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            if (started) {
                val text = NativeBridge.lastMessage()
                if (text.isNotBlank() && statusView.text.toString() != text) statusView.text = text
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        deviceProfile = PerformanceManager.profile(this)
        performanceConfig = PerformanceManager.initialConfig(this)
        registerThermalAdaptation()

        gameKey = intent.getStringExtra(EXTRA_GAME_ID).orEmpty().ifBlank { "game" }
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty().ifBlank { "PlayStation" }
        val uriString = intent.getStringExtra(EXTRA_GAME_URI)
        val extension = intent.getStringExtra(EXTRA_EXTENSION).orEmpty().lowercase()
        val folderUri = intent.getStringExtra(EXTRA_FOLDER_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val companionUris = intent.getStringArrayListExtra(EXTRA_COMPANION_URIS)
            .orEmpty()
            .map(Uri::parse)

        if (uriString.isNullOrBlank() || extension !in Ps1Core.SUPPORTED_EXTENSIONS) {
            Toast.makeText(this, "Arquivo de PS1 não suportado nesta versão.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()
        handler.post(statusPoll)
        prepareGameAsync(Uri.parse(uriString), extension, folderUri, companionUris)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.BLACK)
            holder.addCallback(this@EmulationActivity)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        controls = GamepadOverlayView(this)
        root.addView(
            controls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        statusView = TextView(this).apply {
            text = "Preparando $gameTitle…"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(145, 0, 0, 0))
            maxLines = 2
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(8)
            }
        )

        val save = actionButton("SALVAR") { NativeBridge.saveState(0) }
        root.addView(
            save,
            FrameLayout.LayoutParams(dp(92), dp(42), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = dp(10)
                topMargin = dp(10)
            }
        )

        val load = actionButton("CARREGAR") { NativeBridge.loadState(0) }
        root.addView(
            load,
            FrameLayout.LayoutParams(dp(108), dp(42), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = dp(108)
                topMargin = dp(10)
            }
        )

        val exit = actionButton("SAIR") { finish() }
        root.addView(
            exit,
            FrameLayout.LayoutParams(dp(82), dp(42), Gravity.TOP or Gravity.RIGHT).apply {
                rightMargin = dp(10)
                topMargin = dp(10)
            }
        )

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val targetWidth = minOf(width, (height * 4f / 3f).toInt())
            val targetHeight = minOf(height, (targetWidth * 3f / 4f).toInt())
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                params.gravity = Gravity.CENTER
                surfaceView.layoutParams = params
            }
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.argb(165, 25, 25, 35))
        alpha = 0.88f
        setOnClickListener { action() }
    }

    private data class PreparedContent(
        val path: String,
        val descriptors: List<ParcelFileDescriptor>,
        val sessionDir: File
    ) : AutoCloseable {
        override fun close() {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            runCatching { sessionDir.deleteRecursively() }
        }
    }

    private fun prepareGameAsync(
        uri: Uri,
        extension: String,
        folderUri: Uri?,
        companionUris: List<Uri>
    ) {
        statusView.text = if (extension == "cue") "Preparando faixas de $gameTitle…" else "Preparando $gameTitle…"
        preparationThread = Thread({
            val result = prepareSessionPath(uri, extension, folderUri, companionUris)
            handler.post {
                preparationThread = null
                if (destroyed) {
                    result.getOrNull()?.close()
                    return@post
                }

                result.onSuccess { prepared ->
                    sessionDescriptors = prepared.descriptors
                    sessionDir = prepared.sessionDir
                    gamePath = prepared.path
                    statusView.text = "Iniciando $gameTitle…"
                    if (surfaceView.holder.surface.isValid) {
                        tryStart(surfaceView.holder.surface)
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Não consegui abrir o jogo.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }, "OmniCore-ContentPrep").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun prepareSessionPath(
        uri: Uri,
        extension: String,
        folderUri: Uri?,
        companionUris: List<Uri>
    ): Result<PreparedContent> = runCatching {
        if (extension == "cue") {
            prepareCueSession(uri, folderUri, companionUris)
        } else {
            prepareSingleFileSession(uri, extension)
        }
    }

    private fun prepareSingleFileSession(uri: Uri, extension: String): PreparedContent {
        val dir = freshSessionDir()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        return try {
            val target = File(dir, "game.$extension")
            stageDocument(uri, target, descriptors)
            ensurePreparationActive()
            PreparedContent(target.absolutePath, descriptors.toList(), dir)
        } catch (error: Throwable) {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun prepareCueSession(
        cueUri: Uri,
        folderUri: Uri?,
        companionUris: List<Uri>
    ): PreparedContent {
        val dir = freshSessionDir()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        return try {
            ensurePreparationActive()
            val sources = if (folderUri != null) {
                SafGameSource.listDirectChildren(this, folderUri).filterNot { it.isDirectory }
            } else {
                (companionUris + cueUri)
                    .distinctBy(Uri::toString)
                    .map { SafGameSource.metadata(this, it) }
            }

            val cueText = SafGameSource.readCueText(this, cueUri)
            val references = SafGameSource.cueReferences(cueText)
            require(references.isNotEmpty()) {
                "O arquivo CUE não contém nenhuma faixa FILE reconhecível."
            }

            val byName = sources.associateBy { it.name.lowercase() }
            val resolvedNames = mutableMapOf<String, String>()
            val stagedUris = mutableSetOf<String>()

            references.forEach { reference ->
                ensurePreparationActive()
                val baseName = SafGameSource.normalizeReference(reference)
                val source = byName[baseName.lowercase()]
                    ?: error("A faixa '$baseName' citada no CUE não foi encontrada. Importe a pasta completa do jogo.")
                val safeName = safeFileName(source.name)
                resolvedNames[baseName.lowercase()] = safeName
                if (stagedUris.add(source.uri.toString())) {
                    stageDocument(source.uri, File(dir, safeName), descriptors)
                }
            }

            // Optional SBI files are tiny and improve compatibility for protected discs.
            sources.filter { it.extension == "sbi" }.forEach { source ->
                if (stagedUris.add(source.uri.toString())) {
                    stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors)
                }
            }

            val rewrittenCue = SafGameSource.rewriteCueReferences(cueText, resolvedNames)
            val localCue = File(dir, "game.cue")
            localCue.writeText(rewrittenCue, Charsets.UTF_8)
            ensurePreparationActive()
            PreparedContent(localCue.absolutePath, descriptors.toList(), dir)
        } catch (error: Throwable) {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun freshSessionDir(): File {
        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(cacheDir, "ps1-session/$safeKey")
        dir.deleteRecursively()
        require(dir.mkdirs() || dir.isDirectory) { "Não consegui criar a sessão temporária do jogo." }
        return dir
    }

    private fun stageDocument(
        uri: Uri,
        target: File,
        retainedDescriptors: MutableList<ParcelFileDescriptor>
    ) {
        ensurePreparationActive()
        target.parentFile?.mkdirs()
        target.delete()

        val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "r")) {
            "O Android não forneceu acesso a ${target.name}."
        }
        val procPath = "/proc/self/fd/${descriptor.fd}"
        val seekable = runCatching {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        }.getOrDefault(false)
        val linked = seekable && runCatching {
            Os.symlink(procPath, target.absolutePath)
            true
        }.getOrDefault(false)

        if (linked) {
            retainedDescriptors += descriptor
            return
        }

        runCatching { descriptor.close() }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não consegui ler ${target.name}." }
            target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    ensurePreparationActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun safeFileName(name: String): String =
        name.replace('\\', '_').replace('/', '_').ifBlank { "track.bin" }

    private fun ensurePreparationActive() {
        if (destroyed || Thread.currentThread().isInterrupted) {
            error("Preparação cancelada.")
        }
    }

    private fun tryStart(surface: Surface) {
        if (started || !surface.isValid) return
        val path = gamePath ?: return
        val ok = NativeBridge.startPs1(
            gamePath = path,
            gameKey = gameKey,
            systemDir = Ps1Files.systemDir(this).absolutePath,
            saveDir = Ps1Files.saveDir(this).absolutePath,
            stateDir = Ps1Files.stateDir(this).absolutePath,
            surface = surface,
            performancePolicy = performanceConfig.policy.nativeValue,
            audioBufferBursts = performanceConfig.audioBufferBursts,
            tryExclusiveAudio = performanceConfig.tryExclusiveAudio,
            preferPowerEfficiency = performanceConfig.preferPowerEfficiency,
            aggressiveFramePacing = performanceConfig.aggressiveFramePacing
        )
        started = ok
        if (!ok) statusView.text = "Falha ao iniciar o core PS1."
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        tryStart(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopSession()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        if (::surfaceView.isInitialized && surfaceView.holder.surface.isValid) {
            tryStart(surfaceView.holder.surface)
        }
    }

    override fun onPause() {
        stopSession()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        preparationThread?.interrupt()
        preparationThread = null
        handler.removeCallbacks(statusPoll)
        unregisterThermalAdaptation()
        stopSession()
        sessionDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        sessionDescriptors = emptyList()
        runCatching { sessionDir?.deleteRecursively() }
        sessionDir = null
        super.onDestroy()
    }

    private fun registerThermalAdaptation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        thermalMonitor = ThermalMonitor(this).also { it.register() }
    }

    private fun unregisterThermalAdaptation() {
        thermalMonitor?.unregister()
        thermalMonitor = null
    }

    private fun onThermalStatusChanged(status: Int) {
        val next = PerformanceManager.resolve(
            PerformanceManager.readUserMode(this),
            deviceProfile,
            status
        )
        if (next == performanceConfig) return

        performanceConfig = next
        if (started) {
            NativeBridge.updatePerformancePolicy(
                performancePolicy = next.policy.nativeValue,
                audioBufferBursts = next.audioBufferBursts,
                tryExclusiveAudio = next.tryExclusiveAudio,
                preferPowerEfficiency = next.preferPowerEfficiency,
                aggressiveFramePacing = next.aggressiveFramePacing
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private class ThermalMonitor(private val activity: EmulationActivity) {
        private val manager = activity.getSystemService(PowerManager::class.java)
        private val listener = PowerManager.OnThermalStatusChangedListener { status ->
            activity.onThermalStatusChanged(status)
        }

        fun register() {
            runCatching { manager?.addThermalStatusListener(listener) }
        }

        fun unregister() {
            runCatching { manager?.removeThermalStatusListener(listener) }
        }
    }

    private fun stopSession() {
        if (::controls.isInitialized) controls.releaseAll()
        if (started) NativeBridge.stop()
        started = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 4
            KeyEvent.KEYCODE_DPAD_DOWN -> 5
            KeyEvent.KEYCODE_DPAD_LEFT -> 6
            KeyEvent.KEYCODE_DPAD_RIGHT -> 7
            KeyEvent.KEYCODE_BUTTON_A -> 0
            KeyEvent.KEYCODE_BUTTON_X -> 1
            KeyEvent.KEYCODE_BUTTON_B -> 8
            KeyEvent.KEYCODE_BUTTON_Y -> 9
            KeyEvent.KEYCODE_BUTTON_SELECT -> 2
            KeyEvent.KEYCODE_BUTTON_START -> 3
            KeyEvent.KEYCODE_BUTTON_L1 -> 10
            KeyEvent.KEYCODE_BUTTON_R1 -> 11
            KeyEvent.KEYCODE_BUTTON_L2 -> 12
            KeyEvent.KEYCODE_BUTTON_R2 -> 13
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 14
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 15
            else -> null
        }
        if (id != null && started) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> NativeBridge.setButton(id, true)
                KeyEvent.ACTION_UP -> NativeBridge.setButton(id, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val EXTRA_GAME_URI = "gameUri"
        private const val EXTRA_GAME_ID = "gameId"
        private const val EXTRA_GAME_TITLE = "gameTitle"
        private const val EXTRA_EXTENSION = "extension"
        private const val EXTRA_FOLDER_URI = "folderUri"
        private const val EXTRA_COMPANION_URIS = "companionUris"

        fun intent(context: Context, game: GameEntry, extension: String): Intent =
            Intent(context, EmulationActivity::class.java).apply {
                putExtra(EXTRA_GAME_URI, game.uri)
                putExtra(EXTRA_GAME_ID, game.id)
                putExtra(EXTRA_GAME_TITLE, game.title)
                putExtra(EXTRA_EXTENSION, extension)
                putExtra(EXTRA_FOLDER_URI, game.folderUri)
                putStringArrayListExtra(EXTRA_COMPANION_URIS, ArrayList(game.companionUris))
            }
    }
}
