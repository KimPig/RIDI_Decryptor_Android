package com.kimpig.rididecryptor.core

import java.io.File
import java.util.Calendar
import java.util.Date

enum class SourceKind { ROOT, MANUAL }

data class BookCandidate(
    val bookId: String,
    val sourceRoot: String,
    val files: List<String>,
    val sourceKind: SourceKind,
    val label: String = bookId,
    val author: String? = null,
    val metadataFormat: String? = null,
    val expiresAt: Date? = null,
    val savedPath: String? = null,
    val pageCount: Int? = null,
    val fileSizeBytes: Long? = null,
    val downloadedAt: Date? = null,
    val lastOpenedAt: Date? = null,
    val isDownloaded: Boolean? = null,
    val comicQuality: String? = null,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val displayOrder: Int? = null,
    val coverCachePath: String? = null,
    val comicIndexCoverCount: Int? = null,
    val comicIndexContentCount: Int? = null,
    val decryptedOutputs: List<DecryptedOutput> = emptyList()
) {
    val rawPackage: String?
        get() = files.firstOrNull { it.substringAfterLast('/').equals("${bookId}_raw.zip", true) }

    override fun toString(): String {
        val kinds = buildList {
            if (rawPackage != null) add("raw package")
            if (files.any { it.endsWith(".epub", true) }) add("EPUB")
            if (files.any { it.endsWith(".pdf", true) }) add("PDF")
            if (files.any { it.endsWith(".zip", true) && it != rawPackage }) add("comic")
        }.ifEmpty { listOf("local files") }
        return "$label\n${kinds.joinToString(" · ")} · ${files.size} file(s)"
    }

    val displayTitle: String get() = label.ifBlank { bookId }

    val isComic: Boolean
        get() {
            val normalized = metadataFormat?.lowercase().orEmpty()
            if (normalized in setOf("epub", "pdf")) return false
            if (normalized in setOf("comic", "manga", "webtoon", "image")) return true
            if (files.any { it.endsWith(".epub", true) || it.endsWith(".pdf", true) }) return false
            return files.any { path ->
                    path.endsWith(".zip", true) &&
                        !path.substringAfterLast('/').equals("${bookId}_raw.zip", true)
                } || files.any(::isComicImageName)
        }

    val displayFormat: String
        get() = when {
            isComic -> "COMIC"
            metadataFormat != null -> metadataFormat.uppercase()
            files.any { it.endsWith(".epub", true) } -> "EPUB"
            files.any { it.endsWith(".pdf", true) } -> "PDF"
            rawPackage != null -> "PACKAGE"
            files.any { it.endsWith(".zip", true) } -> "COMIC"
            else -> "LOCAL"
        }

    val sourceState: String
        get() = when {
            sourceKind == SourceKind.MANUAL -> "Imported"
            rawPackage != null -> "Raw package"
            else -> "Opened"
        }

    val storageState: String
        get() = when {
            sourceKind == SourceKind.MANUAL -> "App cache"
            sourceRoot.startsWith("/data/") || sourceRoot.startsWith("/storage/emulated/") ||
                sourceRoot.startsWith("/sdcard/") -> "Internal storage"
            else -> "SD card"
        }

    val hasRequiredLocalFiles: Boolean
        get() {
            if (rawPackage != null) return true
            if (isComic) return files.any { it.endsWith(".zip", true) || imageExtension(it) }
            val hasPublication = files.any { it.endsWith(".epub", true) || it.endsWith(".pdf", true) }
            val hasDat = files.any { it.endsWith(".dat", true) }
            return hasPublication && hasDat
        }

    val isOwned: Boolean
        get() = expiresAt?.let { date ->
            Calendar.getInstance().apply { time = date }.get(Calendar.YEAR) >= 9999
        } == true

    val localComicPages: Int?
        get() {
            val cover = comicIndexCoverCount ?: return null
            val content = comicIndexContentCount ?: return null
            return (cover + content).takeIf { it > 0 }
        }

    val displayedComicPages: Int?
        get() = pageCount?.takeIf { it > 0 } ?: localComicPages

    val realmPageCountIncludesCover: Boolean?
        get() {
            val realm = pageCount ?: return null
            val cover = comicIndexCoverCount ?: return null
            val content = comicIndexContentCount ?: return null
            return when (realm) {
                cover + content -> true
                content -> false
                else -> null
            }
        }

    val isDecrypted: Boolean get() = decryptedOutputs.isNotEmpty()

    private fun imageExtension(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")

    private fun isComicImageName(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return Regex("^__ridi__\\d+\\.(?:jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE).matches(name) ||
            Regex("^${Regex.escape(bookId)}_org\\.(?:jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE).matches(name)
    }
}

data class DecryptedOutput(
    val displayName: String,
    val location: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val displayLocation: String = location
)

data class PreparedBook(
    val bookId: String,
    val directory: File,
    val title: String = bookId,
    val comicQuality: String? = null
)

data class DecryptResult(
    val bookId: String,
    val fileName: String,
    val mimeType: String,
    val temporaryFile: File,
    val format: String,
    val sha256: String,
    val itemCount: Int? = null
)

data class ProgressUpdate(
    val stage: String,
    val completed: Long = 0,
    val total: Long = 0
) {
    val percent: Int?
        get() = total.takeIf { it > 0 }?.let { ((completed.coerceIn(0, total) * 100) / it).toInt() }
}
