package com.kimpig.rididecryptor

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.kimpig.rididecryptor.root.RealmDebugReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookRealmRecordActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val book = AppSession.detailBook
        if (book == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_book_realm_record)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "Realm record"
            subtitle = book.displayTitle
            setNavigationOnClickListener { finish() }
        }
        status = findViewById(R.id.realmRecordStatus)
        progress = findViewById(R.id.realmRecordProgress)
        container = findViewById(R.id.realmRecordContainer)
        loadRecord(book.bookId)
    }

    private fun loadRecord(bookId: String) {
        val roots = AppSession.scanResult?.dataRoots.orEmpty()
        if (roots.isEmpty()) {
            status.text = "Run Scan Library first."
            return
        }
        progress.visibility = View.VISIBLE
        status.text = "Loading a private read-only snapshot…"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    RealmDebugReader().read(
                        this@BookRealmRecordActivity,
                        roots,
                        selectedClass = "Book",
                        limit = 100,
                        query = bookId
                    ).records.firstOrNull { it["bookId"] == bookId }
                }
            }.onSuccess { record ->
                if (record == null) {
                    status.text = "Realm record not found."
                } else {
                    status.text = "${record.size} fields"
                    renderRecord(record)
                }
            }.onFailure { error ->
                status.text = "Realm inspection failed: ${error.message ?: error.javaClass.simpleName}"
            }
            progress.visibility = View.GONE
        }
    }

    private fun renderRecord(record: Map<String, String>) {
        container.removeAllViews()
        record.forEach { (field, value) ->
            val card = MaterialCardView(this).apply {
                radius = dp(10).toFloat()
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.border)
                setCardBackgroundColor(getColor(R.color.surface))
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(TextView(this@BookRealmRecordActivity).apply {
                    text = field
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.text_muted))
                })
                addView(TextView(this@BookRealmRecordActivity).apply {
                    text = value
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    setTextIsSelectable(true)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            card.addView(content)
            container.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
