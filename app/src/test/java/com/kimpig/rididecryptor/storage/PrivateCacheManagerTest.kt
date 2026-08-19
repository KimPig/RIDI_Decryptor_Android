package com.kimpig.rididecryptor.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PrivateCacheManagerTest {
    @Test
    fun cleanupScopesNeverDeleteEachOtherOrUnrelatedFiles() {
        val root = Files.createTempDirectory("ridi-cache-test").toFile()
        try {
            val manual = root.resolve("manual/import/book_raw.zip").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val work = root.resolve("work/job/source.dat").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(4, 5))
            }
            val unrelated = root.resolve("unrelated/keep.bin").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(6))
            }

            PrivateCacheManager.clear(root, PrivateCacheScope.TEMPORARY_FILES)
            assertTrue(manual.exists())
            assertFalse(work.exists())
            assertTrue(unrelated.exists())

            PrivateCacheManager.clear(root, PrivateCacheScope.IMPORTED_PACKAGES)
            assertFalse(manual.exists())
            assertTrue(unrelated.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
