package com.kimpig.rididecryptor.root

import android.content.Context
import com.kimpig.rididecryptor.storage.ScanSessionStore
import io.realm.Case
import io.realm.DynamicRealm
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmFieldType
import java.io.File
import java.text.DateFormat

data class RealmClassSummary(val name: String, val fields: List<String>, val recordCount: Long)

data class RealmDebugPage(
    val classes: List<RealmClassSummary>,
    val selectedClass: String?,
    val offset: Int,
    val total: Int,
    val records: List<Map<String, String>>
)

class RealmDebugReader {
    fun sessionSnapshot(context: Context): File = ScanSessionStore.activeRealm(context)
        ?: throw IllegalStateException("No Realm scan snapshot is loaded. Run Scan Library first")

    fun read(
        context: Context,
        selectedClass: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        query: String = ""
    ): RealmDebugPage {
        return readSnapshot(context, sessionSnapshot(context), selectedClass, offset, limit, query)
    }

    fun readSnapshot(
        context: Context,
        snapshot: File,
        selectedClass: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        query: String = ""
    ): RealmDebugPage {
        require(snapshot.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)) {
            "Realm snapshot must be inside private cache"
        }
        Realm.init(context.applicationContext)
        return parse(snapshot, selectedClass, offset, limit, query)
    }

    private fun parse(snapshot: File, requestedClass: String?, offset: Int, limit: Int, query: String): RealmDebugPage {
        val configuration = RealmConfiguration.Builder()
            .directory(snapshot.parentFile!!)
            .name(snapshot.name)
            .schemaVersion(31)
            .build()
        return DynamicRealm.getInstance(configuration).use { realm ->
            val classes = realm.schema.all.sortedBy { it.className }.map { schema ->
                RealmClassSummary(
                    schema.className,
                    schema.fieldNames.sorted().map { field -> "$field · ${schema.getFieldType(field).name}" },
                    realm.where(schema.className).count()
                )
            }
            val className = requestedClass?.takeIf { name -> classes.any { it.name == name } }
                ?: classes.firstOrNull()?.name
                ?: return@use RealmDebugPage(emptyList(), null, 0, 0, emptyList())
            val schema = realm.schema.get(className)!!
            val baseQuery = realm.where(className)
            val results = if (query.isNotBlank() && schema.hasField("bookId") && schema.getFieldType("bookId") == RealmFieldType.STRING) {
                baseQuery.contains("bookId", query, Case.INSENSITIVE).findAll()
            } else {
                baseQuery.findAll()
            }
            val safeOffset = offset.coerceIn(0, (results.size - 1).coerceAtLeast(0))
            val records = results.drop(safeOffset).take(limit.coerceIn(1, 100)).map { record ->
                schema.fieldNames.sorted().associateWith { field -> value(record, field, schema.getFieldType(field)) }
            }
            RealmDebugPage(classes, className, safeOffset, results.size, records)
        }
    }

    private fun value(record: io.realm.DynamicRealmObject, field: String, type: RealmFieldType): String {
        if (record.isNull(field)) return "null"
        return runCatching {
            when (type) {
                RealmFieldType.STRING -> record.getString(field).orEmpty()
                RealmFieldType.INTEGER -> record.getLong(field).toString()
                RealmFieldType.BOOLEAN -> record.getBoolean(field).toString()
                RealmFieldType.FLOAT -> record.getFloat(field).toString()
                RealmFieldType.DOUBLE -> record.getDouble(field).toString()
                RealmFieldType.DATE -> DateFormat.getDateTimeInstance().format(record.getDate(field)!!)
                RealmFieldType.BINARY -> "<${record.getBlob(field)?.size ?: 0} bytes>"
                RealmFieldType.OBJECT -> "<linked object>"
                RealmFieldType.LIST -> "<${record.getList(field).size} linked objects>"
                else -> "<$type>"
            }
        }.getOrElse { "<unavailable: ${it.javaClass.simpleName}>" }
    }
}
