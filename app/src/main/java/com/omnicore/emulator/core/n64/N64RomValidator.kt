package com.omnicore.emulator.core.n64

import android.content.Context
import android.net.Uri
import com.omnicore.emulator.model.GameEntry
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

enum class N64RomByteOrder(val label: String) {
    BIG_ENDIAN_Z64("z64 / big-endian"),
    BYTE_SWAPPED_V64("v64 / byte-swapped"),
    LITTLE_ENDIAN_N64("n64 / little-endian")
}

enum class N64RomContainer(val label: String) {
    RAW("ROM"),
    ZIP("ZIP"),
    GZIP("GZIP")
}

data class N64RomValidation(
    val byteOrder: N64RomByteOrder,
    val extension: String,
    val extensionMatchesHeader: Boolean,
    val container: N64RomContainer = N64RomContainer.RAW,
    val archiveEntryName: String? = null
)

/**
 * Nintendo 64 detection is signature-first. Extensions are only hints.
 * This lets OmniCore accept correctly formed dumps named .rom/.bin/.u1 or with
 * no extension, plus common ZIP/GZIP wrappers, without misidentifying random files.
 */
object N64RomValidator {
    val commonExtensions = setOf("z64", "n64", "v64", "u1", "rom", "bin", "zip", "gz", "gzip")

    fun validate(context: Context, game: GameEntry): Result<N64RomValidation> =
        validate(context, Uri.parse(game.uri), game.fileName)

    fun validate(context: Context, uri: Uri, fileName: String): Result<N64RomValidation> = runCatching {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        when (extension) {
            "zip" -> validateZip(context, uri, extension)
            "gz", "gzip" -> validateGzip(context, uri, extension)
            else -> validateRaw(context, uri, extension)
        }
    }

    /** Cheap signature probe used by the multi-system library scanner. */
    fun isNintendo64(context: Context, uri: Uri, fileName: String): Boolean =
        validate(context, uri, fileName).isSuccess

    private fun validateRaw(context: Context, uri: Uri, extension: String): N64RomValidation {
        val order = context.contentResolver.openInputStream(uri)?.use { input ->
            detectOrder(BufferedInputStream(input, HEADER_BUFFER))
        } ?: error("Não foi possível abrir a ROM Nintendo 64.")
        return validation(order, extension, N64RomContainer.RAW, null)
    }

    private fun validateGzip(context: Context, uri: Uri, extension: String): N64RomValidation {
        val order = context.contentResolver.openInputStream(uri)?.use { raw ->
            GZIPInputStream(BufferedInputStream(raw, HEADER_BUFFER)).use(::detectOrder)
        } ?: error("Não foi possível abrir o arquivo GZIP Nintendo 64.")
        return validation(order, extension, N64RomContainer.GZIP, null)
    }

    private fun validateZip(context: Context, uri: Uri, extension: String): N64RomValidation {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw, ZIP_BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val result = runCatching { detectOrder(zip) }.getOrNull()
                    if (result != null) {
                        val innerExtension = entry.name.substringAfterLast('.', "").lowercase()
                        return validation(
                            order = result,
                            extension = innerExtension.ifBlank { extension },
                            container = N64RomContainer.ZIP,
                            archiveEntryName = entry.name
                        )
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Não foi possível abrir o arquivo ZIP Nintendo 64.")
        error("O ZIP não contém uma ROM Nintendo 64 reconhecida.")
    }

    private fun validation(
        order: N64RomByteOrder,
        extension: String,
        container: N64RomContainer,
        archiveEntryName: String?
    ): N64RomValidation {
        val expected = when (order) {
            N64RomByteOrder.BIG_ENDIAN_Z64 -> "z64"
            N64RomByteOrder.BYTE_SWAPPED_V64 -> "v64"
            N64RomByteOrder.LITTLE_ENDIAN_N64 -> "n64"
        }
        return N64RomValidation(
            byteOrder = order,
            extension = extension,
            extensionMatchesHeader = extension == expected,
            container = container,
            archiveEntryName = archiveEntryName
        )
    }

    private fun detectOrder(input: InputStream): N64RomByteOrder {
        val header = ByteArray(4)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read < 0) break
            offset += read
        }
        require(offset == 4) { "Não foi possível ler o cabeçalho da ROM Nintendo 64." }
        return when (header.map { it.toInt() and 0xFF }) {
            listOf(0x80, 0x37, 0x12, 0x40) -> N64RomByteOrder.BIG_ENDIAN_Z64
            listOf(0x37, 0x80, 0x40, 0x12) -> N64RomByteOrder.BYTE_SWAPPED_V64
            listOf(0x40, 0x12, 0x37, 0x80) -> N64RomByteOrder.LITTLE_ENDIAN_N64
            else -> error("O arquivo não possui um cabeçalho ROM Nintendo 64 reconhecido.")
        }
    }

    private const val HEADER_BUFFER = 8 * 1024
    private const val ZIP_BUFFER = 64 * 1024
}
