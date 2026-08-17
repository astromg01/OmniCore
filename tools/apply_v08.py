from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# --- Ps1Settings: boot logo + presentation aspect modes, keep 0.7 video path. ---
ps1_settings = r'''package com.omnicore.emulator.settings

import android.content.Context

object Ps1Settings {
    enum class Preset(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Equilibra qualidade, temperatura e estabilidade"),
        PERFORMANCE("performance", "Desempenho", "Menor carga e maior margem para aparelhos modestos"),
        BALANCED("balanced", "Equilibrado", "Fidelidade original com boa estabilidade"),
        QUALITY("quality", "Qualidade", "Resolução aprimorada e maior fidelidade visual"),
        CUSTOM("custom", "Custom", "Ajustes avançados definidos por você")
    }

    enum class AspectMode(val storage: String, val label: String, val subtitle: String) {
        ORIGINAL_4_3("4_3", "4:3 original", "Proporção clássica do PlayStation"),
        WIDE_16_9("16_9", "16:9", "Expande a apresentação para widescreen"),
        FULLSCREEN("fullscreen", "Tela cheia", "Preenche toda a área disponível")
    }

    data class Config(
        val preset: Preset,
        val enhancedResolution: Boolean,
        val enhancedSpeedHack: Boolean,
        val textureAdjustment: Boolean,
        val dithering: Boolean,
        val threadedGpu: Boolean,
        val threadedSpu: Boolean,
        val frameskipAuto: Boolean,
        val cdReadAhead: Int,
        val interpolation: String,
        val dualShock: Boolean,
        val showBiosBootLogo: Boolean,
        val aspectMode: AspectMode
    ) {
        fun toCoreOptions(): String = buildList {
            add("pcsx_rearmed_bios=auto")
            add("pcsx_rearmed_drc=enabled")
            add("pcsx_rearmed_drc_thread=auto")
            add("pcsx_rearmed_gpu_thread_rendering=${if (!threadedGpu) "disabled" else if (preset == Preset.PERFORMANCE) "enabled" else "auto"}")
            add("pcsx_rearmed_spu_thread=${if (threadedSpu) "enabled" else "disabled"}")
            add("pcsx_rearmed_neon_enhancement_enable=${if (enhancedResolution) "enabled" else "disabled"}")
            add("pcsx_rearmed_neon_enhancement_no_main=${if (enhancedSpeedHack) "enabled" else "disabled"}")
            add("pcsx_rearmed_neon_enhancement_tex_adj_v2=${if (textureAdjustment) "enabled" else "disabled"}")
            add("pcsx_rearmed_dithering=${if (dithering) "enabled" else "disabled"}")
            add("pcsx_rearmed_frameskip_type=${if (frameskipAuto) "auto" else "disabled"}")
            add("pcsx_rearmed_frameskip_threshold=33")
            add("pcsx_rearmed_cd_readahead=${cdReadAhead.coerceIn(0, 128)}")
            add("pcsx_rearmed_spu_interpolation=${if (interpolation in setOf("simple", "gaussian", "cubic", "off")) interpolation else "simple"}")
            add("pcsx_rearmed_spu_reverb=${if (preset == Preset.PERFORMANCE) "disabled" else "enabled"}")
            add("pcsx_rearmed_region=auto")
            add("pcsx_rearmed_psxclock=auto")
            add("pcsx_rearmed_cd_turbo=disabled")
            add("pcsx_rearmed_nostalls=disabled")
            add("pcsx_rearmed_icache_emulation=enabled")
            add("pcsx_rearmed_exception_emulation=disabled")
            add("pcsx_rearmed_gpu_slow_llists=auto")
            add("pcsx_rearmed_fractional_framerate=auto")
            add("pcsx_rearmed_neon_interlace_enable_v2=auto")
            add("pcsx_rearmed_rgb32_output=enabled")
            add("pcsx_rearmed_noxadecoding=disabled")
            add("pcsx_rearmed_nocdaudio=disabled")
            add("pcsx_rearmed_show_bios_bootlogo=${if (showBiosBootLogo) "enabled" else "disabled"}")
            add("pcsx_rearmed_memcard1=libretro")
        }.joinToString("\n")
    }

    private const val PREFS = "ps1_settings"
    private const val KEY_PRESET = "preset"
    private const val KEY_DUALSHOCK = "dualshock"
    private const val KEY_BOOT_LOGO = "bios_boot_logo"
    private const val KEY_ASPECT_MODE = "aspect_mode"
    private const val K_ENHANCED = "enhanced"
    private const val K_SPEED = "enhanced_speed"
    private const val K_TEXTURE = "texture_adj"
    private const val K_DITHER = "dither"
    private const val K_GPU_THREAD = "gpu_thread"
    private const val K_SPU_THREAD = "spu_thread"
    private const val K_FRAMESKIP = "frameskip"
    private const val K_READAHEAD = "readahead"
    private const val K_INTERPOLATION = "interpolation"

    fun readPreset(context: Context): Preset {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, Preset.SMART.storage)
        return Preset.entries.firstOrNull { it.storage == raw } ?: Preset.SMART
    }

    fun savePreset(context: Context, preset: Preset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PRESET, preset.storage).apply()
    }

    fun readDualShock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUALSHOCK, true)

    fun saveDualShock(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DUALSHOCK, enabled).apply()
    }

    fun readBiosBootLogo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BOOT_LOGO, true)

    fun saveBiosBootLogo(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BOOT_LOGO, enabled).apply()
    }

    fun readAspectMode(context: Context): AspectMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASPECT_MODE, AspectMode.ORIGINAL_4_3.storage)
        return AspectMode.entries.firstOrNull { it.storage == raw } ?: AspectMode.ORIGINAL_4_3
    }

    fun saveAspectMode(context: Context, mode: AspectMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ASPECT_MODE, mode.storage).apply()
    }

    private fun presetConfig(
        preset: Preset,
        dualShock: Boolean,
        showBiosBootLogo: Boolean,
        aspectMode: AspectMode
    ): Config = when (preset) {
        Preset.PERFORMANCE -> Config(preset, false, false, false, false, true, true, true, 32, "simple", dualShock, showBiosBootLogo, aspectMode)
        Preset.BALANCED -> Config(preset, false, false, true, true, false, false, false, 8, "simple", dualShock, showBiosBootLogo, aspectMode)
        Preset.QUALITY -> Config(preset, true, false, true, true, false, false, false, 8, "gaussian", dualShock, showBiosBootLogo, aspectMode)
        else -> Config(Preset.SMART, false, false, false, true, false, false, false, 8, "simple", dualShock, showBiosBootLogo, aspectMode)
    }

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val preset = readPreset(context)
        val dualShock = readDualShock(context)
        val bootLogo = readBiosBootLogo(context)
        val aspectMode = readAspectMode(context)
        if (preset != Preset.CUSTOM) return presetConfig(preset, dualShock, bootLogo, aspectMode)
        val fallback = presetConfig(Preset.SMART, dualShock, bootLogo, aspectMode)
        return Config(
            preset = Preset.CUSTOM,
            enhancedResolution = prefs.getBoolean(K_ENHANCED, fallback.enhancedResolution),
            enhancedSpeedHack = prefs.getBoolean(K_SPEED, fallback.enhancedSpeedHack),
            textureAdjustment = prefs.getBoolean(K_TEXTURE, fallback.textureAdjustment),
            dithering = prefs.getBoolean(K_DITHER, fallback.dithering),
            threadedGpu = prefs.getBoolean(K_GPU_THREAD, fallback.threadedGpu),
            threadedSpu = prefs.getBoolean(K_SPU_THREAD, fallback.threadedSpu),
            frameskipAuto = prefs.getBoolean(K_FRAMESKIP, fallback.frameskipAuto),
            cdReadAhead = prefs.getInt(K_READAHEAD, fallback.cdReadAhead),
            interpolation = prefs.getString(K_INTERPOLATION, fallback.interpolation) ?: fallback.interpolation,
            dualShock = dualShock,
            showBiosBootLogo = bootLogo,
            aspectMode = aspectMode
        )
    }

    fun saveCustom(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESET, Preset.CUSTOM.storage)
            .putBoolean(K_ENHANCED, config.enhancedResolution)
            .putBoolean(K_SPEED, config.enhancedSpeedHack)
            .putBoolean(K_TEXTURE, config.textureAdjustment)
            .putBoolean(K_DITHER, config.dithering)
            .putBoolean(K_GPU_THREAD, config.threadedGpu)
            .putBoolean(K_SPU_THREAD, config.threadedSpu)
            .putBoolean(K_FRAMESKIP, config.frameskipAuto)
            .putInt(K_READAHEAD, config.cdReadAhead)
            .putString(K_INTERPOLATION, config.interpolation)
            .putBoolean(KEY_BOOT_LOGO, config.showBiosBootLogo)
            .putString(KEY_ASPECT_MODE, config.aspectMode.storage)
            .apply()
    }
}
'''
write("app/src/main/java/com/omnicore/emulator/settings/Ps1Settings.kt", ps1_settings)

