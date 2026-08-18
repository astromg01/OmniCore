package com.omnicore.emulator.storage

import android.content.Context
import java.io.File

/** Files owned only by the Nintendo 64 backend. */
object N64Storage {
    data class Paths(
        val root: File,
        val saves: File,
        val states: File,
        val system: File,
        val cache: File
    )

    fun prepare(context: Context): Paths {
        val root = File(context.filesDir, "n64")
        val saves = File(root, "saves")
        val states = File(root, "states")
        val system = File(root, "system")
        val shaderCache = File(system, "Mupen64plus/shaders")
        val cache = File(context.cacheDir, "n64")
        // GLideN64 resolves its user cache from RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY
        // and appends Mupen64plus/shaders. Keep it under filesDir, never cacheDir,
        // so compiled combiner programs survive app/process restarts.
        listOf(root, saves, states, system, shaderCache, cache).forEach { directory ->
            check(directory.exists() || directory.mkdirs()) { "Não foi possível preparar ${directory.absolutePath}" }
        }
        return Paths(root, saves, states, system, cache)
    }

    fun stateFile(paths: Paths, gameKey: String, slot: Int): File {
        val safeSlot = slot.coerceIn(1, 5)
        return File(paths.states, "${safeGameKey(gameKey)}.slot$safeSlot.state")
    }

    fun saveRamFile(paths: Paths, gameKey: String): File =
        File(paths.saves, "${safeGameKey(gameKey)}.srm")

    fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "n64-game" }
}
