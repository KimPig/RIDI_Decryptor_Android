package com.kimpig.rididecryptor.core

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngine {
    fun decrypt(
        book: PreparedBook,
        deviceId: String,
        outputDirectory: File,
        progress: (ProgressUpdate) -> Unit = {},
        removeEpubPrivacyMarkers: Boolean = true,
        normalizedArchiveTimestampMillis: Long? = null
    ): DecryptResult {
        require(deviceId.length >= 18) { "Device ID is missing or too short" }
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            throw IOException("Could not create output workspace")
        }
        progress(ProgressUpdate("Preparing private copy"))
        val prepared = PackagePreparer().unpackIfNeeded(book)
        val files = prepared.directory.walkTopDown().filter(File::isFile).take(50_001).toList()
        if (files.size > 50_000) throw IOException("Book contains too many files")

        val dat = files.firstOrNull { it.name.equals("${book.bookId}.dat", true) }
            ?: files.firstOrNull { it.extension.equals("dat", true) }

        files.firstOrNull { it.name.equals("${book.bookId}.epub", true) }
            ?.let { return publication(book, it, publicationKey(it, "epub", dat, deviceId), "epub", outputDirectory, progress, removeEpubPrivacyMarkers, normalizedArchiveTimestampMillis) }
        files.firstOrNull { it.name.equals("${book.bookId}.pdf", true) }
            ?.let { return publication(book, it, publicationKey(it, "pdf", dat, deviceId), "pdf", outputDirectory, progress, removeEpubPrivacyMarkers, normalizedArchiveTimestampMillis) }
        files.firstOrNull { it.extension.equals("epub", true) }
            ?.let { return publication(book, it, publicationKey(it, "epub", dat, deviceId), "epub", outputDirectory, progress, removeEpubPrivacyMarkers, normalizedArchiveTimestampMillis) }
        files.firstOrNull { it.extension.equals("pdf", true) }
            ?.let { return publication(book, it, publicationKey(it, "pdf", dat, deviceId), "pdf", outputDirectory, progress, removeEpubPrivacyMarkers, normalizedArchiveTimestampMillis) }

        val optionalComicKey = dat?.let { runCatching { contentKey(it, deviceId) }.getOrNull() }
        return comic(book, files, optionalComicKey, deviceId, outputDirectory, progress, normalizedArchiveTimestampMillis)
    }

    private fun publicationKey(file: File, format: String, dat: File?, deviceId: String): ByteArray? {
        if (isPlainPublication(file, format)) return null
        return dat?.let { contentKey(it, deviceId) }
            ?: throw IOException("Encrypted ${format.uppercase()} requires a local .dat key envelope")
    }

    fun contentKey(datFile: File, deviceId: String): ByteArray {
        val encrypted = datFile.readBytes()
        val deviceKey = deviceId.take(16).toByteArray(Charsets.UTF_8)
        require(deviceKey.size == 16) { "Device ID cannot form a 16-byte key" }
        val decoded = decryptEcb(encrypted, deviceKey)
        return extractContentKey(decoded, deviceId)
    }

    internal fun extractContentKey(decoded: ByteArray, deviceId: String): ByteArray {
        val identity = deviceId.toByteArray(Charsets.UTF_8)
        val offset = identity.size + 32
        require(decoded.size >= offset + 16) { "DRM key envelope is incomplete" }
        require(decoded.copyOfRange(0, identity.size).contentEquals(identity)) {
            "DRM key envelope belongs to another device"
        }
        return decoded.copyOfRange(offset, offset + 16)
    }

    @SuppressLint("GetInstance") // Compatibility with RIDI's local file format.
    internal fun decryptEcb(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(encrypted.isNotEmpty() && encrypted.size % 16 == 0 && key.size == 16) {
            "Invalid AES-ECB input"
        }
        return Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(encrypted)
        }
    }

    internal fun decryptCbc(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(encrypted.size > 16 && (encrypted.size - 16) % 16 == 0 && key.size == 16) {
            "Invalid AES-CBC input"
        }
        val padded = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(encrypted, 0, 16))
            doFinal(encrypted, 16, encrypted.size - 16)
        }
        return removePkcs7(padded)
    }

    internal fun comicKey(bookId: String): ByteArray {
        require(bookId.length > 6) { "Invalid comic book ID" }
        val material = "stream${bookId.dropLast(6)}$bookId".toByteArray(Charsets.UTF_8)
        val hex = MessageDigest.getInstance("SHA-1").digest(material)
            .joinToString("") { "%02x".format(it) }
        return hex.take(16).toByteArray(Charsets.UTF_8)
    }

    private fun publication(
        book: PreparedBook,
        encrypted: File,
        key: ByteArray?,
        format: String,
        outputDirectory: File,
        progress: (ProgressUpdate) -> Unit,
        removeEpubPrivacyMarkers: Boolean,
        normalizedArchiveTimestampMillis: Long?
    ): DecryptResult {
        val output = File(outputDirectory, "${safeOutputBaseName(book.title, book.bookId)}.$format")
        val partial = File(outputDirectory, output.name + ".partial")
        val sanitized = File(outputDirectory, output.name + ".sanitized.partial")
        partial.delete()
        sanitized.delete()
        if (isPlainPublication(encrypted, format)) {
            copyWithProgress(encrypted, partial, "Copying ${format.uppercase()}", progress)
        } else {
            val contentKey = key ?: throw IOException("${format.uppercase()} requires ${book.bookId}.dat")
            when (format) {
                "epub" -> decryptEcbFile(encrypted, partial, contentKey, progress)
                "pdf" -> decryptCbcFile(encrypted, partial, contentKey, progress)
            }
        }
        progress(ProgressUpdate("Validating ${format.uppercase()}", 0, 1))
        if (!validatePublication(partial, format)) {
            partial.delete()
            throw IOException("${format.uppercase()} decryption validation failed")
        }
        var finalPartial = partial
        var warnings = emptyList<String>()
        if (format == "epub" && (removeEpubPrivacyMarkers || normalizedArchiveTimestampMillis != null)) {
            progress(ProgressUpdate("Inspecting EPUB privacy markers", 0, 1))
            val postProcess = EpubPrivacyProcessor.process(
                partial,
                sanitized,
                progress,
                removeEpubPrivacyMarkers,
                normalizedArchiveTimestampMillis
            )
            warnings = postProcess.warnings
            if (postProcess.success) {
                partial.delete()
                finalPartial = sanitized
                progress(ProgressUpdate("Validated sanitized EPUB", 1, 1))
            } else {
                sanitized.delete()
                progress(ProgressUpdate("EPUB completed with warning", 1, 1))
            }
        }
        output.delete()
        if (!finalPartial.renameTo(output)) throw IOException("Could not finalize ${format.uppercase()} output")
        progress(ProgressUpdate("Validated ${format.uppercase()}", 1, 1))
        return DecryptResult(
            book.bookId,
            output.name,
            if (format == "pdf") "application/pdf" else "application/epub+zip",
            output,
            format,
            sha256(output),
            warnings = warnings
        )
    }

    private fun comic(
        book: PreparedBook,
        files: List<File>,
        contentKey: ByteArray?,
        deviceId: String,
        outputDirectory: File,
        progress: (ProgressUpdate) -> Unit,
        normalizedArchiveTimestampMillis: Long?
    ): DecryptResult {
        val bookId = book.bookId
        val keys = buildList {
            if (deviceId.length >= 18) add(deviceId.substring(2, 18).toByteArray(Charsets.UTF_8))
            runCatching { comicKey(bookId) }.getOrNull()?.let(::add)
            contentKey?.let(::add)
        }.distinctBy { it.joinToString() }

        val nestedZips = files.filter {
            it.extension.equals("zip", true) && !it.name.equals("${bookId}_raw.zip", true)
        }
        val sources = mutableListOf<ComicSource>()
        val openedArchives = mutableListOf<ZipFile>()
        try {
            if (nestedZips.isNotEmpty()) {
                nestedZips.sortedBy { it.name }.forEach { file ->
                    if (!hasHeader(file, ZIP_HEADER)) throw IOException("Comic container is not a ZIP archive")
                    val archive = ZipFile(file)
                    openedArchives += archive
                    collectComicImages(archive, bookId, sources)
                }
            } else {
                files.asSequence()
                    .filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
                    .forEach { file ->
                        if (file.length() > MAX_COMIC_IMAGE_BYTES) throw IOException("Comic image is unexpectedly large")
                        comicIdentity(file.name, bookId)?.let { sources += ComicSource(file.name, it, file = file) }
                    }
            }
            val cover = sources.filter { it.identity is ComicIdentity.Cover }
            if (cover.size != 1) throw IOException("Comic must contain exactly one ${bookId}_org cover image")
            val pages = sources.mapNotNull { source ->
                (source.identity as? ComicIdentity.Page)?.let { it.index to source }
            }
            if (pages.isEmpty()) throw IOException("Comic contains no __ridi__ page images")
            if (pages.map { it.first }.distinct().size != pages.size) throw IOException("Comic contains duplicate page numbers")
            val sortedPages = pages.sortedBy { it.first }
            val lastIndex = sortedPages.last().first
            val missing = (0..lastIndex).firstOrNull { expected -> sortedPages.none { it.first == expected } }
            if (missing != null) throw IOException("Comic page __ridi__$missing is missing")
            val ordered = buildList {
                add(cover.single() to 1)
                sortedPages.forEach { (index, source) -> add(source to index + 2) }
            }

            val output = File(outputDirectory, comicOutputName(book.title, bookId, book.comicQuality))
            val partial = File(outputDirectory, output.name + ".partial")
            partial.delete()
            val expectedHashes = linkedMapOf<String, String>()
            ZipOutputStream(FileOutputStream(partial)).use { zipOut ->
                zipOut.setLevel(Deflater.NO_COMPRESSION)
                ordered.forEachIndexed { position, (source, outputIndex) ->
                    val encrypted = readComicSource(source)
                    val image = decryptImage(encrypted, keys)
                        ?: throw IOException("Could not decrypt comic image ${source.originalName}")
                    validateImage(image, source.originalName)
                    val extension = imageExtension(image)
                    val name = canonicalComicName(outputIndex, lastIndex, extension)
                    expectedHashes[name] = sha256(image)
                    val entry = ZipEntry(name).apply {
                        time = EpubIntegrityValidator.resolveEntryTimestamp(
                            sourceTimestamp(source),
                            normalizedArchiveTimestampMillis
                        )
                    }
                    zipOut.putNextEntry(entry)
                    zipOut.write(image)
                    zipOut.closeEntry()
                    progress(ProgressUpdate("Decrypting comic", position + 1L, ordered.size.toLong()))
                }
            }
            progress(ProgressUpdate("Validating comic archive", 0, expectedHashes.size.toLong()))
            validateComicArchive(partial, expectedHashes, progress)
            output.delete()
            if (!partial.renameTo(output)) throw IOException("Could not finalize comic output")
            return DecryptResult(bookId, output.name, "application/zip", output, "comic", sha256(output), expectedHashes.size)
        } finally {
            openedArchives.forEach { runCatching { it.close() } }
        }
    }

    internal fun comicOutputName(title: String, bookId: String, quality: String?): String {
        val cleanTitle = title.trim().trimEnd('.').replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { bookId }
        val base = if (cleanTitle == bookId) bookId else "$cleanTitle ($bookId)"
        val qualityLabel = when (quality?.lowercase()) {
            "original" -> " [Original]"
            "recommended" -> " [Standard]"
            else -> ""
        }
        return "$base$qualityLabel.zip"
    }

    private fun collectComicImages(source: ZipFile, bookId: String, output: MutableList<ComicSource>) {
        val entries = source.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.size > MAX_COMIC_IMAGE_BYTES) continue
            val original = entry.name.substringAfterLast('/')
            val identity = comicIdentity(original, bookId) ?: continue
            output += ComicSource(original, identity, archive = source, entryName = entry.name)
        }
    }

    private fun readComicSource(source: ComicSource): ByteArray {
        val input = when {
            source.file != null -> FileInputStream(source.file)
            source.archive != null && source.entryName != null -> {
                val entry = source.archive.getEntry(source.entryName)
                    ?: throw IOException("Comic ZIP entry disappeared: ${source.originalName}")
                source.archive.getInputStream(entry)
            }
            else -> throw IOException("Comic source is unavailable")
        }
        return input.use { it.readBounded(MAX_COMIC_IMAGE_BYTES) }
    }

    private fun sourceTimestamp(source: ComicSource): Long = when {
        source.file != null -> source.file.lastModified()
        source.archive != null && source.entryName != null -> source.archive.getEntry(source.entryName)?.time ?: -1L
        else -> -1L
    }

    private fun java.io.InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Comic image is unexpectedly large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decryptImage(source: ByteArray, keys: List<ByteArray>): ByteArray? {
        if (isImage(source)) return source
        keys.forEach { key ->
            if (source.isNotEmpty() && source.size % 16 == 0) {
                runCatching { removePkcs7IfValid(decryptEcb(source, key)) }
                    .getOrNull()
                    ?.takeIf(::isImage)
                    ?.let { return it }
            }
            runCatching { decryptCbc(source, key) }.getOrNull()?.takeIf(::isImage)?.let { return it }
        }
        return null
    }

    @SuppressLint("GetInstance") // Compatibility with RIDI's local file format.
    private fun decryptEcbFile(
        source: File,
        target: File,
        key: ByteArray,
        progress: (ProgressUpdate) -> Unit
    ) {
        require(source.length() > 0 && source.length() % 16L == 0L)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        cryptFile(source, target, cipher, 0, "Decrypting EPUB", progress)
        truncatePkcs7(target, "EPUB")
    }

    private fun decryptCbcFile(
        source: File,
        target: File,
        key: ByteArray,
        progress: (ProgressUpdate) -> Unit
    ) {
        require(source.length() > 16 && (source.length() - 16) % 16L == 0L)
        val iv = ByteArray(16)
        FileInputStream(source).use { input ->
            if (input.read(iv) != 16) throw IOException("PDF IV is incomplete")
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cryptFile(source, target, cipher, 16, "Decrypting PDF", progress)
        truncatePkcs7(target, "PDF")
    }

    private fun truncatePkcs7(target: File, format: String) {
        RandomAccessFile(target, "rw").use { file ->
            if (file.length() == 0L) throw IOException("$format decryption produced no data")
            file.seek(file.length() - 1)
            val padding = file.readUnsignedByte()
            if (padding !in 1..16 || padding > file.length()) {
                throw IOException("Invalid $format padding")
            }
            val tail = ByteArray(padding)
            file.seek(file.length() - padding)
            file.readFully(tail)
            if (tail.any { (it.toInt() and 0xff) != padding }) {
                throw IOException("Invalid $format padding")
            }
            file.setLength(file.length() - padding)
        }
    }

    private fun cryptFile(
        source: File,
        target: File,
        cipher: Cipher,
        skip: Long,
        stage: String,
        progress: (ProgressUpdate) -> Unit
    ) {
        FileInputStream(source).use { input ->
            var remaining = skip
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw IOException("Could not skip encryption header")
                remaining -= skipped
            }
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                val total = source.length() - skip
                var completed = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    cipher.update(buffer, 0, count)?.let(output::write)
                    completed += count
                    progress(ProgressUpdate(stage, completed, total))
                }
                cipher.doFinal()?.let(output::write)
            }
        }
    }

    private fun copyWithProgress(
        source: File,
        target: File,
        stage: String,
        progress: (ProgressUpdate) -> Unit
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                var completed = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    completed += count
                    progress(ProgressUpdate(stage, completed, source.length()))
                }
            }
        }
    }

    private fun removePkcs7(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty())
        val size = bytes.last().toInt() and 0xff
        require(size in 1..16 && size <= bytes.size)
        require(bytes.takeLast(size).all { (it.toInt() and 0xff) == size })
        return bytes.copyOf(bytes.size - size)
    }

    private fun removePkcs7IfValid(bytes: ByteArray): ByteArray =
        runCatching { removePkcs7(bytes) }.getOrDefault(bytes)

    private fun isPlainPublication(file: File, format: String): Boolean = when (format) {
        "epub" -> hasHeader(file, ZIP_HEADER)
        "pdf" -> hasHeader(file, PDF_HEADER)
        else -> false
    }

    private fun hasHeader(file: File, expected: ByteArray): Boolean {
        if (!file.isFile || file.length() < expected.size) return false
        val actual = ByteArray(expected.size)
        FileInputStream(file).use { if (it.read(actual) != actual.size) return false }
        return actual.contentEquals(expected)
    }

    private fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) ||
            bytes.take(8).toByteArray().contentEquals(PNG_HEADER) ||
            bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ||
            (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP")
    }

    private fun validatePublication(file: File, format: String): Boolean {
        if (!isPlainPublication(file, format)) return false
        return when (format) {
            "epub" -> runCatching {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().any { !it.isDirectory }
                }
            }.getOrDefault(false)
            "pdf" -> file.length() > PDF_HEADER.size
            else -> false
        }
    }

    private fun comicIdentity(name: String, bookId: String): ComicIdentity? {
        val escaped = Regex.escape(bookId)
        if (Regex("^${escaped}_org\\.(?:jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE).matches(name)) {
            return ComicIdentity.Cover
        }
        val match = Regex("^__ridi__(\\d+)\\.(?:jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE)
            .matchEntire(name) ?: return null
        return ComicIdentity.Page(match.groupValues[1].toIntOrNull() ?: return null)
    }

    private fun validateImage(bytes: ByteArray, sourceName: String) {
        if (!isImage(bytes)) throw IOException("Invalid image header: $sourceName")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IOException("Image could not be decoded: $sourceName")
        }
    }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes[0] == 0xff.toByte() -> "jpg"
        bytes[0] == 0x89.toByte() -> "png"
        bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" -> "webp"
        else -> "gif"
    }

    private fun validateComicArchive(
        archive: File,
        expected: LinkedHashMap<String, String>,
        progress: (ProgressUpdate) -> Unit
    ) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            if (entries.map { it.name } != expected.keys.toList()) {
                throw IOException("Comic archive page order validation failed")
            }
            entries.forEachIndexed { index, entry ->
                val actualHash = zip.getInputStream(entry).use(::sha256)
                if (actualHash != expected.getValue(entry.name)) {
                    throw IOException("Comic SHA-256 validation failed for ${entry.name}")
                }
                zip.getInputStream(entry).use { validateImage(it, entry.name) }
                progress(ProgressUpdate("Validating comic archive", index + 1L, entries.size.toLong()))
            }
        }
    }

    internal fun safeOutputBaseName(title: String, bookId: String): String {
        val clean = title
            .replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(120)
            .trimEnd(' ', '.')
        return if (clean.isBlank() || clean == bookId) bookId else "$clean ($bookId)"
    }

    internal fun canonicalComicName(outputIndex: Int, lastSourceIndex: Int, extension: String): String {
        require(outputIndex in 1..lastSourceIndex + 2)
        require(extension in IMAGE_EXTENSIONS)
        val width = maxOf(3, (lastSourceIndex + 2).toString().length)
        return outputIndex.toString().padStart(width, '0') + "." + extension
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    private fun validateImage(input: java.io.InputStream, sourceName: String) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IOException("Image could not be decoded: $sourceName")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private sealed interface ComicIdentity {
        data object Cover : ComicIdentity
        data class Page(val index: Int) : ComicIdentity
    }

    private data class ComicSource(
        val originalName: String,
        val identity: ComicIdentity,
        val file: File? = null,
        val archive: ZipFile? = null,
        val entryName: String? = null
    )

    companion object {
        private val ZIP_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        private val PDF_HEADER = "%PDF".toByteArray(Charsets.US_ASCII)
        private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
        private const val MAX_COMIC_IMAGE_BYTES = 128L * 1024 * 1024
    }
}
