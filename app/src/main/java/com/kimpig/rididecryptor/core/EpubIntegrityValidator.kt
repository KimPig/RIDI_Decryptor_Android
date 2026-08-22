package com.kimpig.rididecryptor.core

import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubIntegrityResult(
    val errors: List<String>,
    val warnings: List<String>
) {
    val isValid: Boolean get() = errors.isEmpty()
    val hasIssues: Boolean get() = errors.isNotEmpty() || warnings.isNotEmpty()
    val summary: String get() = (errors + warnings).joinToString("; ")
}

object EpubIntegrityValidator {
    private const val REQUIRED_MIME_TYPE = "application/epub+zip"

    fun validate(epub: File): EpubIntegrityResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        try {
            ZipFile(epub).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.isEmpty()) {
                    errors += "The EPUB archive is empty."
                    return EpubIntegrityResult(errors, warnings)
                }

                entries.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                    errors += "Duplicate ZIP entry: $it"
                }
                entries.groupBy { it.name.lowercase(Locale.ROOT) }
                    .filterValues { group -> group.map(ZipEntry::getName).distinct().size > 1 }
                    .keys.forEach { warnings += "Case-colliding ZIP entry: $it" }

                if (entries.first().name != "mimetype") {
                    errors += "mimetype is not the first ZIP entry."
                }
                val mimeEntry = zip.getEntry("mimetype")
                if (mimeEntry == null) {
                    errors += "The mimetype entry is missing."
                } else {
                    val value = zip.getInputStream(mimeEntry).use { it.readBytesBounded(1024) }
                        .toString(Charsets.US_ASCII)
                    if (value != REQUIRED_MIME_TYPE) errors += "The mimetype value is invalid."
                    if (mimeEntry.method != ZipEntry.STORED) warnings += "The mimetype entry is compressed."
                }

                val names = entries.map(ZipEntry::getName).toHashSet()
                val containerEntry = zip.getEntry("META-INF/container.xml")
                if (containerEntry == null) {
                    errors += "META-INF/container.xml is missing."
                } else {
                    validatePackageDocument(zip, names, containerEntry, errors)
                }

                entries.filterNot(ZipEntry::isDirectory).forEach { entry ->
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (input.read(buffer) >= 0) Unit
                    }
                }
            }
        } catch (error: Throwable) {
            errors += (error.message ?: error.javaClass.simpleName)
        }
        return EpubIntegrityResult(errors, warnings)
    }

    fun compare(
        sourceEpub: File,
        sanitizedEpub: File,
        allowedChangedEntries: Set<String>,
        normalizedTimestampMillis: Long? = null
    ): EpubIntegrityResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        try {
            val source = snapshots(sourceEpub)
            val sanitized = snapshots(sanitizedEpub)
            source.order.filterNot(sanitized.byName::containsKey).forEach {
                errors += "Entry removed during sanitization: $it"
            }
            sanitized.order.filterNot(source.byName::containsKey).forEach {
                errors += "Entry added during sanitization: $it"
            }
            if (source.order != sanitized.order) {
                errors += "ZIP entry order changed during sanitization."
            }
            source.byName.keys.intersect(sanitized.byName.keys).forEach { name ->
                val before = source.byName.getValue(name)
                val after = sanitized.byName.getValue(name)
                val expectedTimestamp = resolveEntryTimestamp(before.timestamp, normalizedTimestampMillis)
                if (after.timestamp != expectedTimestamp) {
                    errors += if (normalizedTimestampMillis == null) {
                        "Entry timestamp changed unexpectedly: $name"
                    } else {
                        "Entry timestamp was not normalized: $name"
                    }
                }
                if (name !in allowedChangedEntries && !before.hash.contentEquals(after.hash)) {
                    errors += "Unexpected entry content change: $name"
                }
            }
        } catch (error: Throwable) {
            errors += (error.message ?: error.javaClass.simpleName)
        }
        return EpubIntegrityResult(errors, warnings)
    }

    fun normalizedTimestampMillis(): Long = Calendar.getInstance().run {
        clear()
        set(2010, Calendar.JULY, 28, 12, 48, 18)
        timeInMillis
    }

    fun isNormalizedTimestamp(timestamp: Long): Boolean {
        if (timestamp < 0L) return false
        return Calendar.getInstance().run {
            timeInMillis = timestamp
            get(Calendar.YEAR) == 2010 &&
                get(Calendar.MONTH) == Calendar.JULY &&
                get(Calendar.DAY_OF_MONTH) == 28 &&
                get(Calendar.HOUR_OF_DAY) == 12 &&
                get(Calendar.MINUTE) == 48 &&
                get(Calendar.SECOND) == 18
        }
    }

    fun resolveEntryTimestamp(sourceTimestamp: Long, normalizedTimestampMillis: Long?): Long {
        val candidate = normalizedTimestampMillis ?: sourceTimestamp
        return if (isZipSafeTimestamp(candidate)) candidate else minimumZipTimestampMillis()
    }

    private fun isZipSafeTimestamp(timestamp: Long): Boolean {
        if (timestamp < 0L) return false
        return Calendar.getInstance().run {
            timeInMillis = timestamp
            get(Calendar.YEAR) in 1980..2107
        }
    }

    private fun minimumZipTimestampMillis(): Long = Calendar.getInstance().run {
        clear()
        set(1980, Calendar.JANUARY, 1, 0, 0, 0)
        timeInMillis
    }

    private fun validatePackageDocument(
        zip: ZipFile,
        names: Set<String>,
        containerEntry: ZipEntry,
        errors: MutableList<String>
    ) {
        val container = parseXml(zip, containerEntry)
        val rootFiles = container.getElementsByTagNameNS("*", "rootfile")
        val rootPath = (0 until rootFiles.length)
            .asSequence()
            .mapNotNull { rootFiles.item(it)?.attributes?.getNamedItem("full-path")?.nodeValue }
            .firstOrNull(String::isNotBlank)
        if (rootPath.isNullOrBlank()) {
            errors += "container.xml has no rootfile path."
            return
        }
        val packagePath = normalizePath(decodePercentPath(rootPath))
        val packageEntry = zip.getEntry(packagePath)
        if (packageEntry == null) {
            errors += "The package document is missing: $packagePath"
            return
        }

        val document = parseXml(zip, packageEntry)
        val manifestIds = mutableSetOf<String>()
        val packageDirectory = packagePath.substringBeforeLast('/', "")
        val items = document.getElementsByTagNameNS("*", "item")
        for (index in 0 until items.length) {
            val item = items.item(index)
            val id = item.attributes?.getNamedItem("id")?.nodeValue
            val href = item.attributes?.getNamedItem("href")?.nodeValue
            if (id.isNullOrBlank()) {
                errors += "A manifest item has no id."
                continue
            }
            if (!manifestIds.add(id)) errors += "Duplicate manifest id: $id"
            if (href.isNullOrBlank()) {
                errors += "Manifest item $id has no href."
                continue
            }
            val resolved = resolveLocalReference(packageDirectory, href)
            if (resolved != null && resolved !in names) {
                errors += "Manifest resource is missing: $resolved"
            }
        }

        val itemRefs = document.getElementsByTagNameNS("*", "itemref")
        for (index in 0 until itemRefs.length) {
            val idRef = itemRefs.item(index).attributes?.getNamedItem("idref")?.nodeValue
            if (idRef.isNullOrBlank() || idRef !in manifestIds) {
                errors += "Invalid spine reference: ${idRef ?: "(missing)"}"
            }
        }
    }

    private fun parseXml(zip: ZipFile, entry: ZipEntry): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val bytes = zip.getInputStream(entry).use { it.readBytesBounded(MAX_XML_ENTRY_BYTES) }
        if (containsForbiddenXmlDeclaration(bytes)) {
            throw IOException("EPUB XML contains a prohibited DOCTYPE or ENTITY declaration")
        }
        return ByteArrayInputStream(bytes).use { factory.newDocumentBuilder().parse(it) }
    }

    private fun containsForbiddenXmlDeclaration(bytes: ByteArray): Boolean {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0x3c.toByte() ->
                bytes.toString(Charsets.UTF_16BE)
            bytes.size >= 4 && bytes[0] == 0x3c.toByte() && bytes[1] == 0.toByte() ->
                bytes.toString(Charsets.UTF_16LE)
            else -> bytes.toString(Charsets.UTF_8)
        }
        return FORBIDDEN_XML_DECLARATION.containsMatchIn(text)
    }

    private fun resolveLocalReference(baseDirectory: String, href: String): String? {
        val path = href.substringBefore('#').substringBefore('?')
        if (path.isBlank() || path.startsWith("//") || SCHEME_REGEX.containsMatchIn(path)) return null
        val decodedPath = decodePercentPath(path)
        if (decodedPath.isBlank()) return null
        val combined = if (baseDirectory.isBlank()) decodedPath else "$baseDirectory/$decodedPath"
        return normalizePath(combined)
    }

    private fun decodePercentPath(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').filter(String::isNotEmpty).forEach { part ->
            when (part) {
                "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) throw IOException("Invalid EPUB path: $path")
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun snapshots(epub: File): ArchiveSnapshot = ZipFile(epub).use { zip ->
        val order = mutableListOf<String>()
        val byName = linkedMapOf<String, EntrySnapshot>()
        zip.entries().asSequence().forEach { entry ->
            if (entry.name in byName) throw IOException("Duplicate ZIP entry: ${entry.name}")
            order += entry.name
            val hash = zip.getInputStream(entry).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest()
            }
            byName[entry.name] = EntrySnapshot(hash, entry.time)
        }
        ArchiveSnapshot(order, byName)
    }

    private data class ArchiveSnapshot(
        val order: List<String>,
        val byName: Map<String, EntrySnapshot>
    )

    private data class EntrySnapshot(val hash: ByteArray, val timestamp: Long)

    private const val MAX_XML_ENTRY_BYTES = 64L * 1024 * 1024
    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val FORBIDDEN_XML_DECLARATION = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
}

internal fun java.io.InputStream.readBytesBounded(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("EPUB entry is unexpectedly large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
