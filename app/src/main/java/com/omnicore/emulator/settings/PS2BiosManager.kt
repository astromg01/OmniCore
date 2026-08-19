package com.omnicore.emulator.settings

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Stores only a user-selected BIOS document reference.
 *
 * OmniCore never bundles, downloads or redistributes a Sony BIOS. The current
 * Play! backend cannot execute an external BIOS, but keeping this capability at
 * the OmniCore layer lets a future BIOS-capable PS2 backend use the same user
 * selection without changing the library or UI contract.
 */
object PS2BiosManager {
    data class BiosInfo(
        val uri: String,
        val displayName: String,
        val sizeBytes: Long,
        val plausible: Boolean,
        val reason: String
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, meta.first)
            .putLong(KEY_SIZE, meta.second)
            .apply()
        return validate(context, uri, meta.first, meta.second)
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
        val size = metadata.second
        val canRead = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(16)
                input.read(header) > 0
            } ?: false
        }.getOrDefault(false)

        // Common PS2 BIOS dumps are 4 MiB; some valid dumps include extra ROM
        // regions and are larger. Validation deliberately checks plausibility,
        // not copyrighted contents or region-specific signatures.
        val plausibleSize = size in MIN_PLAUSIBLE_SIZE..MAX_PLAUSIBLE_SIZE
        val plausible = canRead && plausibleSize
        val reason = when {
            !canRead -> "arquivo não pode ser lido"
            !plausibleSize -> "tamanho fora da faixa esperada para um dump de BIOS PS2"
            else -> "dump de BIOS armazenado para backend PS2 compatível"
        }
        return BiosInfo(uri.toString(), name, size, plausible, reason)
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

    private const val MIN_PLAUSIBLE_SIZE = 2L * 1024L * 1024L
    private const val MAX_PLAUSIBLE_SIZE = 8L * 1024L * 1024L
}
