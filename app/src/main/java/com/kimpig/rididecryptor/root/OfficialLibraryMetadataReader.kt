package com.kimpig.rididecryptor.root

import android.content.Context
import io.realm.DynamicRealm
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmFieldType
import java.io.File
import java.util.Date

data class LocalBookMetadata(
    val bookId: String,
    val title: String?,
    val author: String?,
    val format: String?,
    val expiresAt: Date?,
    val savedPath: String?,
    val pageCount: Int?,
    val fileSizeBytes: Long?,
    val downloadedAt: Date?,
    val lastOpenedAt: Date?,
    val isDownloaded: Boolean?,
    val invalidatedType: String?,
    val comicQuality: String?,
    val seriesId: String?,
    val seriesTitle: String?,
    val displayOrder: Int?
)

class OfficialLibraryMetadataReader(private val shell: RootShell) {
    fun read(context: Context, dataRoots: List<String>, snapshot: File): Map<String, LocalBookMetadata> {
        Realm.init(context.applicationContext)
        val realmPaths = dataRoots.flatMap { root ->
            listOf(
                root.trimEnd('/') + "/files/Library.realm",
                root.trimEnd('/') + "/files/Library.db",
                root.trimEnd('/') + "/files/RidibooksV2.db"
            )
        }.distinct()

        val existingRealmPaths = realmPaths.filter(shell::isRegularFile)
        if (existingRealmPaths.isEmpty()) {
            throw IllegalStateException("Official library metadata was not found")
        }

        var firstError: Throwable? = null
        existingRealmPaths.forEach { source ->
            try {
                snapshot.parentFile?.mkdirs()
                snapshot.delete()
                shell.copyFile(source, snapshot, timeoutSeconds = 120)
                return parse(snapshot)
            } catch (error: Throwable) {
                snapshot.delete()
                if (firstError == null) firstError = error
            }
        }
        throw firstError ?: IllegalStateException("Official library metadata was not found")
    }

    private fun parse(snapshot: File): Map<String, LocalBookMetadata> {
        val configuration = RealmConfiguration.Builder()
            .directory(snapshot.parentFile!!)
            .name(snapshot.name)
            .schemaVersion(31)
            .build()

        return DynamicRealm.getInstance(configuration).use { realm ->
            val bookSchema = realm.schema.get("Book")
                ?: realm.schema.all.firstOrNull { schema ->
                    schema.hasField("bookId") && schema.hasField("title")
                }
                ?: return@use emptyMap()

            realm.where(bookSchema.className).findAll().mapNotNull { record ->
                fun text(field: String): String? =
                    if (bookSchema.hasField(field) && !record.isNull(field)) record.getString(field) else null
                fun date(field: String): Date? =
                    if (bookSchema.hasField(field) && !record.isNull(field)) record.getDate(field) else null
                fun number(field: String): Long? =
                    if (bookSchema.hasField(field) && !record.isNull(field)) record.getLong(field) else null
                fun bool(field: String): Boolean? =
                    if (bookSchema.hasField(field) && !record.isNull(field)) record.getBoolean(field) else null
                fun firstText(vararg fields: String): String? = fields.firstNotNullOfOrNull { field ->
                    runCatching { text(field) }.getOrNull()
                }
                fun firstIdentifier(vararg fields: String): String? = fields.firstNotNullOfOrNull { field ->
                    if (!bookSchema.hasField(field) || record.isNull(field)) return@firstNotNullOfOrNull null
                    runCatching {
                        when (bookSchema.getFieldType(field)) {
                            RealmFieldType.STRING -> record.getString(field)
                            RealmFieldType.INTEGER -> record.getLong(field).toString()
                            else -> null
                        }
                    }.getOrNull()
                }
                fun firstNumber(vararg fields: String): Double? = fields.firstNotNullOfOrNull { field ->
                    if (!bookSchema.hasField(field) || record.isNull(field)) return@firstNotNullOfOrNull null
                    runCatching { record.getLong(field).toDouble() }.getOrNull()
                        ?: runCatching { record.getDouble(field) }.getOrNull()
                        ?: runCatching { record.getFloat(field).toDouble() }.getOrNull()
                }

                val bookId = text("bookId")?.trim().orEmpty()
                if (bookId.isBlank()) return@mapNotNull null
                bookId to LocalBookMetadata(
                    bookId = bookId,
                    title = text("title").clean(),
                    author = text("author").clean(),
                    format = text("format").clean(),
                    expiresAt = date("expDate"),
                    savedPath = text("savedPath").clean(),
                    pageCount = number("pageCount")?.toInt(),
                    fileSizeBytes = number("fileSizeInBytes"),
                    downloadedAt = date("downloadedDate"),
                    lastOpenedAt = date("lastOpenDate"),
                    isDownloaded = bool("isDownloaded"),
                    invalidatedType = text("invalidatedType").clean(),
                    comicQuality = text("comicQuality").clean(),
                    seriesId = firstIdentifier("seriesId", "series_id", "setId", "set_id").clean(),
                    seriesTitle = firstText("seriesTitle", "series_title", "setTitle", "set_title").clean(),
                    displayOrder = firstNumber("displayOrder")?.toInt()
                )
            }.toMap()
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotBlank)
}
