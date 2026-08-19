package com.kimpig.rididecryptor.storage

import android.net.Uri
import android.provider.DocumentsContract
import com.kimpig.rididecryptor.root.RootShell

data class RootOutputFile(
    val name: String,
    val path: String,
    val size: Long
)

class RootOutputDiscovery(private val shell: RootShell = RootShell()) {
    /** Returns null only when the selected document provider has no safe filesystem path. */
    fun discover(outputTree: Uri?): List<RootOutputFile>? {
        if (!shell.isAvailable()) return emptyList()
        val user = currentUserId()
        val requestedRoots = if (outputTree == null) defaultOutputRoots(user) else treeOutputRoots(outputTree, user)
            ?: return null
        val roots = requestedRoots.mapNotNull(::resolveOutputRoot).distinct()
        return roots.flatMap(::listOutputFiles)
            .distinctBy(RootOutputFile::path)
            .sortedByDescending(RootOutputFile::name)
    }

    private fun defaultOutputRoots(user: String): List<String> = listOf(
        "/storage/emulated/$user/Download/RIDI_Decryptor",
        "/sdcard/Download/RIDI_Decryptor"
    )

    private fun treeOutputRoots(treeUri: Uri, user: String): List<String>? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        if (!isSafeRelativePath(relative)) return emptyList()
        val suffix = relative.takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()
        return if (volume.equals("primary", ignoreCase = true)) {
            listOf("/storage/emulated/$user$suffix", "/sdcard$suffix")
        } else if (volume.matches(Regex("[A-Za-z0-9._-]+"))) {
            listOf("/storage/$volume$suffix", "/mnt/media_rw/$volume$suffix")
        } else {
            emptyList()
        }
    }

    private fun currentUserId(): String {
        return shell.text("am get-current-user 2>/dev/null || cmd activity get-current-user 2>/dev/null", 20)
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.matches(Regex("[0-9]+")) }
            ?: "0"
    }

    private fun resolveOutputRoot(requested: String): String? {
        if (!isSafeOutputRoot(requested)) return null
        val resolved = shell.text(
            "if [ -d ${RootShell.quote(requested)} ]; then readlink -f ${RootShell.quote(requested)}; fi",
            20
        ).trim().trimEnd('/')
        return resolved.takeIf(::isSafeOutputRoot)
    }

    private fun listOutputFiles(root: String): List<RootOutputFile> {
        val paths = shell.text(
            "if [ -d ${RootShell.quote(root)} ]; then " +
                "for f in ${RootShell.quote(root)}/*; do " +
                "[ -f \"\$f\" ] && printf '%s\\n' \"\$f\"; done; fi",
            45
        ).lineSequence().map(String::trim).filter(String::isNotBlank)

        return paths.mapNotNull { path ->
            if (!isUnder(path, root)) return@mapNotNull null
            val name = path.substringAfterLast('/')
            val header = runCatching { shell.readFilePrefix(path, 4) }.getOrNull() ?: return@mapNotNull null
            if (!isValidOutput(name, header)) return@mapNotNull null
            val size = runCatching { shell.fileLength(path) }.getOrDefault(0L)
            RootOutputFile(name, path, size)
        }.toList()
    }

    private fun isSafeRelativePath(path: String): Boolean =
        !path.contains("..") && !path.contains('\n') && !path.contains('\r') && !path.contains('\u0000')

    private fun isSafeOutputRoot(path: String): Boolean {
        if (path.contains("/../") || path.contains('\n') || path.contains('\r') || path.contains('\u0000')) return false
        val sharedStorage = Regex("^/storage/emulated/[0-9]+(?:/.*)?$").matches(path) ||
            Regex("^/(?:storage|mnt/media_rw)/[A-Za-z0-9._-]+(?:/.*)?$").matches(path) ||
            Regex("^/sdcard(?:/.*)?$").matches(path)
        val officialRidi = path.contains("/Android/data/com.initialcoms.ridi", ignoreCase = true)
        return sharedStorage && !officialRidi
    }

    private fun isUnder(path: String, root: String): Boolean =
        path.startsWith(root.trimEnd('/') + "/") &&
            !path.contains("/../") && !path.contains('\n') && !path.contains('\r')

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        internal fun isValidOutput(name: String, header: ByteArray): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "pdf" -> header.contentEquals("%PDF".toByteArray())
                "epub", "zip" -> header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
                else -> false
            }
        }
    }
}
