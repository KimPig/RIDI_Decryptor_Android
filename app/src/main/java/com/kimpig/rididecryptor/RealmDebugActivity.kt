package com.kimpig.rididecryptor

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.kimpig.rididecryptor.root.RealmDebugPage
import com.kimpig.rididecryptor.root.RealmDebugReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RealmDebugActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var spinner: Spinner
    private lateinit var search: TextInputEditText
    private lateinit var schema: TextView
    private lateinit var records: LinearLayout
    private lateinit var previous: Button
    private lateinit var next: Button
    private var page: RealmDebugPage? = null
    private var selectedClass: String? = null
    private var offset = 0
    private var spinnerReady = false
    private val realmReader = RealmDebugReader()
    private var realmSnapshot: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realm_debug)
        status = findViewById(R.id.debugStatus)
        progress = findViewById(R.id.debugProgress)
        spinner = findViewById(R.id.classSpinner)
        search = findViewById(R.id.searchInput)
        schema = findViewById(R.id.schemaText)
        records = findViewById(R.id.recordsContainer)
        previous = findViewById(R.id.previousButton)
        next = findViewById(R.id.nextButton)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.loadButton).setOnClickListener { offset = 0; load(refreshSnapshot = true) }
        previous.setOnClickListener { offset = (offset - PAGE_SIZE).coerceAtLeast(0); load() }
        next.setOnClickListener { offset += PAGE_SIZE; load() }
        previous.isEnabled = false
        next.isEnabled = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                val name = page?.classes?.getOrNull(position)?.name ?: return
                if (name != selectedClass) { selectedClass = name; offset = 0; load() }
            }
        }
    }

    override fun onDestroy() {
        realmReader.deleteSnapshot(realmSnapshot)
        realmSnapshot = null
        super.onDestroy()
    }

    private fun load(refreshSnapshot: Boolean = false) {
        val roots = AppSession.scanResult?.dataRoots.orEmpty()
        if (roots.isEmpty()) {
            status.text = "Return to Library and run Scan Library first."
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (refreshSnapshot) {
                        realmReader.deleteSnapshot(realmSnapshot)
                        realmSnapshot = null
                    }
                    val snapshot = realmSnapshot ?: realmReader.createSnapshot(this@RealmDebugActivity, roots).also {
                        realmSnapshot = it
                    }
                    realmReader.readSnapshot(
                        this@RealmDebugActivity,
                        snapshot,
                        selectedClass,
                        offset,
                        PAGE_SIZE,
                        search.text?.toString().orEmpty()
                    )
                }
            }.onSuccess(::render).onFailure { error -> status.text = "Realm inspection failed: ${error.message}" }
            setBusy(false)
        }
    }

    private fun render(value: RealmDebugPage) {
        page = value
        selectedClass = value.selectedClass
        offset = value.offset
        spinnerReady = false
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            value.classes.map { "${it.name} · ${it.recordCount}" }
        )
        val selectedIndex = value.classes.indexOfFirst { it.name == value.selectedClass }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex, false)
        spinnerReady = true
        val summary = value.classes.getOrNull(selectedIndex)
        schema.text = summary?.let { item ->
            "${item.name} · ${item.recordCount} records\n${item.fields.joinToString("\n")}"
        }.orEmpty()
        status.text = if (value.total == 0) "No records" else "Records ${value.offset + 1}-${value.offset + value.records.size} of ${value.total}"
        previous.isEnabled = value.offset > 0
        next.isEnabled = value.offset + value.records.size < value.total
        records.removeAllViews()
        value.records.forEachIndexed { index, record -> addRecord(value.offset + index + 1, record) }
    }

    private fun addRecord(number: Int, values: Map<String, String>) {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            strokeColor = getColor(R.color.border)
            setCardBackgroundColor(getColor(R.color.surface))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        content.addView(TextView(this).apply {
            text = "Record $number"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.brand_blue_dark))
        })
        values.forEach { (field, value) ->
            content.addView(TextView(this).apply {
                text = field
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text_muted))
                setPadding(0, dp(10), 0, 0)
            })
            content.addView(TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setTextIsSelectable(true)
            })
        }
        card.addView(content)
        records.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        previous.isEnabled = !value && offset > 0
        next.isEnabled = !value && page?.let { it.offset + it.records.size < it.total } == true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val PAGE_SIZE = 50 }
}
