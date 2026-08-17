package com.omnicore.emulator.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object Ps1Files {
    fun systemDir(context: Context): File = File(context.filesDir, "ps1/system").apply { mkdirs() }
    fun saveDir(context: Context): File = File(context.filesDir, "ps1/saves").apply { mkdirs() }
    fun stateDir(context: Context): File = File(context.filesDir, "ps1/states").apply { mkdirs() }

    fun biosFiles(context: Context): List<File> =
        systemDir(context).listFiles()?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun importBios(context: Context, uri: Uri): Result<File> = runCatching {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "scph5501.bin"

        require(displayName.endsWith(".bin", ignoreCase = true)) { "Selecione um BIOS de PS1 em formato .bin." }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(systemDir(context), safeName)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não consegui abrir o arquivo selecionado." }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        require(destination.length() in 128 * 1024..4L * 1024 * 1024) {
            destination.delete()
            "O arquivo selecionado não parece ser um BIOS válido de PS1."
        }
        destination
    }
}
