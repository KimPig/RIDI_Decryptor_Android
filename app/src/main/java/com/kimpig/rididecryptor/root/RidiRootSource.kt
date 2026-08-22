package com.kimpig.rididecryptor.root

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.kimpig.rididecryptor.core.BookCandidate
import com.kimpig.rididecryptor.core.CandidateDiscovery
import com.kimpig.rididecryptor.core.PreferenceXmlParser
import com.kimpig.rididecryptor.core.PreparedBook
import com.kimpig.rididecryptor.storage.ScanSessionStore
import java.io.File
import java.io.IOException

data class RootScanResult(
    val deviceId: String,
    val books: List<BookCandidate>,
    val metadataCount: Int,
    val metadataIssue: String? = null,
    val androidUser: String = "0",
    val internalBooks: Int = 0,
    val removableBooks: Int = 0,
    val dataRoots: List<String> = emptyList(),
    val currentAccountId: String? = null
)

class RidiAppNotInstalledException : IOException("The official RIDI app is not installed")

class RidiDeviceInfoMissingException(message: String) : IOException(message)

@SuppressLint("SdCardPath") // These are intentional cross-app roots read only after explicit root grant.
class RidiRootSource(private val shell: RootShell = RootShell()) {
    private val fallbackDataRoots = listOf(
        "/data/user/0/com.initialcoms.ridi",
        "/data/data/com.initialcoms.ridi"
    )

    private val externalBookRoots = listOf(
        "/storage/emulated/0/Android/data/com.initialcoms.ridi/files/books",
        "/sdcard/Android/data/com.initialcoms.ridi/files/books"
    )

    fun hasRoot(): Boolean = shell.isAvailable()

    fun knownRootStatus(): Boolean? = shell.knownRootStatus()

    fun requestRootAccess(): Boolean = shell.requestRootAccess()

    fun isOfficialAppRunning(): Boolean = shell.isPackageRunning(OFFICIAL_PACKAGE)

    fun stopOfficialAppAndWait(): Boolean = shell.forceStopPackageAndWait(OFFICIAL_PACKAGE)

    fun scan(context: Context): RootScanResult {
        if (!shell.isAvailable()) throw IOException("Root access was not granted")
        val currentUser = currentUserId()
        val packageDataRoot = packageDataRoot() ?: throw RidiAppNotInstalledException()
        val dataRoots = buildList {
            add(packageDataRoot)
            add("/data/user/$currentUser/com.initialcoms.ridi")
            add("/data/user_de/$currentUser/com.initialcoms.ridi")
            addAll(fallbackDataRoots)
        }.distinct().filter(::isSafeRidiDataRoot)

        val staging = ScanSessionStore.begin(context)
        try {

        val preferences = readPreferences(dataRoots, currentUser, packageInstalled = true)
        File(staging.source, "preferences.xml").writeText(preferences)
        val deviceId = PreferenceXmlParser.deviceId(preferences)
            ?: throw RidiDeviceInfoMissingException("device_id was not found in the official app preferences")
        val currentAccountId = PreferenceXmlParser.accountId(preferences)

        val metadataAttempt = runCatching {
            OfficialLibraryMetadataReader(shell).read(context, dataRoots, File(staging.source, "Library.realm"))
        }.onFailure { error ->
            Log.w(LOG_TAG, "Local library metadata unavailable: ${error.javaClass.simpleName}")
        }
        val metadata = metadataAttempt.getOrDefault(emptyMap())
        val metadataIssue = metadataAttempt.exceptionOrNull()?.let { error ->
            "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
        }

        val configuredExternal = PreferenceXmlParser.strings(preferences)["external_sdcard_path"]
            ?.trim()
            ?.takeIf(::isSafeExternalRoot)
        val realmBookRoots = metadata.values.mapNotNull { it.savedPath?.takeIf(::isSafeSavedBookPath) }
        val requestedRoots = buildList {
            addAll(dataRoots.map { it.trimEnd('/') + "/files/books" })
            addAll(externalBookRoots)
            addAll(realmBookRoots)
            if (configuredExternal != null) {
                add(configuredExternal.trimEnd('/') + "/books")
                add(configuredExternal.trimEnd('/'))
            }
        }.distinct()

        val roots = requestedRoots.mapNotNull { root ->
            val command = "if [ -d ${RootShell.quote(root)} ]; then readlink -f ${RootShell.quote(root)}; fi"
            shell.text(command).trim().takeIf(String::isNotBlank)
        }.distinct()

        val discovered = roots.flatMap { root ->
            val command = "if [ -d ${RootShell.quote(root)} ]; then find ${RootShell.quote(root)} -type f 2>/dev/null; fi"
            shell.text(command).lineSequence().filter(String::isNotBlank).toList()
        }.distinct()

        val safeFiles = discovered.filter { path -> roots.any { root -> isUnder(path, root) } }
        val candidates = CandidateDiscovery.fromRootPaths(safeFiles)
            .groupBy(BookCandidate::bookId)
            .map { (bookId, matches) -> matches.maxBy { score(it, metadata[bookId]?.savedPath) } }

        val coverDirectory = staging.covers
        val indexDirectory = staging.indexes

        val books = candidates.map { candidate ->
            val details = metadata[candidate.bookId]
            val index = copyComicIndexSnapshot(candidate, indexDirectory)
            candidate.copy(
                label = details?.title ?: candidate.bookId,
                author = details?.author,
                metadataFormat = details?.format,
                expiresAt = details?.expiresAt,
                savedPath = details?.savedPath,
                pageCount = details?.pageCount,
                fileSizeBytes = details?.fileSizeBytes,
                downloadedAt = details?.downloadedAt,
                lastOpenedAt = details?.lastOpenedAt,
                isDownloaded = details?.isDownloaded,
                invalidatedType = details?.invalidatedType,
                comicQuality = details?.comicQuality,
                seriesId = details?.seriesId,
                seriesTitle = details?.seriesTitle,
                displayOrder = details?.displayOrder,
                coverCachePath = copyCoverSnapshot(candidate.bookId, dataRoots, coverDirectory),
                comicIndexCoverCount = index?.coverCount,
                comicIndexContentCount = index?.contentCount
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })

        coverDirectory.listFiles().orEmpty().filter { it.name.endsWith(".pending") }.forEach(File::delete)

        val metadataCount = books.count { it.label != it.bookId }
        Log.i(LOG_TAG, "Local scan completed: books=${books.size}, titled=$metadataCount")
        val active = ScanSessionStore.commit(context, staging, books)
        val committedBooks = books.map { book ->
            book.copy(coverCachePath = ScanSessionStore.remap(book.coverCachePath, staging, active))
        }
        return RootScanResult(
            deviceId = deviceId,
            books = committedBooks,
            metadataCount = metadataCount,
            metadataIssue = metadataIssue,
            androidUser = currentUser,
            internalBooks = committedBooks.count { it.sourceRoot.startsWith("/data/") },
            removableBooks = committedBooks.count { !it.sourceRoot.startsWith("/data/") },
            dataRoots = dataRoots,
            currentAccountId = currentAccountId
        )
        } catch (error: Throwable) {
            ScanSessionStore.abort(staging)
            throw error
        }
    }

