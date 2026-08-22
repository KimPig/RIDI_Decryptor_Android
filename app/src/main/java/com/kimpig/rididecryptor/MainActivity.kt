package com.kimpig.rididecryptor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kimpig.rididecryptor.core.BookCandidate
import com.kimpig.rididecryptor.core.CryptoEngine
import com.kimpig.rididecryptor.core.PreparedBook
import com.kimpig.rididecryptor.core.ProgressUpdate
import com.kimpig.rididecryptor.core.SourceKind
import com.kimpig.rididecryptor.root.RidiAppNotInstalledException
import com.kimpig.rididecryptor.root.RidiDeviceInfoMissingException
import com.kimpig.rididecryptor.root.RidiRootSource
import com.kimpig.rididecryptor.root.RootScanResult
import com.kimpig.rididecryptor.storage.OutputSettings
import com.kimpig.rididecryptor.storage.OutputStore
import com.kimpig.rididecryptor.storage.ScanSessionStore
import com.kimpig.rididecryptor.ui.BookAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class DecryptBatchResult(
    val saved: List<String>,
    val skipped: List<String>,
    val failed: List<String>,
    val warnings: List<String>
)

class MainActivity : AppCompatActivity() {
    private lateinit var rootGate: View
    private lateinit var mainContent: View
    private lateinit var rootGateTitle: TextView
    private lateinit var rootGateMessage: TextView
    private lateinit var rootGateProgress: ProgressBar
    private lateinit var grantRootButton: Button
    private lateinit var bookList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var decryptButton: Button
    private lateinit var detailsButton: Button
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var helpButton: Button
    private lateinit var advancedButton: Button
    private lateinit var versionText: TextView