# --- SAF metadata: include source modification time for cache invalidation. ---
saf = read("app/src/main/java/com/omnicore/emulator/storage/SafGameSource.kt")
saf = replace_once(
    saf,
    '        val sizeBytes: Long = 0L,\n        val mimeType: String? = null\n',
    '        val sizeBytes: Long = 0L,\n        val mimeType: String? = null,\n        val lastModifiedMillis: Long = 0L\n',
    "SafGameSource.Document"
)
saf = replace_once(
    saf,
    '            DocumentsContract.Document.COLUMN_MIME_TYPE,\n            DocumentsContract.Document.COLUMN_SIZE\n',
    '            DocumentsContract.Document.COLUMN_MIME_TYPE,\n            DocumentsContract.Document.COLUMN_SIZE,\n            DocumentsContract.Document.COLUMN_LAST_MODIFIED\n',
    "folder projection"
)
saf = replace_once(
    saf,
    '                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)\n',
    '                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)\n                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)\n',
    "modified index"
)
saf = replace_once(
    saf,
    '                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L\n                    add(\n',
    '                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L\n                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L\n                    add(\n',
    "modified value"
)
saf = replace_once(
    saf,
    '                            sizeBytes = size,\n                            mimeType = mime\n',
    '                            sizeBytes = size,\n                            mimeType = mime,\n                            lastModifiedMillis = modified\n',
    "folder document"
)
saf = replace_once(
    saf,
    '        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)\n',
    '        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)\n',
    "metadata projection"
)
saf = replace_once(
    saf,
    '                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)\n                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment ?: "game"\n                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L\n                return Document(uri = uri, name = name, sizeBytes = size)\n',
    '                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)\n                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)\n                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment ?: "game"\n                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L\n                val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L\n                return Document(uri = uri, name = name, sizeBytes = size, lastModifiedMillis = modified)\n',
    "metadata document"
)
write("app/src/main/java/com/omnicore/emulator/storage/SafGameSource.kt", saf)

