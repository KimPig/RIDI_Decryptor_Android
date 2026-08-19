package com.kimpig.rididecryptor.root

import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream

data class ComicIndexInfo(val coverCount: Int, val contentCount: Int)

object ComicIndexReader {
    fun read(snapshot: File): ComicIndexInfo? {
        val json = runCatching {
            ObjectInputStream(FileInputStream(snapshot)).use { it.readObject() as? String }
        }.getOrNull() ?: runCatching { snapshot.readText(Charsets.UTF_8) }.getOrNull()
        if (json.isNullOrBlank()) return null
        val contentStart = valueStart(json, "contentImages")
            ?: valueStart(json, "content_images")
            ?: return null
        val content = arrayElementCount(json, contentStart) ?: return null
        val coverStart = valueStart(json, "front_cover_image")
        val cover = if (coverStart == null || json.regionMatches(coverStart, "null", 0, 4, true)) 0 else 1
        return ComicIndexInfo(cover, content)
    }

    private fun valueStart(json: String, key: String): Int? {
        val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:").find(json) ?: return null
        var index = match.range.last + 1
        while (index < json.length && json[index].isWhitespace()) index++
        return index.takeIf { it < json.length }
    }

    private fun arrayElementCount(json: String, start: Int): Int? {
        if (json.getOrNull(start) != '[') return null
        var depth = 0
        var objectDepth = 0
        var commas = 0
        var hasElement = false
        var inString = false
        var escaped = false
        for (index in start until json.length) {
            val character = json[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> {
                    inString = true
                    if (depth == 1) hasElement = true
                }
                '[' -> {
                    depth++
                    if (depth > 1) hasElement = true
                }
                '{' -> {
                    objectDepth++
                    if (depth == 1) hasElement = true
                }
                '}' -> if (objectDepth > 0) objectDepth--
                ']' -> {
                    depth--
                    if (depth == 0) return if (hasElement) commas + 1 else 0
                    if (depth < 0) return null
                }
                ',' -> if (depth == 1 && objectDepth == 0) commas++
                else -> if (depth == 1 && !character.isWhitespace()) hasElement = true
            }
        }
        return null
    }
}
