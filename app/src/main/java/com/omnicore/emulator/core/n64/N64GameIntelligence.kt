package com.omnicore.emulator.core.n64

import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Small, isolated N64 compatibility brain.
 *
 * It never changes a user's explicit Smart Analog choice. It only enriches AUTO
 * with per-game knowledge when a title is known to use digital movement.
 */
object N64GameIntelligence {
    data class InputPolicy(
        val bridgeDpadInAuto: Boolean,
        val internalTitle: String,
        val analogProfile: String,
        val reason: String?
    )

    private val dpadFirstMarkers = listOf(
        "KIRBY"
    )

    fun inputPolicy(rom: File): InputPolicy {
        val title = readInternalTitle(rom)
        val fileIdentity = rom.nameWithoutExtension.uppercase(Locale.ROOT)
        val identity = "$title $fileIdentity"
        val matched = dpadFirstMarkers.firstOrNull { identity.contains(it) }
        val racingProfile = identity.contains("MARIOKART") || identity.contains("MARIO KART")
        val reasons = listOfNotNull(
            matched?.let { "digital-profile:$it" },
            if (racingProfile) "analog-profile:RACING" else null
        )
        return InputPolicy(
            bridgeDpadInAuto = matched != null,
            internalTitle = title,
            analogProfile = if (racingProfile) "racing" else "balanced",
            reason = reasons.takeIf { it.isNotEmpty() }?.joinToString("+")
        )
    }

    private fun readInternalTitle(rom: File): String {
        if (!rom.isFile || rom.length() < 0x40L) return ""
        val header = ByteArray(0x40)
        var read = 0
        runCatching {
            FileInputStream(rom).use { input ->
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
        }.getOrElse { return "" }
        if (read < header.size) return ""

        val normalized = header.copyOf()
        when (
            listOf(
                header[0].toInt() and 0xff,
                header[1].toInt() and 0xff,
                header[2].toInt() and 0xff,
                header[3].toInt() and 0xff
            )
        ) {
            listOf(0x37, 0x80, 0x40, 0x12) -> {
                for (i in normalized.indices step 2) {
                    if (i + 1 >= normalized.size) break
                    val tmp = normalized[i]
                    normalized[i] = normalized[i + 1]
                    normalized[i + 1] = tmp
                }
            }
            listOf(0x40, 0x12, 0x37, 0x80) -> {
                for (i in normalized.indices step 4) {
                    if (i + 3 >= normalized.size) break
                    val a = normalized[i]
                    val b = normalized[i + 1]
                    normalized[i] = normalized[i + 3]
                    normalized[i + 1] = normalized[i + 2]
                    normalized[i + 2] = b
                    normalized[i + 3] = a
                }
            }
        }

        return String(normalized, 0x20, 20, Charsets.US_ASCII)
            .replace('\u0000', ' ')
            .trim()
            .uppercase(Locale.ROOT)
    }
}
