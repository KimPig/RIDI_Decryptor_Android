package com.kimpig.rididecryptor

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.kimpig.rididecryptor.core.BookCandidate
import com.kimpig.rididecryptor.core.LibraryGrouping
import com.kimpig.rididecryptor.core.SourceKind
import java.text.DateFormat
import java.util.Locale

class BookDetailsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val book = AppSession.detailBook
        if (book == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_book_details)
        container = findViewById(R.id.detailsContainer)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = book.displayTitle
            setNavigationOnClickListener { finish() }
        }
        render(book)
    }

    private fun render(book: BookCandidate) {
        section("Publication") {
            row("Title", book.displayTitle)
            row("Author", book.author ?: "Unavailable")
            row("Book ID", book.bookId)
            row("Format", book.displayFormat)
            row("Series ID", book.seriesId ?: "Unavailable")
            row("Series title", LibraryGrouping.seriesTitleFor(book) ?: "Unavailable")
            row("Volume", LibraryGrouping.volumeLabel(book) ?: "Unavailable")
            if (book.isComic) row("Quality", qualityLabel(book.comicQuality))
        }
        section("Local source") {
            row("Storage", book.storageState)
            row("Package state", when {
                book.sourceKind == SourceKind.MANUAL -> "Imported into private cache"
                book.rawPackage != null -> "Raw package available"
                else -> "Opened by official reader"
            })
            row("Reader extraction", if (book.files.any { it.contains("/extracted/") }) "Available" else "Not present")
            row("Downloaded size", book.fileSizeBytes?.let(::formatBytes) ?: "Unavailable")
            row("Downloaded", book.downloadedAt?.let(::formatDate) ?: "Unavailable")
            row("Last opened", book.lastOpenedAt?.let(::formatDate) ?: "Unavailable")
        }
        section("Official library status") {
            row("Current account", AppSession.scanResult?.currentAccountId ?: "Signed out or unavailable")
            row("Account access", book.officialAccessState)
            row("Realm invalidatedType", book.invalidatedType ?: "None")
        }
        if (book.isComic) section("Page information") {
            row("Displayed pages", book.displayedComicPages?.toString() ?: "Unavailable")
            row("Realm pageCount", book.pageCount?.toString() ?: "Unavailable")
            row("IDX cover items", book.comicIndexCoverCount?.toString() ?: "Unavailable")
            row("IDX content items", book.comicIndexContentCount?.toString() ?: "Unavailable")
            row("Fallback total", book.localComicPages?.toString() ?: "Unavailable")
        }
        section("Decrypted output") {
            if (book.decryptedOutputs.isEmpty()) row("Status", "No verified output found")
            else book.decryptedOutputs.forEachIndexed { index, output ->
                row("File ${index + 1}", output.displayName)
                row("Size", formatBytes(output.sizeBytes))
                row("Location", output.displayLocation)
                output.sha256?.let { row("SHA-256", it) }
            }
        }
        addRealmAdvancedButton(book)
    }

    private fun addRealmAdvancedButton(book: BookCandidate) {
        val button = MaterialButton(this).apply {
            text = "Advanced · View Realm record"
            isAllCaps = false
            setOnClickListener {
                val roots = AppSession.scanResult?.dataRoots.orEmpty()
                if (roots.isEmpty()) {
                    text = "Run Scan Library first"
                    return@setOnClickListener
                }
                AppSession.detailBook = book
                startActivity(Intent(this@BookDetailsActivity, BookRealmRecordActivity::class.java))
            }
        }
        container.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(20)
            bottomMargin = dp(20)
        })
    }

    private fun section(title: String, content: SectionBuilder.() -> Unit) {
        val heading = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        container.addView(heading)
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            strokeColor = getColor(R.color.border)
            setCardBackgroundColor(getColor(R.color.surface))
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(4)) }
        SectionBuilder(rows).content()
        card.addView(rows)
        container.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private inner class SectionBuilder(private val target: LinearLayout) {
        fun row(label: String, value: String) {
            target.addView(LinearLayout(this@BookDetailsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(TextView(this@BookDetailsActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(getColor(R.color.text_muted))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@BookDetailsActivity).apply {
                    text = value
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    setTextIsSelectable(true)
                })
            })
        }
    }

    private fun qualityLabel(value: String?): String = when (value?.lowercase()) {
        "original" -> "Original quality"
        "recommended" -> "Standard quality"
        else -> "Unavailable"
    }

    private fun formatDate(value: java.util.Date): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(value)

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB")
        var amount = value.toDouble()
        var unit = -1
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
