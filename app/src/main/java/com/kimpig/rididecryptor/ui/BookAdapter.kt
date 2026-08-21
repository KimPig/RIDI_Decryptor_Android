package com.kimpig.rididecryptor.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.kimpig.rididecryptor.R
import com.kimpig.rididecryptor.core.BookCandidate
import com.kimpig.rididecryptor.core.LibraryGroup
import com.kimpig.rididecryptor.core.LibraryGrouping
import java.text.DateFormat
import java.util.Date

class BookAdapter(
    private val onSelectionChanged: (BookCandidate, Boolean) -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit = {}
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        val stableKey: String
        data class Series(val group: LibraryGroup) : Row { override val stableKey = "series:${group.key}" }
        data class Book(val book: BookCandidate, val parentSeries: String? = null) : Row {
            override val stableKey = "book:${book.bookId}"
        }
    }

    private val books = mutableListOf<BookCandidate>()
    private val rows = mutableListOf<Row>()
    private val selectedIds = linkedSetOf<String>()
    private val expandedGroups = linkedSetOf<String>()
    var multiSelectMode: Boolean = false
        private set

    init { setHasStableIds(true) }

    fun submitList(value: List<BookCandidate>) {
        books.clear()
        books.addAll(value)
        selectedIds.retainAll(books.map { it.bookId }.toSet())
        rebuildRows()
    }

    fun clearSelection(notify: Boolean = true) {
        val changed = books.filter { it.bookId in selectedIds }
        selectedIds.clear()
        if (notify) changed.forEach { onSelectionChanged(it, false) }
        setMultiSelectMode(false)
        notifyDataSetChanged()
    }

    fun selectAll() {
        setMultiSelectMode(true)
        books.filter { selectedIds.add(it.bookId) }.forEach { onSelectionChanged(it, true) }
        notifyDataSetChanged()
    }

    fun deselectAll() {
        val changed = books.filter { it.bookId in selectedIds }
        selectedIds.clear()
        changed.forEach { onSelectionChanged(it, false) }
        notifyDataSetChanged()
    }

    fun selectOnly(bookId: String) {
        setMultiSelectMode(false)
        if (selectedIds.size == 1 && bookId in selectedIds) {
            books.firstOrNull { it.bookId == bookId }?.let { onSelectionChanged(it, false) }
            selectedIds.clear()
            notifyDataSetChanged()
            return
        }
        books.filter { it.bookId in selectedIds && it.bookId != bookId }.forEach {
            selectedIds.remove(it.bookId)
            onSelectionChanged(it, false)
        }
        books.firstOrNull { it.bookId == bookId }?.let { book ->
            if (selectedIds.add(bookId)) onSelectionChanged(book, true)
        }
        notifyDataSetChanged()
    }

    fun exitMultiSelect() = clearSelection()

    fun allSelected(): Boolean = books.isNotEmpty() && selectedIds.size == books.size

    override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Series) TYPE_SERIES else TYPE_BOOK
    override fun getItemId(position: Int): Long = rows[position].stableKey.hashCode().toLong()
    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_SERIES) {
            SeriesHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_series, parent, false))
        } else {
            BookHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Book -> {
                val selected = row.book.bookId in selectedIds
                (holder as BookHolder).bind(row.book, selected, row.parentSeries != null)
                holder.itemView.setOnClickListener {
                    if (multiSelectMode) toggleBook(row.book) else selectOnly(row.book.bookId)
                }
                holder.itemView.setOnLongClickListener {
                    enterMultiSelect(row.book)
                    true
                }
            }
            is Row.Series -> {
                val expanded = row.group.key in expandedGroups
                val selectedCount = row.group.books.count { it.bookId in selectedIds }
                (holder as SeriesHolder).bind(row.group, expanded, selectedCount, multiSelectMode)
                holder.toggle.setOnClickListener {
                    if (!expandedGroups.add(row.group.key)) expandedGroups.remove(row.group.key)
                    rebuildRows()
                }
                holder.toggle.setOnLongClickListener {
                    setMultiSelectMode(true)
                    row.group.books.filter { selectedIds.add(it.bookId) }.forEach { onSelectionChanged(it, true) }
                    notifyDataSetChanged()
                    true
                }
            }
        }
    }

    private fun toggleBook(book: BookCandidate) {
        val selected = if (selectedIds.remove(book.bookId)) false else {
            selectedIds += book.bookId
            true
        }
        onSelectionChanged(book, selected)
        notifyDataSetChanged()
    }

    private fun enterMultiSelect(book: BookCandidate) {
        setMultiSelectMode(true)
        if (selectedIds.add(book.bookId)) onSelectionChanged(book, true)
        notifyDataSetChanged()
    }

    private fun setMultiSelectMode(value: Boolean) {
        if (multiSelectMode == value) return
        multiSelectMode = value
        onSelectionModeChanged(value)
    }

    private fun rebuildRows() {
        rows.clear()
        LibraryGrouping.group(books).forEach { group ->
            if (group.isSeries) {
                rows += Row.Series(group)
                if (group.key in expandedGroups) group.books.forEach { rows += Row.Book(it, group.key) }
            } else rows += Row.Book(group.books.single())
        }
        notifyDataSetChanged()
    }

    class SeriesHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view as MaterialCardView
        val toggle: View = view.findViewById(R.id.seriesToggle)
        private val cover: ImageView = view.findViewById(R.id.seriesCover)
        private val placeholder: TextView = view.findViewById(R.id.seriesPlaceholder)
        private val title: TextView = view.findViewById(R.id.seriesTitle)
        private val summary: TextView = view.findViewById(R.id.seriesSummary)
        private val selection: TextView = view.findViewById(R.id.seriesSelection)
        private val arrow: ImageView = view.findViewById(R.id.seriesArrow)

        fun bind(group: LibraryGroup, expanded: Boolean, selectedCount: Int, multiSelectMode: Boolean) {
            val bitmap = group.books.asSequence().mapNotNull { book ->
                book.coverCachePath?.let(BitmapFactory::decodeFile)
            }.firstOrNull()
            cover.visibility = if (bitmap == null) View.GONE else View.VISIBLE
            placeholder.visibility = if (bitmap == null) View.VISIBLE else View.GONE
            cover.setImageBitmap(bitmap)
            title.text = group.title
            summary.text = "${group.books.size} books · ${group.decryptedCount}/${group.books.size} Decrypted"
            val allSelected = selectedCount == group.books.size
            selection.visibility = if (multiSelectMode && selectedCount > 0) View.VISIBLE else View.GONE
            selection.text = if (allSelected) "✓ All selected" else "$selectedCount/${group.books.size} selected"
            arrow.animate().rotation(if (expanded) 90f else 0f).setDuration(200).start()
            card.strokeWidth = dp(if (allSelected) 2 else 1)
            card.strokeColor = ContextCompat.getColor(
                itemView.context,
                when {
                    allSelected -> R.color.brand_blue
                    expanded -> R.color.series_expanded_border
                    else -> R.color.border
                }
            )
            card.setCardBackgroundColor(
                ContextCompat.getColor(
                    itemView.context,
                    when {
                        allSelected -> R.color.selected_surface
                        expanded -> R.color.series_expanded_surface
                        else -> R.color.surface
                    }
                )
            )
            (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin = dp(if (expanded) 4 else 10)
                card.layoutParams = params
            }
        }

        private fun dp(value: Int): Int = (value * itemView.resources.displayMetrics.density).toInt()
    }

    class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view as MaterialCardView
        private val cover: ImageView = view.findViewById(R.id.bookCover)
        private val format: TextView = view.findViewById(R.id.bookFormat)
        private val title: TextView = view.findViewById(R.id.bookTitle)
        private val author: TextView = view.findViewById(R.id.bookAuthor)
        private val details: TextView = view.findViewById(R.id.bookDetails)
        private val rental: TextView = view.findViewById(R.id.bookRental)
        private val tags: View = view.findViewById(R.id.bookTags)
        private val quality: TextView = view.findViewById(R.id.bookQuality)
        private val decrypted: TextView = view.findViewById(R.id.bookDecrypted)

        fun bind(item: BookCandidate, selected: Boolean, inSeries: Boolean) {
            (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = dp(if (inSeries) 18 else 0)
                params.bottomMargin = dp(if (inSeries) 6 else 10)
                card.layoutParams = params
            }
            format.text = item.displayFormat
            val bitmap = item.coverCachePath?.let(BitmapFactory::decodeFile)
            cover.visibility = if (bitmap == null) View.GONE else View.VISIBLE
            format.visibility = if (bitmap == null) View.VISIBLE else View.GONE
            cover.setImageBitmap(bitmap)
            title.text = item.displayTitle
            author.text = item.author ?: "Author information unavailable"
            val formatDetails = if (item.isComic && item.displayedComicPages != null) {
                "COMIC · ${item.displayedComicPages} pages"
            } else item.displayFormat
            details.text = "$formatDetails · ${item.storageState}"

            val qualityLabel = if (item.isComic) when (item.comicQuality?.lowercase()) {
                "original" -> "Original quality"
                "recommended" -> "Standard quality"
                else -> null
            } else null
            quality.visibility = if (qualityLabel == null) View.GONE else View.VISIBLE
            quality.text = qualityLabel
            quality.setBackgroundResource(
                if (item.comicQuality.equals("original", true)) R.drawable.bg_badge_blue else R.drawable.bg_badge_gray
            )
            quality.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (item.comicQuality.equals("original", true)) R.color.brand_blue_dark else R.color.text_secondary
                )
            )
            decrypted.visibility = if (item.isDecrypted) View.VISIBLE else View.GONE
            tags.visibility = if (qualityLabel != null || item.isDecrypted) View.VISIBLE else View.GONE

            rental.visibility = if (item.expiresAt == null && !item.isOwned) View.GONE else View.VISIBLE
            if (item.isOwned) {
                rental.text = "Owned"
                rental.setTextColor(ContextCompat.getColor(itemView.context, R.color.success_text))
            } else item.expiresAt?.let { expires ->
                val expired = expires.before(Date())
                rental.text = if (expired) {
                    if (item.hasRequiredLocalFiles) "Rental expired · Still decryptable" else "Rental expired · Incomplete local files"
                } else "Rental until ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(expires)}"
                rental.setTextColor(ContextCompat.getColor(itemView.context, if (expired) R.color.error_text else R.color.success_text))
            }

            card.strokeWidth = if (selected) dp(2) else dp(1)
            card.strokeColor = ContextCompat.getColor(itemView.context, if (selected) R.color.brand_blue else R.color.border)
            card.setCardBackgroundColor(ContextCompat.getColor(itemView.context, if (selected) R.color.selected_surface else R.color.surface))
        }

        private fun dp(value: Int): Int = (value * itemView.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TYPE_BOOK = 0
        private const val TYPE_SERIES = 1
    }
}
