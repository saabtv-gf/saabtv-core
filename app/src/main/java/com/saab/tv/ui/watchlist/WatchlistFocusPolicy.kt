package com.saab.tv.ui.watchlist

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.home.homeFocusIdentityKey

internal data class WatchlistFocusTarget(
    val rowIndex: Int,
    val itemId: String
)

internal data class MyLibraryFocusTarget(
    val rowIndex: Int,
    val rowId: String,
    val itemId: String,
    val itemIndex: Int
)

internal object WatchlistFocusPolicy {
    fun rowId(sectionTitle: String): String = "library:$sectionTitle"

    /** Restore by content identity first, preferring the section that owned focus. */
    fun resolve(
        rows: List<Pair<String, List<MetaItem>>>,
        savedSectionTitle: String?,
        savedItemIdentity: String?,
        savedItemIndex: Int? = null
    ): MyLibraryFocusTarget? {
        val nonEmptyRows = rows.filter { it.second.isNotEmpty() }
        if (savedItemIdentity.isNullOrBlank() || nonEmptyRows.isEmpty()) return null
        nonEmptyRows.forEachIndexed { rowIndex, (title, items) ->
            if (title == savedSectionTitle) {
                val itemIndex = itemIndexForIdentity(items, savedItemIdentity)
                if (itemIndex >= 0) {
                    return target(rowIndex, title, items, itemIndex)
                }
            }
        }
        nonEmptyRows.forEachIndexed { rowIndex, (title, items) ->
            val itemIndex = itemIndexForIdentity(items, savedItemIdentity)
            if (itemIndex >= 0) return target(rowIndex, title, items, itemIndex)
        }

        // If the focused card left the filtered list, keep its old slot in the
        // same section (clamped to the last remaining card) instead of losing focus.
        val sameSection = nonEmptyRows.indexOfFirst { it.first == savedSectionTitle }
        val fallbackRow = if (sameSection >= 0) {
            sameSection
        } else {
            val previousOrder = sectionOrder[savedSectionTitle]
                ?: return target(0, nonEmptyRows[0].first, nonEmptyRows[0].second, 0)
            nonEmptyRows.indices.minWith(
                compareBy<Int> { (sectionOrder[nonEmptyRows[it].first] ?: previousOrder).let { order -> kotlin.math.abs(order - previousOrder) } }
                    .thenBy { sectionOrder[nonEmptyRows[it].first]?.let { order -> order < previousOrder } ?: false }
            )
        }
        val (title, items) = nonEmptyRows[fallbackRow]
        return target(fallbackRow, title, items, savedItemIndex?.coerceIn(items.indices) ?: 0)
    }

    private fun target(rowIndex: Int, title: String, items: List<MetaItem>, itemIndex: Int) =
        MyLibraryFocusTarget(rowIndex, rowId(title), items[itemIndex].homeFocusIdentityKey(), itemIndex)

    private fun itemIndexForIdentity(items: List<MetaItem>, savedIdentity: String): Int {
        val canonicalIndex = items.indexOfFirst { it.canonicalPresentationKey() == savedIdentity }
        if (canonicalIndex >= 0) return canonicalIndex
        // Raw IDs were saved by earlier app versions; keep restoring those addresses,
        // but never reinterpret a missing canonical key as another item's provider ID.
        val looksCanonical = savedIdentity.startsWith("addon/") || savedIdentity.startsWith("imdb/") ||
            savedIdentity.startsWith("legacy/") || savedIdentity.startsWith("tmdb/")
        return if (looksCanonical) -1 else items.indexOfFirst { it.id == savedIdentity }
    }

    private val sectionOrder = mapOf(
        LIBRARY_CONTINUE_WATCHING to 0,
        LIBRARY_WATCHLIST to 1,
        LIBRARY_WATCHED to 2
    )

    /** Parses row_itemId_position while preserving underscores inside item IDs. */
    fun parse(key: String?): WatchlistFocusTarget? {
        if (key.isNullOrBlank()) return null
        val firstSeparator = key.indexOf('_')
        val lastSeparator = key.lastIndexOf('_')
        if (firstSeparator <= 0 || lastSeparator <= firstSeparator) return null

        val rowIndex = key.substring(0, firstSeparator).toIntOrNull() ?: return null
        val itemId = key.substring(firstSeparator + 1, lastSeparator).takeIf { it.isNotBlank() }
            ?: return null
        return WatchlistFocusTarget(rowIndex, itemId)
    }

    fun isValid(key: String?, movieIds: Set<String>, seriesIds: Set<String>): Boolean {
        val target = parse(key) ?: return false
        return when (target.rowIndex) {
            0 -> target.itemId in movieIds
            1 -> target.itemId in seriesIds
            else -> false
        }
    }
}
