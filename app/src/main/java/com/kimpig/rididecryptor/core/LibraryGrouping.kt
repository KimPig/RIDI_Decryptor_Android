package com.kimpig.rididecryptor.core

import java.util.Locale

data class LibraryGroup(
    val key: String,
    val title: String,
    val books: List<BookCandidate>,
    val isSeries: Boolean
) {
    val decryptedCount: Int get() = books.count(BookCandidate::isDecrypted)
}

object LibraryGrouping {
    private val koreanVolume = Regex("^(.*?)(?:\\s*(?:제\\s*)?(\\d+(?:\\.\\d+)?)\\s*권)\\s*$")
    private val latinVolume = Regex("^(.*?)(?:\\s+vol(?:ume)?\\.?\\s*(\\d+(?:\\.\\d+)?))\\s*$", RegexOption.IGNORE_CASE)

    fun group(books: List<BookCandidate>): List<LibraryGroup> {
        val candidates = books.groupBy(::candidateKey)
        return candidates.values.flatMap { matches ->
            if (matches.size < 2) {
                matches.map { book ->
                    LibraryGroup("book:${book.bookId}", book.displayTitle, listOf(book), false)
                }
            } else {
                val sorted = matches.sortedWith(
                    compareBy<BookCandidate> {
                        it.displayOrder?.toDouble() ?: parsedVolume(it.displayTitle) ?: Double.MAX_VALUE
                    }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                )
                val first = sorted.first()
                val title = first.seriesTitle?.takeIf(String::isNotBlank)
                    ?: seriesTitle(first.displayTitle)
                    ?: first.displayTitle
                val officialKey = first.seriesId?.takeIf(String::isNotBlank)
                LibraryGroup(
                    key = officialKey?.let { "series:$it" }
                        ?: "fallback:${title.lowercase(Locale.ROOT)}:${first.author.orEmpty().lowercase(Locale.ROOT)}",
                    title = title,
                    books = sorted,
                    isSeries = true
                ).let(::listOf)
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun volumeLabel(book: BookCandidate): String? {
        val value = parsedVolume(book.displayTitle) ?: return null
        return if (value % 1.0 == 0.0) "${value.toInt()}권" else "${value}권"
    }

    fun seriesTitleFor(book: BookCandidate): String? =
        book.seriesTitle?.takeIf(String::isNotBlank) ?: seriesTitle(book.displayTitle)

    private fun candidateKey(book: BookCandidate): String {
        val official = book.seriesId?.takeIf(String::isNotBlank)
        if (official != null) return "official:$official"
        val title = book.seriesTitle?.takeIf(String::isNotBlank) ?: seriesTitle(book.displayTitle)
        return if (title == null) {
            "book:${book.bookId}"
        } else {
            "fallback:${title.lowercase(Locale.ROOT)}:${book.author.orEmpty().lowercase(Locale.ROOT)}"
        }
    }

    private fun seriesTitle(title: String): String? =
        (koreanVolume.matchEntire(title) ?: latinVolume.matchEntire(title))
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)

    private fun parsedVolume(title: String): Double? =
        (koreanVolume.matchEntire(title) ?: latinVolume.matchEntire(title))
            ?.groupValues?.getOrNull(2)?.toDoubleOrNull()
}
