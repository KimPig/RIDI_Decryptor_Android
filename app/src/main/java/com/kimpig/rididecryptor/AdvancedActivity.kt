package com.kimpig.rididecryptor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.kimpig.rididecryptor.core.ManualPackageImporter
import com.kimpig.rididecryptor.root.RootEnvironmentReader
import com.kimpig.rididecryptor.storage.ExistingFileBehavior
import com.kimpig.rididecryptor.storage.OutputSettings
import com.kimpig.rididecryptor.storage.PrivateCacheManager
import com.kimpig.rididecryptor.storage.PrivateCacheScope
import com.kimpig.rididecryptor.storage.PrivateCacheSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AdvancedActivity : AppCompatActivity() {
    private lateinit var outputValue: TextView
    private lateinit var rootEnvironmentValue: TextView
    private lateinit var rootProgress: ProgressBar
    private lateinit var deviceValue: TextView
    private lateinit var revealButton: Button
    private lateinit var status: TextView
    private lateinit var fileBehaviorDropdown: AutoCompleteTextView
    private lateinit var fileBehaviorLayout: TextInputLayout
    private lateinit var removeImportsButton: Button
    private lateinit var clearTemporaryButton: Button
    private var revealed = false

    private val openOutputTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (Uri.decode(uri.toString()).contains("com.initialcoms.ridi", ignoreCase = true)) {
            status.text = "The official RIDI directory cannot be used as an output folder."
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            OutputSettings.setOutputTree(this, uri)
        }.onSuccess {
            updateOutputDescription()
            status.text = "Output folder changed."
        }.onFailure { status.text = "Could not retain folder access: ${it.message}" }
    }

    private val openPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        status.text = "Copying package into private cache…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { ManualPackageImporter.import(this@AdvancedActivity, uri) } }
                .onSuccess { book ->
                    AppSession.manualBooks.removeAll { it.bookId == book.bookId }
                    AppSession.manualBooks += book
                    status.text = "Imported ${book.bookId}. It will appear in Library when you go back."
                }
                .onFailure { status.text = "Import failed: ${it.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)
        outputValue = findViewById(R.id.outputValue)
        rootEnvironmentValue = findViewById(R.id.rootEnvironmentValue)
        rootProgress = findViewById(R.id.rootProgress)
        deviceValue = findViewById(R.id.deviceValue)
        revealButton = findViewById(R.id.revealButton)
        status = findViewById(R.id.advancedStatus)
        fileBehaviorDropdown = findViewById(R.id.fileBehaviorDropdown)
        fileBehaviorLayout = findViewById(R.id.fileBehaviorLayout)
        removeImportsButton = findViewById(R.id.removeImportsButton)
        clearTemporaryButton = findViewById(R.id.clearTemporaryButton)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.changeOutputButton).setOnClickListener { openOutputTree.launch(OutputSettings.outputTree(this)) }
        findViewById<Button>(R.id.defaultOutputButton).setOnClickListener {
            OutputSettings.setOutputTree(this, null)
            updateOutputDescription()
            status.text = "Output reset to Download/RIDI_Decryptor."
        }
        val fileOptions = listOf("Auto rename", "Overwrite")
        fileBehaviorDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, fileOptions))
        fileBehaviorDropdown.setOnItemClickListener { _, _, position, _ ->
            OutputSettings.setBehavior(this, if (position == 0) ExistingFileBehavior.KEEP_BOTH else ExistingFileBehavior.REPLACE)
            updateFileBehavior()
        }
        revealButton.setOnClickListener {
            revealed = !revealed
            updateDeviceId()
        }
        findViewById<Button>(R.id.realmDebugButton).setOnClickListener {
            startActivity(Intent(this, RealmDebugActivity::class.java))
        }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            openPackage.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        removeImportsButton.setOnClickListener { prepareCleanup(PrivateCacheScope.IMPORTED_PACKAGES) }
        clearTemporaryButton.setOnClickListener { prepareCleanup(PrivateCacheScope.TEMPORARY_FILES) }

        updateOutputDescription()
        updateFileBehavior()
        updateDeviceId()
        loadRootEnvironment()
    }

    private fun updateDeviceId() {
        val value = AppSession.scanResult?.deviceId.orEmpty()
        deviceValue.text = when {
            value.isBlank() -> "Not loaded"
            revealed -> value
            value.length <= 8 -> "••••••••"
            else -> value.take(4) + "••••••••••••" + value.takeLast(4)
        }
        revealButton.text = if (revealed) "Hide" else "Reveal"
        revealButton.isEnabled = value.isNotBlank()
    }

    private fun loadRootEnvironment() {
        rootProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            rootEnvironmentValue.text = runCatching {
                withContext(Dispatchers.IO) { RootEnvironmentReader().readSuVersion() }
            }.getOrElse { "Unavailable" }
            rootProgress.visibility = View.GONE
        }
    }

    private fun updateFileBehavior() {
        when (OutputSettings.behavior(this)) {
            ExistingFileBehavior.KEEP_BOTH -> {
                fileBehaviorDropdown.setText("Auto rename", false)
                fileBehaviorLayout.helperText = "Keeps the existing file and adds (1), (2), …"
            }
            ExistingFileBehavior.REPLACE -> {
                fileBehaviorDropdown.setText("Overwrite", false)
                fileBehaviorLayout.helperText = "Replaces the existing file after validation"
            }
        }
    }

    private fun updateOutputDescription() {
        val tree = OutputSettings.outputTree(this)
        if (tree == null) {
            outputValue.text = "Download/RIDI_Decryptor"
            return
        }
        val name = runCatching {
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        outputValue.text = name?.let { "$it · Custom folder" } ?: "Custom folder"
    }

    private fun prepareCleanup(scope: PrivateCacheScope) {
        setCleanupBusy(true)
        status.text = "Calculating private cache usage…"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { PrivateCacheManager.inspect(this@AdvancedActivity, scope) }
            }.onSuccess { summary ->
                setCleanupBusy(false)
                if (summary.isEmpty) {
                    status.text = when (scope) {
                        PrivateCacheScope.IMPORTED_PACKAGES -> "No imported packages found."
                        PrivateCacheScope.TEMPORARY_FILES -> "No temporary files found."
                    }
                } else showCleanupConfirmation(scope, summary)
            }.onFailure { error ->
                setCleanupBusy(false)
                status.text = "Could not inspect private cache: ${error.message}"
            }
        }
    }

    private fun showCleanupConfirmation(scope: PrivateCacheScope, summary: PrivateCacheSummary) {
        val imported = scope == PrivateCacheScope.IMPORTED_PACKAGES
        val title = if (imported) "Remove imported packages?" else "Clear temporary files?"
        val scopeText = if (imported) {
            "This removes only copies imported into RIDI Decryptor. The files you originally selected are not changed."
        } else {
            "This removes only RIDI Decryptor work files and local metadata snapshots. Imported packages and decrypted outputs are not changed."
        }
        val message = "$scopeText\n\n${summary.files} file(s) · ${formatBytes(summary.bytes)}"
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (imported) "Remove" else "Clear") { _, _ -> performCleanup(scope) }
            .create()
        dialog.setOnShowListener {
            if (imported) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.error_text))
        }
        dialog.show()
    }

    private fun performCleanup(scope: PrivateCacheScope) {
        setCleanupBusy(true)
        status.text = if (scope == PrivateCacheScope.IMPORTED_PACKAGES) {
            "Removing imported package copies…"
        } else "Clearing temporary files…"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { PrivateCacheManager.clear(this@AdvancedActivity, scope) }
            }.onSuccess { removed ->
                if (scope == PrivateCacheScope.IMPORTED_PACKAGES) AppSession.manualBooks.clear()
                status.text = "Cleared ${formatBytes(removed.bytes)} from RIDI Decryptor private cache."
            }.onFailure { error -> status.text = "Cache cleanup failed: ${error.message}" }
            setCleanupBusy(false)
        }
    }

    private fun setCleanupBusy(value: Boolean) {
        removeImportsButton.isEnabled = !value
        clearTemporaryButton.isEnabled = !value
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB")
        var amount = value.toDouble()
        var unit = -1
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return String.format(Locale.US, "%.1f %s", amount, units[unit])
    }
}
