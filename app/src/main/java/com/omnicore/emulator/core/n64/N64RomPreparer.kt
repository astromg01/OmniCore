package com.omnicore.emulator.core.n64

import android.content.Context
import android.net.Uri
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.storage.N64Storage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Materializes SAF-backed N64 ROMs into the N64-only cache and normalizes byte
 * order to canonical big-endian (.z64). Raw ROMs plus ZIP/GZIP wrappers are
 * supported. The source file is never modified.
 */
object N64RomPreparer {
    data class PreparedRom(
        val file: File,
        val sourceOrder: N64RomByteOrder,
        val reusedCache: Boolean,
        val sourceContainer: N64RomContainer
    )

    fun prepare(context: Context, game: GameEntry): Result<PreparedRom> = runCatching {
        val validation = N64RomValidator.validate(context, game).getOrThrow()
        val paths = N64Storage.prepare(context)
        val key = N64Storage.safeGameKey(game.id)
        val target = File(paths.cache, "$key.z64")
        val meta = File(paths.cache, "$key.meta")
        val fingerprint = buildFingerprint(game, validation)

        if (target.isFile && target.length() > 0L && meta.isFile && meta.readText() == fingerprint) {
            return@runCatching PreparedRom(
                file = target,
                sourceOrder = validation.byteOrder,
                reusedCache = true,
                sourceContainer = validation.container
            )
        }

        val temp = File(paths.cache, "$key.z64.tmp")
        if (temp.exists()) temp.delete()

        openPayload(context, Uri.parse(game.uri), validation).use { payload ->
            BufferedInputStream(payload, IO_BUFFER).use { input ->
                BufferedOutputStream(temp.outputStream(), IO_BUFFER).use { output ->
                    when (validation.byteOrder) {
                        N64RomByteOrder.BIG_ENDIAN_Z64 -> copyLimited(input, output)
                        N64RomByteOrder.BYTE_SWAPPED_V64 -> transformUnits(input, output, 2)
                        N64RomByteOrder.LITTLE_ENDIAN_N64 -> transformUnits(input, output, 4)
                    }
                }
            }
        }

        require(temp.length() in MIN_ROM_BYTES..MAX_ROM_BYTES) {
            "A ROM Nintendo 64 preparada possui tamanho inválido (${temp.length()} bytes)."
        }

        if (target.exists()) target.delete()
        require(temp.renameTo(target) || run {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            true
        }) { "Não foi possível finalizar o cache Nintendo 64." }
        meta.writeText(fingerprint)

        PreparedRom(
            file = target,
            sourceOrder = validation.byteOrder,
            reusedCache = false,
            sourceContainer = validation.container
        )
    }

    private fun openPayload(context: Context, uri: Uri, validation: N64RomValidation): InputStream {
        val raw = context.contentResolver.openInputStream(uri)
            ?: error("Não foi possível abrir a ROM Nintendo 64.")
        return when (validation.container) {
            N64RomContainer.RAW -> raw
            N64RomContainer.GZIP -> GZIPInputStream(BufferedInputStream(raw, 64 * 1024))
            N64RomContainer.ZIP -> {
                val zip = ZipInputStream(BufferedInputStream(raw, 64 * 1024))
                val wanted = validation.archiveEntryName
                    ?: error("Entrada Nintendo 64 ausente no ZIP.")
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == wanted) {
                        found = true
                        break
                    }
                    zip.closeEntry()
                }
                if (!found) {
                    zip.close()
                    error("A ROM Nintendo 64 não foi reencontrada dentro do ZIP.")
                }
                zip
            }
        }
    }

    private fun buildFingerprint(game: GameEntry, validation: N64RomValidation): String = buildString {
        append(game.uri).append('\n')
        append(game.fileName).append('\n')
        append(game.sizeBytes).append('\n')
        append(validation.container.name).append('\n')
        append(validation.archiveEntryName.orEmpty()).append('\n')
        append(validation.byteOrder.name)
    }

    private fun copyLimited(input: InputStream, output: BufferedOutputStream) {
        val buffer = ByteArray(IO_BUFFER)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= MAX_ROM_BYTES) { "ROM Nintendo 64 excede o limite de segurança." }
            output.write(buffer, 0, count)
        }
    }

    private fun transformUnits(input: InputStream, output: BufferedOutputStream, unitSize: Int) {
        require(unitSize == 2 || unitSize == 4)
        val buffer = ByteArray(IO_BUFFER + unitSize)
        var carry = 0
        var totalWritten = 0L
        while (true) {
            val count = input.read(buffer, carry, buffer.size - carry)
            if (count < 0) break
            if (count == 0) continue
            val total = carry + count
            val processCount = total - (total % unitSize)
            var index = 0
            while (index < processCount) {
                if (unitSize == 2) {
                    val a = buffer[index]
                    buffer[index] = buffer[index + 1]
                    buffer[index + 1] = a
                } else {
                    val a = buffer[index]
                    val b = buffer[index + 1]
                    buffer[index] = buffer[index + 3]
                    buffer[index + 1] = buffer[index + 2]
                    buffer[index + 2] = b
                    buffer[index + 3] = a
                }
                index += unitSize
            }
            if (processCount > 0) {
                totalWritten += processCount
                require(totalWritten <= MAX_ROM_BYTES) { "ROM Nintendo 64 excede o limite de segurança." }
                output.write(buffer, 0, processCount)
            }
            carry = total - processCount
            if (carry > 0) System.arraycopy(buffer, processCount, buffer, 0, carry)
        }
        require(carry == 0) { "ROM Nintendo 64 possui tamanho inválido para conversão de byte order." }
    }

    private const val IO_BUFFER = 256 * 1024
    private const val MIN_ROM_BYTES = 256 * 1024L
    private const val MAX_ROM_BYTES = 128L * 1024L * 1024L
}
