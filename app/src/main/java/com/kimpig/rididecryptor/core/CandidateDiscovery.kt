package com.kimpig.rididecryptor.core

object CandidateDiscovery {
    private val idFromFile = Regex("^(.+?)(?:_raw)?\\.(?:zip|dat|epub|pdf|txt|idx)$", RegexOption.IGNORE_CASE)
    private val usefulExtension = Regex(".*\\.(?:zip|dat|epub|pdf|txt|idx|jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE)

    fun fromRootPaths(paths: List<String>): List<BookCandidate> {
        val normalized = paths.asSequence()
            .map { it.trim().replace('\\', '/') }
            .filter { it.startsWith('/') && !it.contains('\u0000') && usefulExtension.matches(it) }
            .distinct()
            .toList()

        val groups = linkedMapOf<String, MutableList<String>>()
        normalized.forEach { path ->
            val root = bookRoot(path) ?: return@forEach
            groups.getOrPut(root) { mutableListOf() }.add(path)
        }

        return groups.mapNotNull { (root, files) ->
            val id = idFor(root, files) ?: return@mapNotNull null
            BookCandidate(
                bookId = id,
                sourceRoot = root,
                files = files.sorted(),
                sourceKind = SourceKind.ROOT
            )
        }.sortedBy { it.bookId }
    }

    fun manual(rawPackage: String, bookId: String): BookCandidate = BookCandidate(
        bookId = bookId,
        sourceRoot = rawPackage.substringBeforeLast('/', ""),
        files = listOf(rawPackage),
        sourceKind = SourceKind.MANUAL,
        label = "$bookId (manual)"
    )

    private fun bookRoot(path: String): String? {
        val marker = "/books/"
        val markerIndex = path.indexOf(marker)
        if (markerIndex >= 0) {
            val idStart = markerIndex + marker.length
            val idEnd = path.indexOf('/', idStart)
            if (idEnd > idStart) return path.substring(0, idEnd)
        }
        return path.substringBeforeLast('/', "").takeIf(String::isNotBlank)
    }

    private fun idFor(root: String, files: List<String>): String? {
        val rootName = root.substringAfterLast('/')
        if (rootName.matches(Regex("[A-Za-z0-9_-]{4,}"))) return rootName
        return files.asSequence()
            .map { it.substringAfterLast('/') }
            .mapNotNull { idFromFile.matchEntire(it)?.groupValues?.get(1) }
            .firstOrNull()
    }
}
