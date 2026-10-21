package com.saab.tv

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridRestoreStateTest {
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun gridFocusAndViewportRoundTripWithTheirSourceIdentity() {
        val expected = GridRestoreState(
            sourceKey = gridRestoreSourceKey("Movies", "tmdb-popular"),
            focusedIndex = 17,
            focusedItemKey = "tmdb:movie:42",
            scrollIndex = 12,
            scrollOffset = 36
        )

        val encoded = with(GridRestoreStateSaver) {
            with(saverScope) { save(expected) }
        }

        assertEquals(expected, GridRestoreStateSaver.restore(encoded!!))
    }

    @Test
    fun aNewGridSourceCannotInheritAnotherShelfFocusOrScroll() {
        val original = GridRestoreState(
            sourceKey = gridRestoreSourceKey("Popular", "catalog-a"),
            focusedIndex = 22,
            focusedItemKey = "movie:22",
            scrollIndex = 18,
            scrollOffset = 24
        )

        assertEquals(original, original.forSource(gridRestoreSourceKey("Popular", "catalog-a")))
        assertEquals(
            GridRestoreState(sourceKey = gridRestoreSourceKey("Series", "catalog-b")),
            original.forSource(gridRestoreSourceKey("Series", "catalog-b"))
        )
    }

    @Test
    fun malformedSavedValuesFallBackToSafeInitialGridState() {
        val restored = GridRestoreStateSaver.restore(listOf("", -1, "", -3, -9))!!

        assertNull(restored.sourceKey)
        assertNull(restored.focusedIndex)
        assertNull(restored.focusedItemKey)
        assertEquals(0, restored.scrollIndex)
        assertEquals(0, restored.scrollOffset)
    }
}
