package com.kimpig.rididecryptor.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

object ManualPackageImporter {
    fun import(context: Context, uri: Uri): BookCandidate {
        val directory = File(context.cacheDir, "manual/${System.currentTimeMillis()}")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create private import cache")
        val displayName = queryName(context, uri) ?: "manual_raw.zip"
        val temporary = File(directory, "incoming.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output, 128 * 1024) }
        } ?: throw IOException("Could not open the selected file")
        val bookId = detectBookId(displayName, temporary)
        val finalFile = File(directory, if (displayName.contains("_raw", true)) "${bookId}_raw.zip" else "$bookId.zip")
        if (!temporary.renameTo(finalFile)) temporary.copyTo(finalFile, overwrite = true)
        return CandidateDiscovery.manual(finalFile.absolutePath.replace('\\', '/'), bookId)
    }

    private fun queryName(context: Context, uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun detectBookId(displayName: String, zip: File): String {
        Regex("^(.+?)(?:_raw)?\\.(?:zip|epub|pdf)$", RegexOption.IGNORE_CASE)
            .matchEntire(displayName)?.groupValues?.get(1)?.takeIf { it.length >= 4 }?.let { return it }
        return ZipFile(zip).use { archive ->
            archive.entries().asSequence().map { it.name.substringAfterLast('/') }.mapNotNull {
                Regex("^(.+?)\\.(?:dat|epub|pdf|txt|idx|zip)$", RegexOption.IGNORE_CASE)
                    .matchEntire(it)?.groupValues?.get(1)
            }.firstOrNull()
        } ?: throw IOException("Could not determine the book ID from this package")
    }
}
