package com.omnicore.emulator.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Stores and validates a user-selected PS2 BIOS document reference.
 *
 * OmniCore never bundles, downloads or redistributes a Sony BIOS. Validation is
 * deliberately structural: size plus the PS2 ROMDIR/ROMVER layout used by PCSX2.
 * The current Play! backend still cannot execute an external BIOS, but the same
 * validated user selection can be handed to a future BIOS-capable backend.
 */
object PS2BiosManager {
    data class BiosInfo(
        val uri: String,
        val displayName: String,
        val sizeBytes: Long,
        val plausible: Boolean,
        val reason: String
    )

    private data class RomVersion(
        val raw: String,
        val version: String,
        val region: String
    )

    private const val PREFS = "ps2_bios"
    private const val KEY_URI = "bios_uri"
    private const val KEY_NAME = "bios_name"
    private const val KEY_SIZE = "bios_size"

    fun read(context: Context): BiosInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(KEY_URI, null) ?: return null
        val name = prefs.getString(KEY_NAME, "PS2 BIOS") ?: "PS2 BIOS"
        val size = prefs.getLong(KEY_SIZE, -1L)
        return validate(context, Uri.parse(uri), name, size)
    }

    fun save(context: Context, uri: Uri): BiosInfo {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val meta = queryMetadata(context, uri)
        val validated = validate(context, uri, meta.first, meta.second)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, meta.first)
            .putLong(KEY_SIZE, validated.sizeBytes)
            .apply()
        return validated
    }

    fun clear(context: Context) {
        val old = read(context)
        if (old != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun validate(context: Context, uri: Uri, displayName: String? = null, sizeHint: Long = -1L): BiosInfo {
        val metadata = if (displayName == null || sizeHint < 0) queryMetadata(context, uri) else displayName to sizeHint
        val name = metadata.first
        val declaredSize = metadata.second

        val bytes = runCatching { readLimited(context, uri) }.getOrNull()
        if (bytes == null) {
            return BiosInfo(uri.toString(), name, declaredSize, false, "arquivo não pode ser lido")
        }

        val actualSize = bytes.size.toLong()
        val size = if (declaredSize >= 0L) declaredSize else actualSize
        val plausibleSize = size in MIN_PLAUSIBLE_SIZE..MAX_PLAUSIBLE_SIZE &&
            actualSize in MIN_PLAUSIBLE_SIZE..MAX_PLAUSIBLE_SIZE

        if (!plausibleSize) {
            return BiosInfo(
                uri.toString(),
                name,
                size,
                false,
                "tamanho fora da faixa 4–8 MiB esperada para BIOS PS2"
            )
        }

        val romVersion = findRomVersion(bytes)
        if (romVersion == null) {
            return BiosInfo(
                uri.toString(),
                name,
                size,
                false,
                "ROMDIR/ROMVER de BIOS PS2 não encontrado"
            )
        }

        return BiosInfo(
            uri = uri.toString(),
            displayName = name,
            sizeBytes = size,
            plausible = true,
            reason = "BIOS PS2 válida • ${romVersion.region} • v${romVersion.version} • ROMVER ${romVersion.raw}"
        )
    }

    /**
     * Mirrors the public PCSX2 BIOS discovery strategy at a small scale:
     * find the RESET ROMDIR entry within the first 512 KiB, walk 16-byte entries,
     * accumulate aligned ROM file offsets, and resolve ROMVER.
     */
    private fun findRomVersion(data: ByteArray): RomVersion? {
        val scanEnd = minOf(ROMDIR_SCAN_LIMIT, data.size - ROMDIR_ENTRY_SIZE)
        var romDirOffset = -1
        var offset = 0
        while (offset <= scanEnd) {
            if (entryName(data, offset) == "RESET") {
                romDirOffset = offset
                break
            }
            offset += ROMDIR_ENTRY_SIZE
        }
        if (romDirOffset < 0) return null

        var directoryOffset = romDirOffset
        var fileOffset = 0L
        var entries = 0
        while (directoryOffset + ROMDIR_ENTRY_SIZE <= data.size && entries < MAX_ROMDIR_ENTRIES) {
            val name = entryName(data, directoryOffset)
            if (name.isEmpty()) break

            val fileSize = readU32Le(data, directoryOffset + 12)
            if (name == "ROMVER") {
                if (fileOffset < 0 || fileOffset + ROMVER_LENGTH > data.size.toLong()) return null
                val start = fileOffset.toInt()
                val raw = data.copyOfRange(start, start + ROMVER_LENGTH)
                    .toString(Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ')
                return parseRomVersion(raw)
            }

            fileOffset = align16(fileOffset + fileSize)
            if (fileOffset > data.size.toLong()) return null
            directoryOffset += ROMDIR_ENTRY_SIZE
            entries++
        }
        return null
    }

    private fun parseRomVersion(raw: String): RomVersion? {
        if (raw.length < ROMVER_LENGTH) return null
        val versionDigits = raw.substring(0, 4)
        if (!versionDigits.all { it.isDigit() }) return null
        val version = "${versionDigits.substring(0, 2).toInt()}.${versionDigits.substring(2, 4)}"
        val region = when (raw[4]) {
            'J' -> "Japan"
            'A' -> "USA"
            'E' -> "Europe"
            'H' -> "Asia"
            'C' -> "China"
            'T' -> if (raw.getOrNull(5) == 'Z') "COH-H" else "T10K"
            'X' -> "Test"
            'P' -> "Free"
            else -> "Região ${raw[4]}"
        }
        return RomVersion(raw = raw, version = version, region = region)
    }

    private fun entryName(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 10 > data.size) return ""
        var end = offset
        val limit = offset + 10
        while (end < limit && data[end].toInt() != 0) end++
        if (end == limit) return ""
        return data.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }

    private fun readU32Le(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) return Long.MAX_VALUE
        return (data[offset].toLong() and 0xffL) or
            ((data[offset + 1].toLong() and 0xffL) shl 8) or
            ((data[offset + 2].toLong() and 0xffL) shl 16) or
            ((data[offset + 3].toLong() and 0xffL) shl 24)
    }

    private fun align16(value: Long): Long = (value + 0x0fL) and 0xfffffff0L

    private fun readLimited(context: Context, uri: Uri): ByteArray {
        val output = ByteArrayOutputStream(MAX_PLAUSIBLE_SIZE.toInt())
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PLAUSIBLE_SIZE) {
                    // One extra byte is enough to prove that the image is oversized.
                    output.write(buffer, 0, minOf(read, 1))
                    break
                }
                output.write(buffer, 0, read)
            }
        } ?: error("BIOS stream unavailable")
        return output.toByteArray()
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "PS2 BIOS"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private const val MIN_PLAUSIBLE_SIZE = 4L * 1024L * 1024L
    private const val MAX_PLAUSIBLE_SIZE = 8L * 1024L * 1024L
    private const val ROMDIR_SCAN_LIMIT = 512 * 1024
    private const val ROMDIR_ENTRY_SIZE = 16
    private const val ROMVER_LENGTH = 14
    private const val MAX_ROMDIR_ENTRIES = 4096
}
