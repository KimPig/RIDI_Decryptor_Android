package com.kimpig.rididecryptor.storage

import android.content.Context
import java.io.File
import java.io.IOException

enum class PrivateCacheScope { IMPORTED_PACKAGES, TEMPORARY_FILES }

data class PrivateCacheSummary(
    val files: Int = 0,
    val directories: Int = 0,
    val bytes: Long = 0
) {
    val isEmpty: Boolean get() = files == 0 && directories == 0

    operator fun plus(other: PrivateCacheSummary) = PrivateCacheSummary(
        files + other.files,
        directories + other.directories,
        bytes + other.bytes
    )
}

/** Deletes only fixed, app-owned cache children. This class never invokes a root shell. */
object PrivateCacheManager {
    private val temporaryChildren = listOf(
        "work",
        "realm-snapshot",
        "realm-debug-snapshot",
        "official-cover-snapshots",
        "official-index-snapshots",
        "scan-session"
    )

    fun inspect(context: Context, scope: PrivateCacheScope): PrivateCacheSummary =
        inspect(context.cacheDir, scope)

    fun clear(context: Context, scope: PrivateCacheScope): PrivateCacheSummary =
        clear(context.cacheDir, scope)

    internal fun inspect(cacheRoot: File, scope: PrivateCacheScope): PrivateCacheSummary =
        targets(cacheRoot, scope).fold(PrivateCacheSummary()) { total, target -> total + measure(target) }

    internal fun clear(cacheRoot: File, scope: PrivateCacheScope): PrivateCacheSummary {
        val targets = targets(cacheRoot, scope)
        val before = targets.fold(PrivateCacheSummary()) { total, target -> total + measure(target) }
        targets.forEach(::deleteNode)
        return before
    }

    private fun targets(cacheRoot: File, scope: PrivateCacheScope): List<File> {
        val root = cacheRoot.canonicalFile
        val names = when (scope) {
            PrivateCacheScope.IMPORTED_PACKAGES -> listOf("manual")
            PrivateCacheScope.TEMPORARY_FILES -> temporaryChildren
        }
        return names.map { name ->
            require('/' !in name && '\\' !in name && name !in setOf(".", ".."))
            File(root, name).absoluteFile.also { target ->
                require(target.parentFile == root) { "Cache cleanup target escaped private cache" }
            }
        }
    }

    private fun measure(file: File): PrivateCacheSummary {
        if (!file.exists()) return PrivateCacheSummary()
        if (isSymbolicLink(file)) return PrivateCacheSummary(files = 1)
        if (file.isFile) return PrivateCacheSummary(files = 1, bytes = file.length().coerceAtLeast(0))
        if (!file.isDirectory) return PrivateCacheSummary(files = 1)
        return file.listFiles().orEmpty().fold(PrivateCacheSummary(directories = 1)) { total, child ->
            total + measure(child)
        }
    }

    private fun deleteNode(file: File) {
        if (!file.exists()) return
        if (!isSymbolicLink(file) && file.isDirectory) file.listFiles().orEmpty().forEach(::deleteNode)
        if (!file.delete() && file.exists()) throw IOException("Could not clear private cache item ${file.name}")
    }

    private fun isSymbolicLink(file: File): Boolean {
        val parent = file.parentFile?.canonicalFile ?: return false
        val normalized = File(parent, file.name)
        return normalized.canonicalFile != normalized.absoluteFile
    }
}
