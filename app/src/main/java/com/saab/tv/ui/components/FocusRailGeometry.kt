package com.saab.tv.ui.components

import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** Shared dimensions for the fixed focus frame of poster and landscape shelves. */
internal data class FocusFrameSize(val width: Dp, val height: Dp)

internal fun focusFrameSize(itemWidth: Dp, isLandscape: Boolean): FocusFrameSize =
    FocusFrameSize(
        width = itemWidth,
        height = itemWidth * if (isLandscape) (9f / 16f) else (3f / 2f)
    )

/**
 * Keeps the focus frame at the rail pivot while a scrollable row moves beneath
 * it. Only follow the card when there is no scroll range, or the final finite
 * item is clamped at the trailing edge and cannot reach the pivot.
 */
internal fun selectedShelfItemOffsetPx(
    visibleItemOffsets: List<Pair<Int, Int>>,
    focusedItemIndex: Int?,
    pivotOffsetPx: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    totalItemCount: Int,
    repeatedSectionSize: Int = 0
): Int = selectedShelfItemOffsetPx(
    visibleItems = visibleItemOffsets,
    focusedItemIndex = focusedItemIndex,
    pivotOffsetPx = pivotOffsetPx,
    canScrollBackward = canScrollBackward,
    canScrollForward = canScrollForward,
    totalItemCount = totalItemCount,
    repeatedSectionSize = repeatedSectionSize,
    itemIndex = { it.first },
    itemOffset = { it.second }
)

/** Scans visible items directly so scrolling doesn't allocate mapped/filtered lists per layout. */
internal inline fun <T> selectedShelfItemOffsetPx(
    visibleItems: List<T>,
    focusedItemIndex: Int?,
    pivotOffsetPx: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    totalItemCount: Int,
    repeatedSectionSize: Int = 0,
    itemIndex: (T) -> Int,
    itemOffset: (T) -> Int
): Int {
    if (focusedItemIndex == null || focusedItemIndex !in 0 until totalItemCount) return pivotOffsetPx

    val hasScrollRange = canScrollBackward || canScrollForward
    // At the trailing edge, even cards before a terminal View All item can be
    // clamped short of the leading pivot. Follow whichever card is focused once
    // the row has no remaining forward scroll, not only the final lazy item.
    val trailingEdgeIsClamped = repeatedSectionSize == 0 && !canScrollForward
    if (hasScrollRange && !trailingEdgeIsClamped) return pivotOffsetPx

    var closestOffset: Int? = null
    var closestDistance = Int.MAX_VALUE
    for (item in visibleItems) {
        val index = itemIndex(item)
        val isFocusedItem = if (repeatedSectionSize > 0) {
            index % repeatedSectionSize == focusedItemIndex % repeatedSectionSize
        } else {
            index == focusedItemIndex
        }
        if (!isFocusedItem) continue

        val offset = itemOffset(item)
        val distance = abs(offset - pivotOffsetPx)
        if (distance < closestDistance) {
            closestOffset = offset
            closestDistance = distance
        }
    }
    return closestOffset ?: pivotOffsetPx
}
