package com.omnicore.emulator.core.ps2

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Bounded ISO9660 probe used only for library classification.
 *
 * A PS1 and a PS2 disc can both contain SYSTEM.CNF, so finding the file is not
 * enough. PS2 media is identified by a SYSTEM.CNF payload containing BOOT2.
 * The probe never scans the whole image and never copies it into app storage.
 */
object PS2IsoValidator {
    private const val SECTOR = 2048L
    private const val PVD_LBA = 16L
    private const val PVD_ROOT_RECORD_OFFSET = 156
    private const val MAX_ROOT_BYTES = 256 * 1024
    private const val MAX_SYSTEM_CNF_BYTES = 16 * 1024

    fun isPlayStation2Iso(context: Context, uri: Uri, fileName: String): Boolean {
        if (!fileName.endsWith(".iso", ignoreCase = true)) return false
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val pvd = readAt(channel, PVD_LBA * SECTOR, SECTOR.toInt()) ?: return@use false
                    if (!hasIso9660Pvd(pvd)) return@use false

                    val root = directoryRecord(pvd, PVD_ROOT_RECORD_OFFSET) ?: return@use false
                    val rootBytes = readAt(
                        channel,
                        root.extentLba * SECTOR,
                        min(root.dataLength, MAX_ROOT_BYTES)
                    ) ?: return@use false

                    val systemCnf = findRootEntry(rootBytes, "SYSTEM.CNF") ?: return@use false
                    val cnfBytes = readAt(
                        channel,
                        systemCnf.extentLba * SECTOR,
                        min(systemCnf.dataLength, MAX_SYSTEM_CNF_BYTES)
                    ) ?: return@use false
                    val text = cnfBytes.toString(Charsets.ISO_8859_1)
                    PS2_BOOT2.containsMatchIn(text)
                }
            } ?: false
        }.getOrDefault(false)
    }

    private data class IsoRecord(val extentLba: Long, val dataLength: Int, val name: String)

    private fun hasIso9660Pvd(bytes: ByteArray): Boolean =
        bytes.size >= 7 &&
            bytes[0].toInt() == 1 &&
            bytes.copyOfRange(1, 6).toString(Charsets.US_ASCII) == "CD001" &&
            bytes[6].toInt() == 1

    private fun findRootEntry(bytes: ByteArray, wanted: String): IsoRecord? {
        var offset = 0
        while (offset < bytes.size) {
            val recordLength = bytes[offset].toInt() and 0xFF
            if (recordLength == 0) {
                val nextSector = (((offset / SECTOR.toInt()) + 1) * SECTOR.toInt())
                if (nextSector <= offset) break
                offset = nextSector
                continue
            }
            if (offset + recordLength > bytes.size) break
            val record = directoryRecord(bytes, offset)
            if (record != null && record.name.substringBefore(';').equals(wanted, ignoreCase = true)) {
                return record
            }
            offset += recordLength
        }
        return null
    }

    private fun directoryRecord(bytes: ByteArray, offset: Int): IsoRecord? {
        if (offset < 0 || offset + 34 > bytes.size) return null
        val recordLength = bytes[offset].toInt() and 0xFF
        if (recordLength < 34 || offset + recordLength > bytes.size) return null
        val nameLength = bytes[offset + 32].toInt() and 0xFF
        if (nameLength <= 0 || offset + 33 + nameLength > bytes.size) return null

        val extent = littleEndianUInt32(bytes, offset + 2)
        val dataLength = littleEndianUInt32(bytes, offset + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nameBytes = bytes.copyOfRange(offset + 33, offset + 33 + nameLength)
        val name = if (nameLength == 1 && (nameBytes[0].toInt() == 0 || nameBytes[0].toInt() == 1)) {
            if (nameBytes[0].toInt() == 0) "." else ".."
        } else {
            nameBytes.toString(Charsets.US_ASCII)
        }
        return IsoRecord(extent, dataLength, name)
    }

    private fun littleEndianUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0L
        return ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFF_FFFFL
    }

    private fun readAt(
        channel: java.nio.channels.FileChannel,
        offset: Long,
        length: Int
    ): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val buffer = ByteBuffer.allocate(length)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) break
            if (read == 0) break
        }
        if (buffer.position() == 0) return null
        return buffer.array().copyOf(buffer.position())
    }

    private val PS2_BOOT2 = Regex("(?im)^\\s*BOOT2\\s*=")
}
