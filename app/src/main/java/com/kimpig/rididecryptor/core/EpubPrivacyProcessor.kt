package com.kimpig.rididecryptor.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class EpubPrivacyScanResult(
    val completed: Boolean,
    val invisibleCodeCount: Int,
    val bookTokenCount: Int,
    val invisibleCodeEntries: List<String>,
    val bookTokenEntries: List<String>,
    val failureReason: String? = null
) {
    val hasKnownMarkers: Boolean get() = invisibleCodeCount > 0 || bookTokenCount > 0
}

data class EpubPostProcessResult(
    val success: Boolean,
    val outputFile: File,
    val modifiedEntries: List<String> = emptyList(),
    val initialScan: EpubPrivacyScanResult? = null,
    val finalScan: EpubPrivacyScanResult? = null,
    val integrity: EpubIntegrityResult? = null,
    val warnings: List<String> = emptyList()
)

object EpubPrivacyProcessor {
    private val textExtensions = setOf("xhtml", "html", "htm", "xml", "opf")
    private val invisibleCodeRegex = Regex("(?<![\\u2060\\u2063])[\\u2060\\u2063]{66}(?![\\u2060\\u2063])")
    private val bookTokenScanRegex = Regex(
        """<meta\b[^>]*\bname\s*=\s*(['"])book-token\1[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val bookTokenFullTagRegex = Regex(
        """<meta\b[^>]*?\bname\s*=\s*(['"])book-token\1[^>]*>(?:[A-Za-z0-9+/]{43}=|[A-Za-z0-9+/]{44})?</meta>|<meta\b[^>]*?\bname\s*=\s*(['"])book-token\2[^>]*?/>""",
        RegexOption.IGNORE_CASE
    )

    fun scan(epub: File): EpubPrivacyScanResult {
        val invisibleEntries = mutableListOf<String>()
        val tokenEntries = mutableListOf<String>()
        var invisibleCount = 0
        var tokenCount = 0
        return try {
            ZipFile(epub).use { zip ->
                zip.entries().asSequence().filter(::isTextEntry).forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytesBounded(MAX_TEXT_ENTRY_BYTES) }
                    val text = decode(bytes).text
                    val invisible = invisibleCodeRegex.findAll(text).count()
                    val tokens = bookTokenScanRegex.findAll(text).count()
                    if (invisible > 0) {
                        invisibleCount += invisible
                        invisibleEntries += entry.name
                    }
                    if (tokens > 0) {
                        tokenCount += tokens
                        tokenEntries += entry.name
                    }
                }
            }
            EpubPrivacyScanResult(true, invisibleCount, tokenCount, invisibleEntries, tokenEntries)
        } catch (error: Throwable) {
            EpubPrivacyScanResult(
                false,
                invisibleCount,
                tokenCount,
                invisibleEntries,
                tokenEntries,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    fun process(
        sourceEpub: File,
        sanitizedEpub: File,
        progress: (ProgressUpdate) -> Unit = {},
        removeKnownMarkers: Boolean = true,
        normalizedTimestampMillis: Long? = null
    ): EpubPostProcessResult {
        sanitizedEpub.delete()
        val sourceIntegrity = EpubIntegrityValidator.validate(sourceEpub)
        if (!sourceIntegrity.isValid) {
            return warningResult(
                sourceEpub,
                "EPUB integrity validation failed: ${sourceIntegrity.summary}",
                integrity = sourceIntegrity
            )
        }
        val initialScan = if (removeKnownMarkers) scan(sourceEpub) else null
        if (initialScan?.completed == false) {
            return warningResult(
                sourceEpub,
                "Privacy marker scan failed: ${initialScan.failureReason}",
                initialScan = initialScan,
                integrity = sourceIntegrity
            )
        }

        val modified = mutableListOf<String>()
        return try {
            rewrite(sourceEpub, sanitizedEpub, modified, progress, removeKnownMarkers, normalizedTimestampMillis)
            val integrity = EpubIntegrityValidator.validate(sanitizedEpub)
            if (!integrity.isValid) throw IOException("Sanitized EPUB integrity check failed: ${integrity.summary}")
            val comparison = EpubIntegrityValidator.compare(
                sourceEpub,
                sanitizedEpub,
                modified.toSet(),
                normalizedTimestampMillis
            )
            if (!comparison.isValid) throw IOException("Sanitized EPUB differs unexpectedly: ${comparison.summary}")
            val finalScan = if (removeKnownMarkers) scan(sanitizedEpub) else null
            if (finalScan?.completed == false) {
                throw IOException("Could not validate the sanitized EPUB: ${finalScan.failureReason}")
            }
            if (finalScan?.hasKnownMarkers == true) throw IOException("Known privacy markers remain after sanitization.")
            EpubPostProcessResult(
                true,
                sanitizedEpub,
                modified.toList(),
                initialScan,
                finalScan,
                integrity
            )
        } catch (error: Throwable) {
            sanitizedEpub.delete()
            warningResult(
                sourceEpub,
                "EPUB privacy processing failed: ${error.message ?: error.javaClass.simpleName}",
                initialScan = initialScan,
                integrity = sourceIntegrity,
                modifiedEntries = modified
            )
        }
    }

    private fun rewrite(
        sourceEpub: File,
        sanitizedEpub: File,
        modifiedEntries: MutableList<String>,
        progress: (ProgressUpdate) -> Unit,
        removeKnownMarkers: Boolean,
        normalizedTimestampMillis: Long?
    ) {
        sanitizedEpub.parentFile?.mkdirs()
        ZipFile(sourceEpub).use { source ->
            val entries = source.entries().asSequence().toList()
            if (entries.map(ZipEntry::getName).distinct().size != entries.size) {
                throw IOException("Duplicate ZIP entries cannot be sanitized safely")
            }
            ZipOutputStream(FileOutputStream(sanitizedEpub)).use { output ->
                output.setLevel(Deflater.DEFAULT_COMPRESSION)
                entries.forEachIndexed { index, entry ->
                    val original = if (entry.isDirectory) ByteArray(0) else {
                        source.getInputStream(entry).use { input ->
                            if (isTextEntry(entry)) input.readBytesBounded(MAX_TEXT_ENTRY_BYTES) else null
                        }
                    }
                    val bytes = when {
                        entry.isDirectory -> ByteArray(0)
                        original != null && removeKnownMarkers -> sanitizeBytes(original, entry.name, modifiedEntries)
                        original != null -> original
                        else -> null
                    }
                    val target = ZipEntry(entry.name).apply {
                        time = EpubIntegrityValidator.resolveEntryTimestamp(entry.time, normalizedTimestampMillis)
                    }
                    if (entry.name == "mimetype") {
                        val mimeBytes = bytes ?: source.getInputStream(entry).use { it.readBytesBounded(1024) }
                        val crc = CRC32().apply { update(mimeBytes) }
                        target.method = ZipEntry.STORED
                        target.size = mimeBytes.size.toLong()
                        target.compressedSize = mimeBytes.size.toLong()
                        target.crc = crc.value
                        output.putNextEntry(target)
                        output.write(mimeBytes)
                    } else {
                        target.method = ZipEntry.DEFLATED
                        output.putNextEntry(target)
                        when {
                            bytes != null -> output.write(bytes)
                            !entry.isDirectory -> source.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                    output.closeEntry()
                    progress(ProgressUpdate("Sanitizing EPUB", index + 1L, entries.size.toLong()))
                }
            }
            ZipCentralDirectory.normalizeDirectoryAttributes(sanitizedEpub)
        }
    }

    private fun sanitizeBytes(
        bytes: ByteArray,
        entryName: String,
        modifiedEntries: MutableList<String>
    ): ByteArray {
        val decoded = decode(bytes)
        var cleaned = invisibleCodeRegex.replace(decoded.text, "")
        val extension = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension == "opf" || extension == "xml") {
            cleaned = bookTokenFullTagRegex.replace(cleaned, "")
        }
        if (cleaned == decoded.text) return bytes
        modifiedEntries += entryName
        return decoded.preamble + cleaned.toByteArray(decoded.charset)
    }

    private fun decode(bytes: ByteArray): DecodedText = when {
        bytes.startsWith(UTF8_BOM) -> DecodedText(
            bytes.copyOfRange(UTF8_BOM.size, bytes.size).toString(Charsets.UTF_8),
            Charsets.UTF_8,
            UTF8_BOM
        )
        bytes.startsWith(UTF16_LE_BOM) -> DecodedText(
            bytes.copyOfRange(UTF16_LE_BOM.size, bytes.size).toString(Charsets.UTF_16LE),
            Charsets.UTF_16LE,
            UTF16_LE_BOM
        )
        bytes.startsWith(UTF16_BE_BOM) -> DecodedText(
            bytes.copyOfRange(UTF16_BE_BOM.size, bytes.size).toString(Charsets.UTF_16BE),
            Charsets.UTF_16BE,
            UTF16_BE_BOM
        )
        else -> DecodedText(bytes.toString(Charsets.UTF_8), Charsets.UTF_8, ByteArray(0))
    }

    private fun isTextEntry(entry: ZipEntry): Boolean = !entry.isDirectory &&
        entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in textExtensions

    private fun warningResult(
        outputFile: File,
        warning: String,
        initialScan: EpubPrivacyScanResult? = null,
        integrity: EpubIntegrityResult? = null,
        modifiedEntries: List<String> = emptyList()
    ) = EpubPostProcessResult(
        false,
        outputFile,
        modifiedEntries,
        initialScan,
        integrity = integrity,
        warnings = listOf(warning)
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private data class DecodedText(
        val text: String,
        val charset: Charset,
        val preamble: ByteArray
    )

    private const val MAX_TEXT_ENTRY_BYTES = 64L * 1024 * 1024
    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xfe.toByte(), 0xff.toByte())
}
