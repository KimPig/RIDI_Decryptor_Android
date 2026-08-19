package com.kimpig.rididecryptor.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import com.kimpig.rididecryptor.core.DecryptResult
import com.kimpig.rididecryptor.core.DecryptedOutput
import com.kimpig.rididecryptor.core.ProgressUpdate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

class OutputStore(private val context: Context) {
    fun save(
        result: DecryptResult,
        outputTree: Uri? = null,
        progress: (ProgressUpdate) -> Unit = {}
    ): String {
        val location = if (outputTree != null) {
            saveToTree(result, outputTree, progress).toString()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(result, progress).toString()
        } else {
            saveLegacy(result, progress).absolutePath
        }
        rememberOutput(location, result)
        return location
    }

    fun discover(bookIds: Set<String>, outputTree: Uri? = null): Map<String, List<DecryptedOutput>> {
        if (bookIds.isEmpty()) return emptyMap()
        val found = if (outputTree != null) discoverTree(outputTree) else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            discoverMediaStore()
        } else {
            discoverLegacy()
        }
        return found.mapNotNull { output ->
            matchBookId(output.displayName, bookIds)?.let { it to output }
        }.groupBy({ it.first }, { it.second })
    }

    private fun saveToTree(result: DecryptResult, treeUri: Uri, progress: (ProgressUpdate) -> Unit): Uri {
        require(!Uri.decode(treeUri.toString()).contains("com.initialcoms.ridi", ignoreCase = true)) {
            "The official RIDI directory cannot be selected as an output folder"
        }
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val replacing = OutputSettings.behavior(context) == ExistingFileBehavior.REPLACE
        val existing = if (replacing) findDocument(treeUri, parentId, result.fileName) else null
        val name = when {
            existing != null -> uniqueDocumentName(treeUri, parentId, ".${result.fileName}.ridi-new")
            replacing -> result.fileName
            else -> uniqueDocumentName(treeUri, parentId, result.fileName)
        }
        val target = DocumentsContract.createDocument(resolver, parent, result.mimeType, name)
            ?: throw IOException("Could not create a file in the selected output folder")
        try {
            resolver.openOutputStream(target, "w")?.use { output ->
                copyResult(result, output, progress)
            } ?: throw IOException("Could not open the selected output folder")
            resolver.openInputStream(target)?.use { input ->
                verifySaved(input, result, progress)
            } ?: throw IOException("Could not verify the saved output")
            if (existing == null) return target
            val backupName = uniqueDocumentName(treeUri, parentId, ".${result.fileName}.ridi-backup")
            val backup = DocumentsContract.renameDocument(resolver, existing, backupName)
                ?: throw IOException("This folder provider does not support safe replacement")
            return try {
                val finalTarget = DocumentsContract.renameDocument(resolver, target, result.fileName)
                    ?: throw IOException("Could not finalize replacement output")
                DocumentsContract.deleteDocument(resolver, backup)
                finalTarget
            } catch (error: Throwable) {
                runCatching { DocumentsContract.renameDocument(resolver, backup, result.fileName) }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        }
    }

    private fun uniqueDocumentName(treeUri: Uri, parentId: String, requested: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(existing::add)
        }
        if (requested !in existing) return requested
        val base = requested.substringBeforeLast('.', requested)
        val extension = requested.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while ("$base ($index)$extension" in existing) index++
        return "$base ($index)$extension"
    }

    private fun findDocument(treeUri: Uri, parentId: String, requested: String): Uri? {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == requested) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(result: DecryptResult, progress: (ProgressUpdate) -> Unit): Uri {
        val behavior = OutputSettings.behavior(context)
        val existing = mediaStoreNames()
        val replacementTargets = if (behavior == ExistingFileBehavior.REPLACE) existingMediaStoreEntries(result.fileName) else emptyList()
        val outputName = when {
            replacementTargets.isNotEmpty() -> uniqueName(".${result.fileName}.ridi-new", existing)
            behavior == ExistingFileBehavior.KEEP_BOTH -> uniqueName(result.fileName, existing)
            else -> result.fileName
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outputName)
            put(MediaStore.Downloads.MIME_TYPE, result.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RIDI_Decryptor")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a Downloads entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                copyResult(result, output, progress)
            } ?: throw IOException("Could not open the output file")
            resolver.openInputStream(uri)?.use { input ->
                verifySaved(input, result, progress)
            } ?: throw IOException("Could not verify the saved output")
            if (replacementTargets.isNotEmpty()) {
                val backups = mutableListOf<Uri>()
                try {
                    replacementTargets.forEachIndexed { index, old ->
                        val backupName = ".${result.fileName}.ridi-backup-${System.currentTimeMillis()}-$index"
                        val changed = resolver.update(old, ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, backupName)
                        }, null, null)
                        if (changed != 1) throw IOException("Could not prepare safe replacement")
                        backups += old
                    }
                    val changed = resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, result.fileName)
                    }, null, null)
                    if (changed != 1) throw IOException("Could not finalize replacement output")
                    backups.forEach { resolver.delete(it, null, null) }
                } catch (error: Throwable) {
                    backups.forEach { old ->
                        runCatching { resolver.update(old, ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, result.fileName)
                        }, null, null) }
                    }
                    throw error
                }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreNames(): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("%RIDI_Decryptor%"),
            null
        )?.use { cursor -> while (cursor.moveToNext()) cursor.getString(0)?.let(names::add) }
        return names
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun existingMediaStoreEntries(name: String): List<Uri> {
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf("%RIDI_Decryptor%", name),
            null
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString()))
            }
        } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(result: DecryptResult, progress: (ProgressUpdate) -> Unit): File {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "RIDI_Decryptor"
        )
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create Download/RIDI_Decryptor")
        }
        val direct = File(directory, result.fileName)
        val replacing = OutputSettings.behavior(context) == ExistingFileBehavior.REPLACE && direct.exists()
        val target = if (OutputSettings.behavior(context) == ExistingFileBehavior.KEEP_BOTH) {
            uniqueFile(directory, result.fileName)
        } else if (replacing) {
            File(directory, ".${result.fileName}.ridi-new")
        } else {
            direct
        }
        target.delete()
        try {
            FileOutputStream(target).use { output -> copyResult(result, output, progress) }
            FileInputStream(target).use { input -> verifySaved(input, result, progress) }
            if (replacing) {
                val backup = File(directory, ".${result.fileName}.ridi-backup-${System.currentTimeMillis()}")
                if (!direct.renameTo(backup)) throw IOException("Could not prepare safe replacement")
                try {
                    if (!target.renameTo(direct)) throw IOException("Could not finalize replacement output")
                    backup.delete()
                } catch (error: Throwable) {
                    runCatching { backup.renameTo(direct) }
                    throw error
                }
            }
            return if (replacing) direct else target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun copyResult(
        result: DecryptResult,
        output: java.io.OutputStream,
        progress: (ProgressUpdate) -> Unit
    ) {
        FileInputStream(result.temporaryFile).use { input ->
            val buffer = ByteArray(128 * 1024)
            var completed = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                completed += count
                progress(ProgressUpdate("Saving verified output", completed, result.temporaryFile.length()))
            }
        }
    }

    private fun verifySaved(input: InputStream, result: DecryptResult, progress: (ProgressUpdate) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var completed = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            completed += count
            progress(ProgressUpdate("Verifying saved output", completed, result.temporaryFile.length()))
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(result.sha256, ignoreCase = true)) {
            throw IOException("Saved output SHA-256 validation failed")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun discoverMediaStore(): List<DecryptedOutput> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.RELATIVE_PATH
        )
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("%RIDI_Decryptor%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: continue
                    val size = cursor.getLong(2)
                    val relativePath = cursor.getString(3).orEmpty()
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                    if (validOutput(name) { resolver.openInputStream(uri) }) {
                        val friendlyBase = relativePath.trimEnd('/').ifBlank { "Download/RIDI_Decryptor" }
                        add(outputRecord(name, uri.toString(), size, "$friendlyBase/$name"))
                    }
                }
            }
        } ?: emptyList()
    }

    private fun discoverTree(treeUri: Uri): List<DecryptedOutput> {
        if (Uri.decode(treeUri.toString()).contains("com.initialcoms.ridi", ignoreCase = true)) return emptyList()
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val folderName = treeFolderName(treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val size = cursor.getLong(2)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    if (validOutput(name) { resolver.openInputStream(uri) }) {
                        add(outputRecord(name, uri.toString(), size, "$folderName/$name"))
                    }
                }
            }
        } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun discoverLegacy(): List<DecryptedOutput> {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "RIDI_Decryptor"
        )
        return directory.listFiles()?.mapNotNull { file ->
            if (file.isFile && validOutput(file.name) { FileInputStream(file) }) {
                outputRecord(file.name, file.absolutePath, file.length(), file.absolutePath)
            } else null
        } ?: emptyList()
    }

    private fun validOutput(name: String, input: () -> InputStream?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("epub", "pdf", "zip")) return false
        return runCatching {
            input()?.use { stream ->
                val header = ByteArray(4)
                if (stream.read(header) != 4) return@use false
                if (extension == "pdf") header.contentEquals("%PDF".toByteArray())
                else header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            } ?: false
        }.getOrDefault(false)
    }

    private fun outputRecord(name: String, location: String, size: Long, displayLocation: String): DecryptedOutput {
        val hash = context.getSharedPreferences(OUTPUT_PREFS, Context.MODE_PRIVATE).getString(location, null)
        return DecryptedOutput(name, location, name.substringAfterLast('.').uppercase(), size, hash, displayLocation)
    }

    private fun treeFolderName(treeUri: Uri): String {
        val resolver = context.contentResolver
        return runCatching {
            val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            resolver.query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "Selected folder"
    }

    private fun matchBookId(name: String, bookIds: Set<String>): String? = bookIds.firstOrNull { id ->
        Regex("(?:^|[^A-Za-z0-9])${Regex.escape(id)}(?:[^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(name)
    }

    private fun rememberOutput(location: String, result: DecryptResult) {
        context.getSharedPreferences(OUTPUT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(location, result.sha256).apply()
    }

    private fun uniqueFile(directory: File, name: String): File {
        val direct = File(directory, name)
        if (!direct.exists()) return direct
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while (true) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun uniqueName(requested: String, existing: Set<String>): String {
        if (requested !in existing) return requested
        val base = requested.substringBeforeLast('.', requested)
        val extension = requested.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while ("$base ($index)$extension" in existing) index++
        return "$base ($index)$extension"
    }

    companion object {
        private const val OUTPUT_PREFS = "verified_outputs"
    }
}
