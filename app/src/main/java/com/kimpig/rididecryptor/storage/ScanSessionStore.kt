package com.kimpig.rididecryptor.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.kimpig.rididecryptor.core.BookCandidate
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class ScanSessionPaths(
    val root: File,
    val source: File,
    val covers: File,
    val indexes: File
)

/** App-owned, process-session snapshot. Official files are only ever copied into staging. */
object ScanSessionStore {
    private const val ROOT = "scan-session"

    fun clearAtProcessStart(context: Context) = root(context).deleteRecursively()

    fun clear(context: Context) = root(context).deleteRecursively()

    fun begin(context: Context): ScanSessionPaths {
        val root = root(context)
        val staging = File(root, "staging")
        staging.deleteRecursively()
        val source = File(staging, "source")
        val covers = File(staging, "covers")
        val indexes = File(staging, "indexes")
        listOf(source, covers, indexes).forEach { directory ->
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Could not create private scan snapshot")
            }
        }
        return ScanSessionPaths(staging, source, covers, indexes)
    }

    fun commit(context: Context, staging: ScanSessionPaths, books: List<BookCandidate>): File {
        writeMetadata(File(staging.root, "metadata.db"), books)
        File(staging.root, "manifest.json").writeText(
            JSONObject()
                .put("createdAt", System.currentTimeMillis())
                .put("books", books.size)
                .put("version", 1)
                .toString(2)
        )
        val parent = root(context)
        val active = File(parent, "active")
        val previous = File(parent, "previous")
        previous.deleteRecursively()
        if (active.exists() && !active.renameTo(previous)) {
            throw IOException("Could not preserve the current scan snapshot")
        }
        if (!staging.root.renameTo(active)) {
            if (previous.exists()) previous.renameTo(active)
            throw IOException("Could not activate the new scan snapshot")
        }
        previous.deleteRecursively()
        return active
    }

    fun abort(staging: ScanSessionPaths?) {
        staging?.root?.deleteRecursively()
    }

    fun activeRealm(context: Context): File? =
        File(root(context), "active/source/Library.realm").takeIf(File::isFile)

    fun activeRoot(context: Context): File? = File(root(context), "active").takeIf(File::isDirectory)

    fun remap(path: String?, staging: ScanSessionPaths, active: File): String? = path?.let {
        val prefix = staging.root.absolutePath + File.separator
        if (it.startsWith(prefix)) File(active, it.removePrefix(prefix)).absolutePath else it
    }

    private fun root(context: Context) = File(context.cacheDir, ROOT)

    private fun writeMetadata(target: File, books: List<BookCandidate>) {
        target.delete()
        SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
            db.execSQL(
                """CREATE TABLE books (
                    book_id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT, format TEXT,
                    series_id TEXT, series_title TEXT, display_order INTEGER, source_root TEXT NOT NULL,
                    cover_name TEXT, page_count INTEGER, scanned_at INTEGER NOT NULL
                )""".trimIndent()
            )
            db.beginTransaction()
            try {
                books.forEach { book ->
                    db.insertOrThrow("books", null, ContentValues().apply {
                        put("book_id", book.bookId)
                        put("title", book.displayTitle)
                        put("author", book.author)
                        put("format", book.displayFormat)
                        put("series_id", book.seriesId)
                        put("series_title", book.seriesTitle)
                        put("display_order", book.displayOrder)
                        put("source_root", book.sourceRoot)
                        put("cover_name", book.coverCachePath?.substringAfterLast(File.separatorChar))
                        put("page_count", book.displayedComicPages)
                        put("scanned_at", System.currentTimeMillis())
                    })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
