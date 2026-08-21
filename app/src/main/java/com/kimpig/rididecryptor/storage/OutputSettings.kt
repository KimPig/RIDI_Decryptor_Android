package com.kimpig.rididecryptor.storage

import android.content.Context
import android.net.Uri

enum class ExistingFileBehavior { KEEP_BOTH, SKIP, REPLACE }

object OutputSettings {
    private const val PREFS = "decryptor_settings"
    private const val KEY_OUTPUT_TREE = "output_tree"
    private const val KEY_EXISTING_FILE = "existing_file_behavior"
    private const val KEY_STOP_OFFICIAL_APP = "stop_official_app_before_access"

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
}
