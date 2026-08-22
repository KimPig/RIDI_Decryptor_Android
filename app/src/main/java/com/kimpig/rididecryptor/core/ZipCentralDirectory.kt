package com.kimpig.rididecryptor.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlin.math.min

internal object ZipCentralDirectory {
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val END_SIGNATURE = 0x06054b50L
    private const val CENTRAL_HEADER_SIZE = 46
    private const val END_HEADER_SIZE = 22
    private const val MAX_COMMENT_SIZE = 65_535
    private const val UTF8_FLAG = 1 shl 11
    private const val DOS_DIRECTORY_ATTRIBUTE = 0x10L

    fun normalizeDirectoryAttributes(zipFile: File): Int =
        readEntries(zipFile, normalizeDirectories = true).count { it.name.endsWith('/') }

    fun externalAttributes(zipFile: File): Map<String, Long> =
        readEntries(zipFile, normalizeDirectories = false).associate { it.name to it.externalAttributes }

    private fun readEntries(zipFile: File, normalizeDirectories: Boolean): List<CentralEntry> {
        RandomAccessFile(zipFile, if (normalizeDirectories) "rw" else "r").use { file ->
            val end = findEndRecord(file)
            if (end.diskNumber != 0 || end.centralDirectoryDisk != 0 ||
                end.entriesOnDisk != end.totalEntries
            ) {
                throw IOException("Multi-disk ZIP archives are not supported")
            }
            if (end.totalEntries == 0xffff || end.centralDirectorySize == 0xffffffffL ||
                end.centralDirectoryOffset == 0xffffffffL
            ) {
                throw IOException("ZIP64 central directories are not supported")
            }
            val centralEnd = end.centralDirectoryOffset + end.centralDirectorySize
            if (end.centralDirectoryOffset < 0L || centralEnd > end.recordOffset) {
                throw IOException("ZIP central directory bounds are invalid")
            }

            val result = ArrayList<CentralEntry>(end.totalEntries)
            var position = end.centralDirectoryOffset
            repeat(end.totalEntries) {
                if (position + CENTRAL_HEADER_SIZE > centralEnd) {
                    throw IOException("ZIP central directory entry is truncated")
                }
                file.seek(position)
                val header = ByteArray(CENTRAL_HEADER_SIZE)
                file.readFully(header)
                if (uint32(header, 0) != CENTRAL_HEADER_SIGNATURE) {
                    throw IOException("Invalid ZIP central directory signature")
                }
                val flags = uint16(header, 8)
                val nameLength = uint16(header, 28)
                val extraLength = uint16(header, 30)
                val commentLength = uint16(header, 32)
                val entrySize = CENTRAL_HEADER_SIZE.toLong() + nameLength + extraLength + commentLength
                if (position + entrySize > centralEnd) {
                    throw IOException("ZIP central directory variable data is truncated")
                }
                val nameBytes = ByteArray(nameLength)
                file.readFully(nameBytes)
                val name = nameBytes.toString(
                    if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else Charset.forName("CP437")
                )
                val isDirectory = nameBytes.lastOrNull() == '/'.code.toByte()
                var attributes = uint32(header, 38)
                if (normalizeDirectories && isDirectory && attributes != DOS_DIRECTORY_ATTRIBUTE) {
                    file.seek(position + 38)
                    writeUInt32(file, DOS_DIRECTORY_ATTRIBUTE)
                    attributes = DOS_DIRECTORY_ATTRIBUTE
                }
                result += CentralEntry(name, attributes)
                position += entrySize
            }
            if (position != centralEnd) {
                throw IOException("Unexpected data follows the ZIP central directory entries")
            }
            return result
        }
    }

    private fun findEndRecord(file: RandomAccessFile): EndRecord {
        val tailSize = min(file.length(), (END_HEADER_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
        if (tailSize < END_HEADER_SIZE) throw IOException("ZIP end record is missing")
        val tailOffset = file.length() - tailSize
        val tail = ByteArray(tailSize)
        file.seek(tailOffset)
        file.readFully(tail)
        for (index in tailSize - END_HEADER_SIZE downTo 0) {
            if (uint32(tail, index) != END_SIGNATURE) continue
            val commentLength = uint16(tail, index + 20)
            if (index + END_HEADER_SIZE + commentLength != tailSize) continue
            return EndRecord(
                diskNumber = uint16(tail, index + 4),
                centralDirectoryDisk = uint16(tail, index + 6),
                entriesOnDisk = uint16(tail, index + 8),
                totalEntries = uint16(tail, index + 10),
                centralDirectorySize = uint32(tail, index + 12),
                centralDirectoryOffset = uint32(tail, index + 16),
                recordOffset = tailOffset + index
            )
        }
        throw IOException("ZIP end record is invalid")
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun writeUInt32(file: RandomAccessFile, value: Long) {
        repeat(4) { shift -> file.write(((value ushr (shift * 8)) and 0xffL).toInt()) }
    }

    private data class CentralEntry(val name: String, val externalAttributes: Long)

    private data class EndRecord(
        val diskNumber: Int,
        val centralDirectoryDisk: Int,
        val entriesOnDisk: Int,
        val totalEntries: Int,
        val centralDirectorySize: Long,
        val centralDirectoryOffset: Long,
        val recordOffset: Long
    )
}
