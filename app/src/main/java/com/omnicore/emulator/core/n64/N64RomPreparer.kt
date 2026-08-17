package com.omnicore.emulator.core.n64

import android.content.Context
import android.net.Uri
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.storage.N64Storage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/**
 * Materializes SAF-backed N64 ROMs into the N64-only cache and normalizes byte
 * order to canonical big-endian (.z64). The source file is never modified.
 */
object N64RomPreparer {
    data class PreparedRom(
        val file: File,
        val sourceOrder: N64RomByteOrder,
        val reusedCache: Boolean
    )

    fun prepare(context: Context, game: GameEntry): Result<PreparedRom> = runCatching {
        val validation = N64RomValidator.validate(context, game).getOrThrow()
        val paths = N64Storage.prepare(context)
        val key = N64Storage.safeGameKey(game.id)
        val target = File(paths.cache, "$key.z64")

        if (target.isFile && target.length() > 0L && (game.sizeBytes <= 0L || target.length() == game.sizeBytes)) {
            return@runCatching PreparedRom(target, validation.byteOrder, true)
        }

        val temp = File(paths.cache, "$key.z64.tmp")
        if (temp.exists()) temp.delete()
        val uri = Uri.parse(game.uri)
        context.contentResolver.openInputStream(uri)?.use { rawInput ->
            BufferedInputStream(rawInput, 256 * 1024).use { input ->
                BufferedOutputStream(temp.outputStream(), 256 * 1024).use { output ->
                    when (validation.byteOrder) {
                        N64RomByteOrder.BIG_ENDIAN_Z64 -> input.copyTo(output, 256 * 1024)
                        N64RomByteOrder.BYTE_SWAPPED_V64 -> swapPairs(input, output)
                        N64RomByteOrder.LITTLE_ENDIAN_N64 -> reverseWords(input, output)
                    }
                }
            }
        } ?: error("Não foi possível abrir a ROM Nintendo 64.")

        require(temp.length() > 0L) { "A preparação da ROM Nintendo 64 gerou um arquivo vazio." }
        if (game.sizeBytes > 0L) {
            require(temp.length() == game.sizeBytes) { "O tamanho da ROM mudou durante a preparação." }
        }
        require(temp.renameTo(target) || run {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            true
        }) { "Não foi possível finalizar o cache Nintendo 64." }

        PreparedRom(target, validation.byteOrder, false)
    }

    private fun swapPairs(input: BufferedInputStream, output: BufferedOutputStream) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count % 2 == 0) { "ROM V64 possui tamanho inválido para byte swap." }
            var index = 0
            while (index < count) {
                val a = buffer[index]
                buffer[index] = buffer[index + 1]
                buffer[index + 1] = a
                index += 2
            }
            output.write(buffer, 0, count)
        }
    }

    private fun reverseWords(input: BufferedInputStream, output: BufferedOutputStream) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count % 4 == 0) { "ROM N64 possui tamanho inválido para word swap." }
            var index = 0
            while (index < count) {
                val a = buffer[index]
                val b = buffer[index + 1]
                buffer[index] = buffer[index + 3]
                buffer[index + 1] = buffer[index + 2]
                buffer[index + 2] = b
                buffer[index + 3] = a
                index += 4
            }
            output.write(buffer, 0, count)
        }
    }
}
