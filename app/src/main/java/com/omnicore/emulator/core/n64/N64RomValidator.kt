package com.omnicore.emulator.core.n64

import android.content.Context
import android.net.Uri
import com.omnicore.emulator.model.GameEntry

enum class N64RomByteOrder(val label: String) {
    BIG_ENDIAN_Z64("z64 / big-endian"),
    BYTE_SWAPPED_V64("v64 / byte-swapped"),
    LITTLE_ENDIAN_N64("n64 / little-endian")
}

data class N64RomValidation(
    val byteOrder: N64RomByteOrder,
    val extension: String,
    val extensionMatchesHeader: Boolean
)

object N64RomValidator {
    private val supported = setOf("z64", "n64", "v64")

    fun validate(context: Context, game: GameEntry): Result<N64RomValidation> = runCatching {
        val extension = game.fileName.substringAfterLast('.', "").lowercase()
        require(extension in supported) { "Formato Nintendo 64 não suportado: .$extension" }

        val uri = Uri.parse(game.uri)
        val header = ByteArray(4)
        val count = context.contentResolver.openInputStream(uri)?.use { stream ->
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        } ?: 0
        require(count == 4) { "Não foi possível ler o cabeçalho da ROM Nintendo 64." }

        val order = when (header.map { it.toInt() and 0xFF }) {
            listOf(0x80, 0x37, 0x12, 0x40) -> N64RomByteOrder.BIG_ENDIAN_Z64
            listOf(0x37, 0x80, 0x40, 0x12) -> N64RomByteOrder.BYTE_SWAPPED_V64
            listOf(0x40, 0x12, 0x37, 0x80) -> N64RomByteOrder.LITTLE_ENDIAN_N64
            else -> error("O arquivo não possui um cabeçalho ROM Nintendo 64 reconhecido.")
        }
        val expected = when (order) {
            N64RomByteOrder.BIG_ENDIAN_Z64 -> "z64"
            N64RomByteOrder.BYTE_SWAPPED_V64 -> "v64"
            N64RomByteOrder.LITTLE_ENDIAN_N64 -> "n64"
        }
        N64RomValidation(order, extension, extension == expected)
    }
}
