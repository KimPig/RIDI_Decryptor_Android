package com.kimpig.rididecryptor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCandidateTest {
    @Test
    fun openedEpubWithExtractedImagesRemainsEpub() {
        val book = BookCandidate(
            bookId = "2036000000",
            sourceRoot = "/data/user/0/com.initialcoms.ridi/files/books/2036000000",
            files = listOf(
                "/books/2036000000/2036000000.epub",
                "/books/2036000000/2036000000.dat",
                "/books/2036000000/extracted/OEBPS/Images/cover.jpg"
            ),
            sourceKind = SourceKind.ROOT,
            metadataFormat = "epub"
        )

        assertFalse(book.isComic)
        assertEquals("EPUB", book.displayFormat)
    }

    @Test
    fun genericExtractedImagesDoNotCreateComicCandidate() {
        val book = BookCandidate(
            bookId = "2036000000",
            sourceRoot = "/books/2036000000",
            files = listOf("/books/2036000000/extracted/OEBPS/Images/cover.jpg"),
            sourceKind = SourceKind.ROOT
        )

        assertFalse(book.isComic)
    }

    @Test
    fun comicIndexCountIncludesCoverExactlyOnce() {
        val book = BookCandidate(
            bookId = "2036000000",
            sourceRoot = "/books/2036000000",
            files = listOf("/books/2036000000/2036000000.zip"),
            sourceKind = SourceKind.ROOT,
            metadataFormat = "comic",
            pageCount = 80,
            comicIndexCoverCount = 1,
            comicIndexContentCount = 79
        )

        assertEquals(80, book.displayedComicPages)
        assertTrue(book.realmPageCountIncludesCover == true)
    }

    @Test
    fun identifiesRealmCountThatExcludesCover() {
        val book = BookCandidate(
            bookId = "2036000000",
            sourceRoot = "/books/2036000000",
            files = listOf("/books/2036000000/2036000000.zip"),
            sourceKind = SourceKind.ROOT,
            metadataFormat = "comic",
            pageCount = 79,
            comicIndexCoverCount = 1,
            comicIndexContentCount = 79
        )

        assertEquals(79, book.displayedComicPages)
        assertFalse(book.realmPageCountIncludesCover!!)
    }

    @Test
    fun unknownRealmCountRelationshipIsNotGuessed() {
        val book = BookCandidate(
            bookId = "2036000000",
            sourceRoot = "/books/2036000000",
            files = listOf("/books/2036000000/2036000000.zip"),
            sourceKind = SourceKind.ROOT,
            metadataFormat = "comic",
            pageCount = 75,
            comicIndexCoverCount = 1,
            comicIndexContentCount = 79
        )

        assertNull(book.realmPageCountIncludesCover)
    }

    @Test
    fun realmPageCountTakesPriorityOverIndexFallback() {
        val book = BookCandidate(
            bookId = "2036000000",
            sourceRoot = "/books/2036000000",
            files = listOf("/books/2036000000/2036000000.zip"),
            sourceKind = SourceKind.ROOT,
            metadataFormat = "comic",
            pageCount = 210,
            comicIndexCoverCount = 1,
            comicIndexContentCount = 209
        )

        assertEquals(210, book.displayedComicPages)
    }
}
