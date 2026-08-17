package com.omnicore.emulator.storage

import java.io.File
import java.security.MessageDigest

data class Ps1BiosStatus(
    val hasCandidate: Boolean,
    val verifiedRetail: Boolean,
    val fileName: String?,
    val shortLabel: String,
    val detail: String
)

object Ps1BiosHealth {
    private val knownMd5 = mapOf(
        "c53ca5908936d412331790f4426c6c33" to "PSXONPSP660.bin",
        "6e3735ff4c7dc899ee98981385f6f3d0" to "scph101.bin",
        "1e68c231d0896b7eadcad1d7d8e76129" to "scph7001.bin",
        "490f666e1afb15b7362b406ed1cea246" to "scph5501.bin",
        "924e392ed05558ffdb115408c263dccf" to "scph1001.bin"
    )

    fun inspect(systemDir: File): Ps1BiosStatus {
        val candidates = systemDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) {
            return Ps1BiosStatus(false, false, null, "HLE", "Nenhuma BIOS de PS1 importada; usando HLE do core.")
        }
        for (file in candidates) {
            val md5 = runCatching { md5(file) }.getOrNull() ?: continue
            val canonical = knownMd5[md5]
            if (canonical != null) {
                return Ps1BiosStatus(true, true, file.name, "BIOS OK", "BIOS retail verificada: ${file.name}")
            }
        }
        val first = candidates.first()
        return Ps1BiosStatus(true, false, first.name, "BIOS ?", "BIOS encontrada, mas o hash não corresponde ao conjunto retail conhecido.")
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
