package com.kimpig.rididecryptor.storage

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class ExistingFileBehavior { KEEP_BOTH, SKIP, REPLACE }

object OutputSettings {
    private const val PREFS = "decryptor_settings"
    private const val KEY_OUTPUT_TREE = "output_tree"
    private const val KEY_EXISTING_FILE = "existing_file_behavior"
    private const val KEY_STOP_OFFICIAL_APP = "stop_official_app_before_access"
    private const val KEY_REMOVE_EPUB_MARKERS = "remove_epub_privacy_markers"
    private const val KEY_NORMALIZE_ARCHIVE_TIMESTAMPS = "normalize_archive_timestamps"
    private const val KEY_ARCHIVE_TIMESTAMP = "archive_timestamp"
    const val DEFAULT_ARCHIVE_TIMESTAMP = "2010-07-28 12:48:18"

    fun outputTree(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_OUTPUT_TREE, null)?.let(Uri::parse)

    fun setOutputTree(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (uri == null) remove(KEY_OUTPUT_TREE) else putString(KEY_OUTPUT_TREE, uri.toString())
        }.apply()
    }

    fun behavior(context: Context): ExistingFileBehavior = runCatching {
        ExistingFileBehavior.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EXISTING_FILE, ExistingFileBehavior.KEEP_BOTH.name)!!
        )
    }.getOrDefault(ExistingFileBehavior.KEEP_BOTH)

    fun setBehavior(context: Context, behavior: ExistingFileBehavior) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXISTING_FILE, behavior.name).apply()
    }

    fun stopOfficialAppBeforeAccess(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STOP_OFFICIAL_APP, true)

    fun setStopOfficialAppBeforeAccess(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STOP_OFFICIAL_APP, enabled).apply()
    }

    fun removeEpubPrivacyMarkers(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMOVE_EPUB_MARKERS, true)

    fun setRemoveEpubPrivacyMarkers(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REMOVE_EPUB_MARKERS, enabled).apply()
    }

    fun normalizeArchiveTimestamps(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NORMALIZE_ARCHIVE_TIMESTAMPS, false)

    fun setNormalizeArchiveTimestamps(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NORMALIZE_ARCHIVE_TIMESTAMPS, enabled).apply()
    }

    fun archiveTimestampText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ARCHIVE_TIMESTAMP, DEFAULT_ARCHIVE_TIMESTAMP)
            ?: DEFAULT_ARCHIVE_TIMESTAMP

    fun setArchiveTimestampText(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ARCHIVE_TIMESTAMP, value).apply()
    }

    fun archiveTimestampMillis(context: Context): Long =
        parseArchiveTimestamp(archiveTimestampText(context))
            ?: requireNotNull(parseArchiveTimestamp(DEFAULT_ARCHIVE_TIMESTAMP))

    fun parseArchiveTimestamp(value: String): Long? = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }
            .parse(value.trim()) ?: return@runCatching null
        Calendar.getInstance().run {
            time = parsed
            if (get(Calendar.YEAR) !in 1980..2107) return@runCatching null
            set(Calendar.MILLISECOND, 0)
            set(Calendar.SECOND, get(Calendar.SECOND) - get(Calendar.SECOND) % 2)
            timeInMillis
        }
    }.getOrNull()
}
