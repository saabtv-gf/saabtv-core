package com.saab.tv.ui.details

/** A route return may need to wait for asynchronous Details rows before restoring focus. */
internal sealed interface DetailsFocusRestoreTarget {
    data object Pending : DetailsFocusRestoreTarget
    data object Hero : DetailsFocusRestoreTarget
    data class RowItem(val rowIndex: Int, val itemIndex: Int) : DetailsFocusRestoreTarget
}

/**
 * Resolve a saved Details address only after the row's data is available. Item keys are the
 * canonical presentation keys used by related cards, or the stable TMDB person keys for cast.
 * Null entries represent non-focusable structural LazyRow items (for example the crew divider).
 */
internal fun resolveDetailsFocusRestoreTarget(
    rowKey: String?,
    itemKey: String?,
    fallbackIndex: Int,
    metadataPending: Boolean,
    rowKeys: List<String>,
    itemsByRow: Map<String, List<String?>>
): DetailsFocusRestoreTarget {
    if (rowKey == null) return DetailsFocusRestoreTarget.Hero

    val rowIndex = rowKeys.indexOf(rowKey)
    val itemKeys = itemsByRow[rowKey].orEmpty()
    if (rowIndex >= 0 && itemKeys.isNotEmpty()) {
        val exactIndex = itemKey?.let(itemKeys::indexOf)?.takeIf { it >= 0 }
        val preferredIndex = exactIndex ?: fallbackIndex.coerceIn(itemKeys.indices)
        val focusableIndex = if (itemKeys[preferredIndex] != null) preferredIndex else {
            itemKeys.indices
                .filter { itemKeys[it] != null }
                .minByOrNull { kotlin.math.abs(it - preferredIndex) }
        }
        if (focusableIndex != null) return DetailsFocusRestoreTarget.RowItem(rowIndex, focusableIndex)
    }

    return if (metadataPending) DetailsFocusRestoreTarget.Pending else DetailsFocusRestoreTarget.Hero
}
