package com.kimpig.rididecryptor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupingTest {
    @Test
    fun groupsKoreanVolumesByTitleAndAuthor() {
        val books = listOf(3, 1, 2).map { volume ->
            BookCandidate(
                bookId = "book-$volume",
                sourceRoot = "/books/book-$volume",
                files = emptyList(),
                sourceKind = SourceKind.ROOT,
                label = "마케 ${volume}권",
                author = "Author"
            )
        }

        val group = LibraryGrouping.group(books).single()
        assertTrue(group.isSeries)
        assertEquals("마케", group.title)
        assertEquals(listOf("book-1", "book-2", "book-3"), group.books.map { it.bookId })
    }

    @Test
    fun doesNotGroupSameTitleWithDifferentAuthors() {
        val books = listOf("A", "B").mapIndexed { index, author ->
            BookCandidate(
                bookId = "book-$index",
                sourceRoot = "/books/book-$index",
                files = emptyList(),
                sourceKind = SourceKind.ROOT,
                label = "마케 ${index + 1}권",
                author = author
            )
        }

        assertEquals(2, LibraryGrouping.group(books).size)
        assertTrue(LibraryGrouping.group(books).all { !it.isSeries })
    }

    @Test
    fun leavesStandaloneBookUngrouped() {
        val book = BookCandidate("id", "/books/id", emptyList(), SourceKind.ROOT, label = "Standalone")
        assertFalse(LibraryGrouping.group(listOf(book)).single().isSeries)
    }

    @Test
    fun officialSeriesIdOverridesTitleFallback() {
        val books = listOf(
            BookCandidate(
                bookId = "official-later",
                sourceRoot = "/books/official-later",
                files = emptyList(),
                sourceKind = SourceKind.ROOT,
                label = "A title that sorts first alphabetically",
                seriesId = "series-42",
                displayOrder = 20
            ),
            BookCandidate(
                bookId = "official-first",
                sourceRoot = "/books/official-first",
                files = emptyList(),
                sourceKind = SourceKind.ROOT,
                label = "Z title that sorts last alphabetically",
                seriesId = "series-42",
                displayOrder = 10
            )
        )

        val group = LibraryGrouping.group(books).single()
        assertTrue(group.isSeries)
        assertEquals(listOf("official-first", "official-later"), group.books.map { it.bookId })
    }
}