    private val rootSource = RidiRootSource()
    private val books = mutableListOf<BookCandidate>()
    private val selectedBooks = linkedMapOf<String, BookCandidate>()
    private lateinit var adapter: BookAdapter
    private var deviceId = ""
    private var busy = false
    private var officialAccessPending = false
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { adapter.exitMultiSelect() }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status(if (granted) "Storage permission granted. Tap Decrypt again." else "Storage permission is required on Android 7-9.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSession.initialize(applicationContext)
        setContentView(R.layout.activity_main)
        bindViews()
        adapter = BookAdapter(
            onSelectionChanged = { candidate, selected ->
                if (selected) selectedBooks[candidate.bookId] = candidate else selectedBooks.remove(candidate.bookId)
                updateSelectionControls()
            },
            onSelectionModeChanged = {
                selectionBack.isEnabled = it
                updateSelectionControls()
            }
        )
        onBackPressedDispatcher.addCallback(this, selectionBack)
        bookList.layoutManager = LinearLayoutManager(this)
        bookList.adapter = adapter

        scanButton.setOnClickListener { requestLibraryScan() }
        grantRootButton.setOnClickListener { requestRootAccess() }
        helpButton.setOnClickListener { showHelp() }
        advancedButton.setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java)) }
        selectAllButton.setOnClickListener {
            if (adapter.allSelected()) adapter.deselectAll() else adapter.selectAll()
            updateSelectionControls()
        }
        cancelSelectionButton.setOnClickListener { adapter.exitMultiSelect() }
        decryptButton.setOnClickListener { requestDecryptSelected() }
        detailsButton.setOnClickListener {
            val book = selectedBooks.values.singleOrNull() ?: return@setOnClickListener
            AppSession.detailBook = book
            startActivity(Intent(this, BookDetailsActivity::class.java))
        }
        updateSelectionControls()
        versionText.text = "v${displayVersion()}"
        checkRootAtLaunch()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && mainContent.visibility == View.VISIBLE && !busy) {
            mergeManualBooks()
            refreshOutputStatus()
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) ScanSessionStore.clear(applicationContext)
        super.onDestroy()
    }

    private fun bindViews() {
        rootGate = findViewById(R.id.rootGate)
        mainContent = findViewById(R.id.mainContent)
        rootGateTitle = findViewById(R.id.rootGateTitle)
        rootGateMessage = findViewById(R.id.rootGateMessage)
        rootGateProgress = findViewById(R.id.rootGateProgress)
        grantRootButton = findViewById(R.id.grantRootButton)
        bookList = findViewById(R.id.bookList)
        emptyView = findViewById(R.id.emptyView)
        decryptButton = findViewById(R.id.decryptButton)
        detailsButton = findViewById(R.id.detailsButton)
        selectAllButton = findViewById(R.id.selectAllButton)
        cancelSelectionButton = findViewById(R.id.cancelSelectionButton)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        helpButton = findViewById(R.id.helpButton)
        advancedButton = findViewById(R.id.settingsButton)
        versionText = findViewById(R.id.versionText)
    }

    private fun checkRootAtLaunch() {
        showRootChecking()
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) {
                if (rootSource.knownRootStatus() == false) false else rootSource.hasRoot()
            }
            if (granted) enterMain() else showRootRequired()
        }
    }

    private fun showHelp() {
        val content = layoutInflater.inflate(R.layout.dialog_how_to_use, null, false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setView(content)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun requestRootAccess() {
        showRootChecking()
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) { rootSource.requestRootAccess() }
            if (granted) enterMain() else showRootRequired()
        }
    }

    private fun showRootChecking() {
        mainContent.visibility = View.GONE
        rootGate.visibility = View.VISIBLE
        rootGateTitle.text = getString(R.string.root_checking_title)
        rootGateMessage.text = getString(R.string.root_checking_message)
        rootGateProgress.visibility = View.VISIBLE
        grantRootButton.visibility = View.GONE
    }

    private fun showRootRequired() {
        mainContent.visibility = View.GONE
        rootGate.visibility = View.VISIBLE
        rootGateTitle.text = getString(R.string.root_required_title)
        rootGateMessage.text = getString(R.string.root_required_message)
        rootGateProgress.visibility = View.GONE
        grantRootButton.visibility = View.VISIBLE
        clearLibrary()
    }

    private fun enterMain() {
        rootGate.visibility = View.GONE
        mainContent.visibility = View.VISIBLE
        requestLibraryScan()
    }

    private fun requestLibraryScan() {
        withOfficialAppAccess("scan the local library", ::scanRootLibrary)
    }

    private fun requestDecryptSelected() {
        if (selectedBooks.values.none { it.sourceKind == SourceKind.ROOT }) {
            decryptSelected()
            return
        }
        withOfficialAppAccess("copy the selected official files", ::decryptSelected)
    }

    private fun withOfficialAppAccess(operation: String, action: () -> Unit) {
        if (busy || officialAccessPending) return
        if (!OutputSettings.stopOfficialAppBeforeAccess(this)) {
            action()
            return
        }
        officialAccessPending = true
        setBusy(true)
        status("Checking the official RIDI app before attempting to $operation…")
        lifecycleScope.launch {
            val running = runCatching { withContext(Dispatchers.IO) { rootSource.isOfficialAppRunning() } }
            setBusy(false)
            running.onFailure { error ->
                officialAccessPending = false
                status("Could not verify the official RIDI app state: ${error.message ?: error.javaClass.simpleName}")
            }.onSuccess { isRunning ->
                if (!isRunning) {
                    officialAccessPending = false
                    action()
                    return@onSuccess
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Close the official RIDI app?")
                    .setMessage(
                        "The official app is running. Closing it first prevents reading a changing database or an incomplete download. " +
                            "Any reading or download currently in progress will stop."
                    )
                    .setNegativeButton("Cancel") { _, _ ->
                        officialAccessPending = false
                        status("Operation cancelled.")
                    }
                    .setPositiveButton("Close and continue") { _, _ -> stopOfficialAppThen(action) }
                    .setOnCancelListener {
                        officialAccessPending = false
                        status("Operation cancelled.")
                    }
                    .show()
            }
        }
    }

    private fun stopOfficialAppThen(action: () -> Unit) {
        setBusy(true)
        status("Closing the official RIDI app…")
        lifecycleScope.launch {
            val stopped = runCatching { withContext(Dispatchers.IO) { rootSource.stopOfficialAppAndWait() } }
            setBusy(false)
            stopped.onFailure { error ->
                officialAccessPending = false
                status("Could not close the official RIDI app: ${error.message ?: error.javaClass.simpleName}")
            }.onSuccess { confirmed ->
                officialAccessPending = false
                if (confirmed) action() else status("The official RIDI app is still running. No official files were read.")
            }
        }
    }

    private fun scanRootLibrary() {
        setBusy(true)
        status("Reading local library metadata…")
        lifecycleScope.launch {
            try {
                val (result, outputs) = withContext(Dispatchers.IO) {
                    val scanned = rootSource.scan(applicationContext)
                    val candidates = scanned.books + AppSession.manualBooks
                    val found = discoverOutputs(candidates)
                    scanned to found
                }
                displayScanResult(result, outputs)
            } catch (_: RidiAppNotInstalledException) {
                showLibraryState(getString(R.string.ridi_not_installed))
            } catch (_: RidiDeviceInfoMissingException) {
                showLibraryState(getString(R.string.ridi_not_initialized))
            } catch (error: Throwable) {
                if (error.message?.contains("Root", true) == true) showRootRequired()
                else showLibraryState("Local scan failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun displayScanResult(
        result: RootScanResult,
        outputs: Map<String, List<com.kimpig.rididecryptor.core.DecryptedOutput>>
    ) {
        AppSession.scanResult = result
        deviceId = result.deviceId
        books.clear()
        books.addAll(result.books)
        mergeManualBooks(refresh = false)
        applyOutputStatus(outputs)
        selectedBooks.clear()
        adapter.clearSelection(false)
        updateSelectionControls()
        emptyView.text = if (books.isEmpty()) getString(R.string.empty_library) else getString(R.string.empty_books)
        refreshBookList()
        status(
            when {
                books.isEmpty() -> getString(R.string.empty_library)
                result.metadataIssue != null -> "Found ${books.size} local book(s), but some metadata was unavailable."
                else -> "Found ${books.size} local book(s); loaded ${result.metadataCount} title(s)."
            }
        )
    }

    private fun mergeManualBooks(refresh: Boolean = true) {
        val activeManualIds = AppSession.manualBooks.map { it.bookId }.toSet()
        books.removeAll { it.sourceKind == SourceKind.MANUAL && it.bookId !in activeManualIds }
        AppSession.manualBooks.forEach { manual ->
            if (books.none { it.bookId == manual.bookId && it.sourceKind == SourceKind.MANUAL }) books += manual
        }
        if (refresh && ::adapter.isInitialized) refreshBookList()
    }

    private fun showLibraryState(message: String) {
        clearLibrary()
        emptyView.text = message
        refreshBookList()
        status(message)
    }

    private fun clearLibrary() {
        deviceId = ""
        AppSession.scanResult = null
        books.clear()
        selectedBooks.clear()
        if (::adapter.isInitialized) adapter.clearSelection(false)
        if (::decryptButton.isInitialized) updateSelectionControls()
        if (::bookList.isInitialized) refreshBookList()
    }

    private fun decryptSelected() {
        val candidates = selectedBooks.values.toList()
        if (candidates.isEmpty()) return
        val activeDeviceId = deviceId.trim()
        if (activeDeviceId.length < 18) {
            status("A valid device_id was not loaded from the official app.")
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        runBusy("Preparing private working copies…") {
            val result = withContext(Dispatchers.IO) {
                val saved = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val failed = mutableListOf<String>()
                val warnings = mutableListOf<String>()
                val outputTree = OutputSettings.outputTree(this@MainActivity)
                candidates.forEachIndexed { index, candidate ->
                    val work = File(cacheDir, "work/${System.currentTimeMillis()}-$index")
                    val sourceDir = File(work, "source")
                    val outputDir = File(work, "output")
                    try {
                        statusOnMain("${index + 1}/${candidates.size} · Copying ${candidate.displayTitle}…")
                        val prepared = when (candidate.sourceKind) {
                            SourceKind.ROOT -> rootSource.materialize(candidate, sourceDir) { done, total ->
                                progressOnMain(ProgressUpdate("Copying source files", done.toLong(), total.toLong()))
                            }
                            SourceKind.MANUAL -> {
                                sourceDir.mkdirs()
                                val source = File(candidate.files.single())
                                source.copyTo(File(sourceDir, source.name), overwrite = true)
                                PreparedBook(candidate.bookId, sourceDir, candidate.displayTitle, candidate.comicQuality)
                            }
                        }
                        statusOnMain("${index + 1}/${candidates.size} · Decrypting and validating ${candidate.displayTitle}…")
                        val normalizedTimestamp = if (OutputSettings.normalizeArchiveTimestamps(this@MainActivity)) {
                            OutputSettings.archiveTimestampMillis(this@MainActivity)
                        } else null
                        val result = CryptoEngine().decrypt(
                            prepared,
                            activeDeviceId,
                            outputDir,
                            ::progressOnMain,
                            OutputSettings.removeEpubPrivacyMarkers(this@MainActivity),
                            normalizedTimestamp
                        )
                        val saveResult = OutputStore(this@MainActivity).save(result, outputTree, ::progressOnMain)
                        if (saveResult.skipped) skipped += candidate.displayTitle else saved += saveResult.location
                        result.warnings.forEach { warning ->
                            warnings += "${candidate.displayTitle}: $warning"
                        }
                    } catch (error: Throwable) {
                        failed += "${candidate.displayTitle}: ${error.message ?: error.javaClass.simpleName}"
                    } finally {
                        work.deleteRecursively()
                    }
                }
                DecryptBatchResult(saved, skipped, failed, warnings)
            }
            val saved = result.saved
            val skipped = result.skipped
            val failed = result.failed
            val warnings = result.warnings
            val completionSummary = buildString {
                append(if (warnings.isEmpty()) "Completed" else "Completed with warning")
                append(" · ${saved.size} saved")
                if (skipped.isNotEmpty()) append(" · ${skipped.size} skipped")
                if (warnings.isNotEmpty()) append(" · ${warnings.size} warning(s)")
                if (failed.isNotEmpty()) append(" · ${failed.size} failed")
            }
            val details = warnings + failed
            status(completionSummary + if (details.isEmpty()) "" else "\n${details.joinToString("\n")}")
            refreshOutputStatusNow()
            Toast.makeText(this@MainActivity, completionSummary, Toast.LENGTH_LONG).show()
        }
    }

    private fun runBusy(message: String, block: suspend () -> Unit) {
        setBusy(true)
        status(message)
        lifecycleScope.launch {
            try { block() }
            catch (error: Throwable) {
                if (error.message?.contains("Root", true) == true) showRootRequired()
                else status("Error: ${error.message ?: error.javaClass.simpleName}")
            } finally { setBusy(false) }
        }
    }

    private suspend fun statusOnMain(message: String) = withContext(Dispatchers.Main) { status(message) }

    private fun progressOnMain(update: ProgressUpdate) = runOnUiThread {
        val percent = update.percent
        progressBar.isIndeterminate = percent == null
        if (percent != null) progressBar.progress = percent
        progressText.text = if (percent == null) update.stage else "${update.stage} · $percent%"
        progressText.visibility = View.VISIBLE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = value
        progressText.visibility = if (value) View.VISIBLE else View.GONE
        if (value) progressText.text = "Working…"
        scanButton.isEnabled = !value
        scanButton.text = if (value) "Scanning…" else getString(R.string.scan_library)
        helpButton.isEnabled = !value
        advancedButton.isEnabled = !value
        selectAllButton.isEnabled = !value && books.isNotEmpty() && adapter.multiSelectMode
        cancelSelectionButton.isEnabled = !value && adapter.multiSelectMode
        decryptButton.isEnabled = !value && selectedBooks.isNotEmpty()
        detailsButton.isEnabled = !value && selectedBooks.size == 1
    }

    private fun refreshBookList() {
        val hasBooks = books.isNotEmpty()
        emptyView.visibility = if (hasBooks) View.GONE else View.VISIBLE
        bookList.visibility = if (hasBooks) View.VISIBLE else View.GONE
        selectAllButton.isEnabled = hasBooks && !busy && adapter.multiSelectMode
        adapter.submitList(books.toList())
        updateSelectionControls()
    }

    private fun updateSelectionControls() {
        val count = selectedBooks.size
        selectAllButton.isEnabled = !busy && books.isNotEmpty() && adapter.multiSelectMode
        cancelSelectionButton.isEnabled = !busy && adapter.multiSelectMode
        decryptButton.isEnabled = !busy && count > 0
        detailsButton.isEnabled = !busy && count == 1
        detailsButton.visibility = if (adapter.multiSelectMode) View.GONE else View.VISIBLE
        decryptButton.text = when {
            count == 0 -> getString(R.string.select_book_to_decrypt)
            count == 1 -> getString(R.string.decrypt_selected)
            else -> "Decrypt $count books"
        }
        selectAllButton.text = if (adapter.allSelected()) "Deselect all" else "Select all"
        selectAllButton.visibility = if (adapter.multiSelectMode) View.VISIBLE else View.GONE
        cancelSelectionButton.visibility = if (adapter.multiSelectMode) View.VISIBLE else View.GONE
    }

    private fun refreshOutputStatus() {
        if (books.isEmpty()) return
        lifecycleScope.launch {
            refreshOutputStatusNow()
        }
    }

    private suspend fun refreshOutputStatusNow() {
        if (books.isEmpty()) return
        val snapshot = books.toList()
        val found = withContext(Dispatchers.IO) { discoverOutputs(snapshot) }
        applyOutputStatus(found)
        refreshBookList()
    }

    private fun discoverOutputs(candidates: List<BookCandidate>) = runCatching {
        OutputStore(this).discover(
            candidates.map { it.bookId }.toSet(),
            OutputSettings.outputTree(this)
        )
    }.getOrDefault(emptyMap())

    private fun applyOutputStatus(found: Map<String, List<com.kimpig.rididecryptor.core.DecryptedOutput>>) {
        books.indices.forEach { index ->
            val current = books[index]
            val updated = current.copy(decryptedOutputs = found[current.bookId].orEmpty())
            books[index] = updated
            if (current.bookId in selectedBooks) selectedBooks[current.bookId] = updated
        }
    }

    private fun status(message: String) { statusText.text = message }

    private fun displayVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().substringBefore('-')
    }.getOrDefault("Unknown")
}