# --- EmulationActivity: persistent validated CUE/BIN cache + aspect modes. ---
emu = read("app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt")
emu = replace_once(emu, 'import java.io.File\n', 'import java.io.File\nimport java.security.MessageDigest\n', "MessageDigest import")
emu = replace_once(
    emu,
    '    private var sessionDir: File? = null\n    private var preparationThread: Thread? = null\n',
    '    private var sessionDir: File? = null\n    private var sessionPersistent = false\n    private var preparationThread: Thread? = null\n',
    "sessionPersistent field"
)
old_layout = '''            val targetWidth = minOf(width, (height * 4f / 3f).toInt())
            val targetHeight = minOf(height, (targetWidth * 3f / 4f).toInt())
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
'''
new_layout = '''            val (targetWidth, targetHeight) = when (ps1Config.aspectMode) {
                Ps1Settings.AspectMode.ORIGINAL_4_3 -> fitAspect(width, height, 4f / 3f)
                Ps1Settings.AspectMode.WIDE_16_9 -> fitAspect(width, height, 16f / 9f)
                Ps1Settings.AspectMode.FULLSCREEN -> width to height
            }
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
'''
emu = replace_once(emu, old_layout, new_layout, "aspect layout")
old_prepared = '''    private data class PreparedContent(
        val path: String,
        val descriptors: List<ParcelFileDescriptor>,
        val sessionDir: File
    ) : AutoCloseable {
        override fun close() {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            runCatching { sessionDir.deleteRecursively() }
        }
    }
'''
new_prepared = '''    private data class PreparedContent(
        val path: String,
        val descriptors: List<ParcelFileDescriptor>,
        val sessionDir: File,
        val persistent: Boolean = false
    ) : AutoCloseable {
        override fun close() {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            if (!persistent) runCatching { sessionDir.deleteRecursively() }
        }
    }
'''
emu = replace_once(emu, old_prepared, new_prepared, "PreparedContent")
emu = replace_once(
    emu,
    '                    sessionDir = prepared.sessionDir\n                    gamePath = prepared.path\n',
    '                    sessionDir = prepared.sessionDir\n                    sessionPersistent = prepared.persistent\n                    gamePath = prepared.path\n',
    "prepared persistence assignment"
)

