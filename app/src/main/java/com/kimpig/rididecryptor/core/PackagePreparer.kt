package com.kimpig.rididecryptor.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

class PackagePreparer {
    fun unpackIfNeeded(book: PreparedBook): PreparedBook {
        val raw = book.directory.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals("${book.bookId}_raw.zip", true) }
            ?: return book
        val extracted = File(book.directory, "package")
        if (!extracted.mkdirs() && !extracted.isDirectory) {
            throw IOException("Could not create package workspace")
        }

        var entries = 0
        var bytes = 0L
        ZipFile(raw).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                entries++
                if (entries > 50_000) throw IOException("Package has too many entries")
                val target = safeTarget(extracted, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    if (entry.time >= 0L) target.setLastModified(entry.time)
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            if (bytes > 12L * 1024 * 1024 * 1024) {
                                throw IOException("Expanded package is unexpectedly large")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (entry.time >= 0L) target.setLastModified(entry.time)
            }
        }
        return PreparedBook(book.bookId, extracted, book.title)
    }

    private fun safeTarget(root: File, name: String): File {
        val target = File(root, name).canonicalFile
        val prefix = root.canonicalPath + File.separator
        if (!target.path.startsWith(prefix)) throw IOException("Unsafe ZIP entry")
        return target
    }
}
