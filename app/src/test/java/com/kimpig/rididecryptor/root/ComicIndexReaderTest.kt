package com.kimpig.rididecryptor.root

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.ObjectOutputStream

class ComicIndexReaderTest {
    @Test
    fun readsSerializedOfficialIndexWithoutCountingCoverTwice() {
        val snapshot = File.createTempFile("comic-index", ".idx")
        try {
            ObjectOutputStream(snapshot.outputStream()).use { output ->
                output.writeObject(
                    """{"front_cover_image":{"file_name":"cover.jpg","width":1200,"height":1800},"content_images":[{"file_name":"0.jpg","width":1200,"height":1800},{"file_name":"1.jpg","width":1200,"height":1800}]}"""
                )
            }

            val result = ComicIndexReader.read(snapshot)!!
            assertEquals(1, result.coverCount)
            assertEquals(2, result.contentCount)
        } finally {
            snapshot.delete()
        }
    }
}
