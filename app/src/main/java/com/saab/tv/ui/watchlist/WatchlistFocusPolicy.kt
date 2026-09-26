package com.saab.tv.ui.watchlist

internal data class WatchlistFocusTarget(
    val rowIndex: Int,
    val itemId: String
)

internal object WatchlistFocusPolicy {
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