cue_pattern = re.compile(r'    private fun prepareCueSession\(.*?\n(?=    private fun freshSessionDir\(\): File \{)', re.S)
cue_match = cue_pattern.search(emu)
if not cue_match:
    raise SystemExit("prepareCueSession block not found")
new_cue = r'''    private fun prepareCueSession(cueUri: Uri, folderUri: Uri?, companionUris: List<Uri>): PreparedContent {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val dir = cueCacheDir()
        return try {
            ensurePreparationActive()
            val sources = if (folderUri != null) {
                SafGameSource.listDirectChildren(this, folderUri).filterNot { it.isDirectory }
            } else {
                (companionUris + cueUri).distinctBy(Uri::toString).map { SafGameSource.metadata(this, it) }
            }

            val cueText = SafGameSource.readCueText(this, cueUri)
            val references = SafGameSource.cueReferences(cueText)
            require(references.isNotEmpty()) { "O CUE não contém nenhuma linha FILE reconhecível." }

            val byName = sources.associateBy { it.name.lowercase() }
            val requiredTracks = references.map { reference ->
                val baseName = SafGameSource.normalizeReference(reference)
                byName[baseName.lowercase()]
                    ?: error("Faixa '$baseName' citada no CUE não encontrada. Importe a pasta completa.")
            }
            val auxiliaries = sources.filter { it.extension == "sbi" }
            val fingerprint = cueFingerprint(cueText, (requiredTracks + auxiliaries).distinctBy { it.uri.toString() })
            val marker = File(dir, ".source-fingerprint")
            val localCue = File(dir, "game.cue")

            val cacheValid = runCatching {
                marker.isFile && marker.readText(Charsets.UTF_8) == fingerprint &&
                    localCue.isFile && localCue.length() > 0L &&
                    validateCueSession(localCue).let { true }
            }.getOrDefault(false)

            if (cacheValid) {
                statusView.post { statusView.text = "PREP 2/3 • cache CUE/BIN validado — início rápido" }
                return PreparedContent(localCue.absolutePath, emptyList(), dir, persistent = true)
            }

            runCatching { dir.deleteRecursively() }
            require(dir.mkdirs() || dir.isDirectory) { "Não consegui criar o cache persistente do jogo." }
            statusView.post { statusView.text = "PREP 2/3 • preparando ${references.size} faixa(s) pela primeira vez…" }

            val resolvedNames = mutableMapOf<String, String>()
            requiredTracks.forEachIndexed { index, source ->
                ensurePreparationActive()
                val reference = references[index]
                val baseName = SafGameSource.normalizeReference(reference)
                val safeName = safeFileName(source.name)
                resolvedNames[baseName.lowercase()] = safeName
                stageDocument(source.uri, File(dir, safeName), descriptors, forceCopy = true)
            }

            auxiliaries.forEach { source ->
                stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors, forceCopy = true)
            }

            val rewrittenCue = SafGameSource.rewriteCueReferences(cueText, resolvedNames)
                .removePrefix("\uFEFF")
            localCue.writeText(rewrittenCue, Charsets.UTF_8)
            validateCueSession(localCue)
            marker.writeText(fingerprint, Charsets.UTF_8)
            ensurePreparationActive()
            PreparedContent(localCue.absolutePath, descriptors.toList(), dir, persistent = true)
        } catch (error: Throwable) {
            descriptors.forEach { runCatching { it.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun cueCacheDir(): File {
        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir, "ps1-disc-cache/$safeKey")
    }

    private fun cueFingerprint(cueText: String, sources: List<SafGameSource.Document>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(cueText.toByteArray(Charsets.UTF_8))
        sources.sortedBy { it.name.lowercase() }.forEach { source ->
            digest.update(0.toByte())
            digest.update(source.name.lowercase().toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(source.sizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(source.lastModifiedMillis.toString().toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(source.uri.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

'''
emu = emu[:cue_match.start()] + new_cue + emu[cue_match.end():]