    fun materialize(
        candidate: BookCandidate,
        destination: File,
        progress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): PreparedBook {
        require(candidate.sourceKind.name == "ROOT")
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Could not create private work directory")
        }
        val filesToCopy = candidate.rawPackage?.let(::listOf) ?: candidate.files
        if (filesToCopy.isEmpty()) throw IOException("The selected book has no readable files")
        if (filesToCopy.size > 20_000) throw IOException("The selected book contains too many files")

        filesToCopy.forEachIndexed { index, source ->
            requireSafeOfficialPath(source)
            val relative = source.removePrefix(candidate.sourceRoot).trimStart('/')
                .takeIf { it.isNotBlank() && !it.split('/').contains("..") }
                ?: "item-$index-${source.substringAfterLast('/')}"
            shell.copyFile(source, File(destination, relative))
            progress(index + 1, filesToCopy.size)
        }
        return PreparedBook(candidate.bookId, destination, candidate.displayTitle, candidate.comicQuality)
    }

    private fun copyCoverSnapshot(bookId: String, dataRoots: List<String>, destination: File): String? {
        val names = listOf("${bookId}_thumbnail.png", "$bookId.png")
        dataRoots.forEach { root ->
            names.forEach { name ->
                val source = root.trimEnd('/') + "/files/covers/$name"
                if (!isUnder(source, root) || !shell.isRegularFile(source)) return@forEach
                val target = File(destination, name)
                return runCatching {
                    requireSafeOfficialPath(source)
                    val pending = File(destination, target.name + ".pending").apply { delete() }
                    shell.copyFile(source, pending, timeoutSeconds = 60)
                    if (target.exists() && !target.delete()) throw IOException("Could not replace cached cover")
                    if (!pending.renameTo(target)) throw IOException("Could not commit cached cover")
                    target.absolutePath
                }.getOrNull()
            }
        }
        return null
    }

    private fun copyComicIndexSnapshot(candidate: BookCandidate, destination: File): ComicIndexInfo? {
        val source = candidate.files.firstOrNull {
            it.substringAfterLast('/').equals("${candidate.bookId}.idx", true)
        } ?: return null
        return runCatching {
            requireSafeOfficialPath(source)
            val target = File(destination, "${candidate.bookId}.idx")
            val pending = File(destination, target.name + ".pending").apply { delete() }
            shell.copyFile(source, pending, timeoutSeconds = 60)
            if (target.exists() && !target.delete()) throw IOException("Could not replace cached comic index")
            if (!pending.renameTo(target)) throw IOException("Could not commit cached comic index")
            ComicIndexReader.read(target)
        }.getOrNull()
    }

    private fun readPreferences(dataRoots: List<String>, currentUser: String, packageInstalled: Boolean): String {
        val exactFiles = dataRoots.map { root ->
            root.trimEnd('/') + "/shared_prefs/com.initialcoms.ridi_preferences.xml"
        }.distinct()
        exactFiles.forEach { path ->
            if (!isSafePreferencePath(path, dataRoots)) return@forEach
            val value = runCatching { shell.readTextFile(path) }.getOrNull() ?: return@forEach
            if (PreferenceXmlParser.deviceId(value) != null) return value
        }

        val files = dataRoots.flatMap { root ->
            val directory = root.trimEnd('/') + "/shared_prefs"
            val command = "if [ -d ${RootShell.quote(directory)} ]; then " +
                "for f in ${RootShell.quote(directory)}/*.xml; do " +
                "[ -f \"\$f\" ] && printf '%s\\n' \"\$f\"; done; fi"
            shell.text(command).lineSequence().filter(String::isNotBlank).toList()
        }.distinct()

        val preferred = files.sortedByDescending {
            it.substringAfterLast('/') == "com.initialcoms.ridi_preferences.xml"
        }
        preferred.forEach { path ->
            if (!isSafePreferencePath(path, dataRoots)) return@forEach
            val value = runCatching { shell.readTextFile(path) }.getOrNull() ?: return@forEach
            if (PreferenceXmlParser.deviceId(value) != null) return value
        }
        val packageState = if (packageInstalled) "installed" else "not reported by package manager"
        throw RidiDeviceInfoMissingException(
            "Official RIDI preferences with device_id or uuid were not found " +
                "(package $packageState, Android user $currentUser, ${files.size} preference file(s) checked)"
        )
    }

    private fun currentUserId(): String {
        val direct = shell.text("am get-current-user 2>/dev/null || cmd activity get-current-user 2>/dev/null")
            .lineSequence().map(String::trim).firstOrNull { it.matches(Regex("[0-9]+")) }
        return direct ?: "0"
    }

    private fun packageDataRoot(): String? {
        return shell.text(
            "dumpsys package com.initialcoms.ridi 2>/dev/null | " +
                "sed -n 's/^[[:space:]]*dataDir=//p' | head -n 1"
        ).trim().takeIf(::isSafeRidiDataRoot)
    }

    private fun requireSafeOfficialPath(path: String) {
        val fixed = listOf("/data/data/com.initialcoms.ridi/")
        val dynamicPrivate = Regex("^/data/user(?:_de)?/[0-9]+/com\\.initialcoms\\.ridi/").containsMatchIn(path)
        val externalOfficial = path.contains("/Android/data/com.initialcoms.ridi/files/") &&
            (path.startsWith("/sdcard/") || path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/"))
        require(path.startsWith('/') && (dynamicPrivate || externalOfficial || fixed.any(path::startsWith))) {
            "Unsafe source path"
        }
        require(!path.contains("/../") && !path.contains('\n') && !path.contains('\r'))
    }

    private fun isSafeRidiDataRoot(path: String): Boolean =
        path == "/data/data/com.initialcoms.ridi" ||
            Regex("^/data/user(?:_de)?/[0-9]+/com\\.initialcoms\\.ridi/?$").matches(path)

    private fun isSafePreferencePath(path: String, roots: List<String>): Boolean =
        path.endsWith(".xml") && roots.any { root -> isUnder(path, root.trimEnd('/') + "/shared_prefs") }

    private fun isSafeExternalRoot(path: String): Boolean =
        (path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/") || path.startsWith("/sdcard/")) &&
            path.contains("/Android/data/com.initialcoms.ridi/files") &&
            !path.contains("/../") && !path.contains('\n') && !path.contains('\r')

    private fun isSafeSavedBookPath(path: String): Boolean = runCatching {
        requireSafeOfficialPath(path.trimEnd('/') + "/placeholder")
        true
    }.getOrDefault(false)

    private fun isUnder(path: String, root: String): Boolean =
        path == root || path.startsWith(root.trimEnd('/') + "/")

    private fun score(candidate: BookCandidate, savedPath: String?): Int {
        val realmMatch = savedPath?.trimEnd('/')?.let { candidate.sourceRoot == it } == true
        return (if (realmMatch) 1_000_000 else 0) +
            (if (candidate.rawPackage != null) 100_000 else 0) + candidate.files.size
    }

    companion object {
        const val OFFICIAL_PACKAGE = "com.initialcoms.ridi"
        private const val LOG_TAG = "RidiDecryptor"
    }
}
