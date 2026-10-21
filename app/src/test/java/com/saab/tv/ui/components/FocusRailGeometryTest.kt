package com.saab.tv.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusRailGeometryTest {
    @Test
    fun scrollableRailKeepsFocusFrameAtThePivotWhileTheCardMovesIntoFocus() {
        assertEquals(
            48,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(0 to 48, 1 to 180, 2 to 312),
                focusedItemIndex = 2,
                pivotOffsetPx = 48,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemCount = 12
            )
        )
    }

    @Test
    fun finalFiniteItemUsesItsViewportOffsetWhenStartPaddingIsNonZero() {
        // The final card's LazyListItemInfo.offset is already relative to the
        // row container; the 48px leading inset must not be added a second time.
        assertEquals(
            312,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(9 to 48, 10 to 180, 11 to 312),
                focusedItemIndex = 11,
                pivotOffsetPx = 48,
                canScrollBackward = true,
                canScrollForward = false,
                totalItemCount = 12
            )
        )
    }

    @Test
    fun cardsBeforeTerminalViewAllFollowTheirActualOffsetWhenTheTrailingEdgeIsClamped() {
        // With no trailing content padding, the final title cards cannot all
        // reach the leading pivot while View All still occupies the row tail.
        assertEquals(
            820,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(12 to 540, 13 to 680, 14 to 820, 15 to 960),
                focusedItemIndex = 14,
                pivotOffsetPx = 120,
                canScrollBackward = true,
                canScrollForward = false,
                totalItemCount = 16
            )
        )
    }

    @Test
    fun shortNonScrollableRailFollowsTheSelectedCard() {
        assertEquals(
            180,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(0 to 48, 1 to 180, 2 to 312),
                focusedItemIndex = 1,
                pivotOffsetPx = 48,
                canScrollBackward = false,
                canScrollForward = false,
                totalItemCount = 3
            )
        )
    }

    @Test
    fun repeatedScrollableShelfKeepsTheIndicatorAnchoredInsteadOfFollowingClones() {
        assertEquals(
            48,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(8 to -84, 13 to 80, 18 to 220),
                focusedItemIndex = 3,
                pivotOffsetPx = 48,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemCount = 30,
                repeatedSectionSize = 5
            )
        )
    }

    @Test
    fun repeatedNonScrollableShelfSelectsVisibleCopyClosestToPivot() {
        assertEquals(
            48,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(8 to -84, 13 to 48, 18 to 180),
                focusedItemIndex = 3,
                pivotOffsetPx = 48,
                canScrollBackward = false,
                canScrollForward = false,
                totalItemCount = 25,
                repeatedSectionSize = 5
            )
        )
    }

    @Test
    fun missingOrNotYetVisibleFocusTargetFallsBackToThePivot() {
        assertEquals(
            48,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = emptyList(), focusedItemIndex = 2, pivotOffsetPx = 48,
                canScrollBackward = true, canScrollForward = true, totalItemCount = 6
            )
        )
        assertEquals(
            48,
            selectedShelfItemOffsetPx(
                visibleItemOffsets = listOf(0 to 12), focusedItemIndex = null, pivotOffsetPx = 48,
                canScrollBackward = false, canScrollForward = false, totalItemCount = 1
            )
        )
    }
}