emu = replace_once(
    emu,
    '        runCatching { sessionDir?.deleteRecursively() }\n        sessionDir = null\n',
    '        if (!sessionPersistent) runCatching { sessionDir?.deleteRecursively() }\n        sessionDir = null\n        sessionPersistent = false\n',
    "persistent cache destroy policy"
)
emu = replace_once(
    emu,
    '    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()\n',
    '''    private fun fitAspect(width: Int, height: Int, aspect: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || aspect <= 0f) return width to height
        val widthFromHeight = (height * aspect).toInt().coerceAtLeast(1)
        return if (widthFromHeight <= width) {
            widthFromHeight to height
        } else {
            width to (width / aspect).toInt().coerceAtLeast(1)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
''',
    "fitAspect helper"
)
emu = replace_once(emu, '        private const val COPY_BUFFER_BYTES = 256 * 1024\n', '        private const val COPY_BUFFER_BYTES = 2 * 1024 * 1024\n', "copy buffer")
write("app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt", emu)

# --- Tuning UI: expose boot logo, aspect presentation and cache management. ---
ui = read("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt")
ui = replace_once(
    ui,
    '    var updateStatus by remember { mutableStateOf("Canal DEV • pronto para verificar") }\n    var updateRelease by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }\n',
    '    var updateStatus by remember { mutableStateOf("Canal DEV • pronto para verificar") }\n    var updateRelease by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }\n    var cacheStatus by remember { mutableStateOf("CUE/BIN será reutilizado após a primeira preparação.") }\n',
    "cache status state"
)
anchor = '''        item {
            HubSection("Performance e áudio", "Controles de estabilidade para aparelhos com diferentes limites térmicos.") {
'''
insert = '''        item {
            HubSection("Tela e inicialização", "Mantém a base de vídeo 0.7 e adiciona apresentação configurável.") {
                SettingSwitch(
                    "Boot clássico do PS1",
                    "Com uma BIOS real válida, mostra a tela/logo clássico antes do jogo. Pode ser desligado para máxima compatibilidade.",
                    config.showBiosBootLogo
                ) {
                    Ps1Settings.saveBiosBootLogo(context, it)
                    refresh()
                }
                Text("Formato de tela", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.AspectMode.entries) { mode ->
                        FilterChip(
                            selected = config.aspectMode == mode,
                            onClick = { Ps1Settings.saveAspectMode(context, mode); refresh() },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(
                    when (config.aspectMode) {
                        Ps1Settings.AspectMode.ORIGINAL_4_3 -> "4:3 preserva a proporção original do console."
                        Ps1Settings.AspectMode.WIDE_16_9 -> "16:9 expande a apresentação. Não é um patch widescreen de geometria 3D por jogo."
                        Ps1Settings.AspectMode.FULLSCREEN -> "Tela cheia preenche toda a área disponível e pode deformar a proporção."
                    },
                    color = HubSoft,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            HubSection("Início rápido PS1", "CUE/BIN é preparado uma vez e reaproveitado enquanto a origem não mudar.") {
                Text(cacheStatus, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    val cacheDir = context.cacheDir.resolve("ps1-disc-cache")
                    val cleared = !cacheDir.exists() || cacheDir.deleteRecursively()
                    cacheStatus = if (cleared) "Cache CUE/BIN limpo. O próximo boot fará uma nova preparação." else "Não consegui limpar todo o cache agora."
                }) { Text("Limpar cache CUE/BIN") }
                Text(
                    "O Android ainda pode limpar este cache automaticamente quando precisar de espaço.",
                    color = Color(0xFF737C98),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
'''
if anchor not in ui:
    raise SystemExit("HubTuning performance anchor not found")
ui = ui.replace(anchor, insert + anchor, 1)
write("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt", ui)

# --- Version metadata; Runtime remains v7 because stable native video path is intentionally unchanged. ---
gradle = read("app/build.gradle.kts")
gradle = replace_once(gradle, '        versionCode = 9\n        versionName = "0.7.0"\n', '        versionCode = 10\n        versionName = "0.8.0"\n', "version bump")
write("app/build.gradle.kts", gradle)

bridge = read("app/src/main/cpp/native_bridge.cpp")
bridge = replace_once(
    bridge,
    '    return env->NewStringUTF("OmniCore Native Runtime 0.6.0 / libretro host v7 / EGL-GLES presenter");\n',
    '    return env->NewStringUTF("OmniCore Native Runtime 0.8.0 / libretro host v7 / EGL-GLES presenter");\n',
    "runtime version string"
)
write("app/src/main/cpp/native_bridge.cpp", bridge)

print("OmniCore v0.8.0 migration applied successfully")
