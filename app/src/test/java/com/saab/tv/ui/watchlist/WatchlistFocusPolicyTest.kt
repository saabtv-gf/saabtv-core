package com.saab.tv.ui.watchlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistFocusPolicyTest {
    @Test
    fun parsesIdsContainingUnderscores() {
        assertEquals(
            WatchlistFocusTarget(1, "series_id_with_underscores"),
            WatchlistFocusPolicy.parse("1_series_id_with_underscores_7")
        )
    }

    @Test
    fun rejectsRemovedAndMalformedTargets() {
        assertTrue(
            WatchlistFocusPolicy.isValid("0_movie_one_0", setOf("movie_one"), emptySet())
        )
        assertFalse(
            WatchlistFocusPolicy.isValid("0_removed_0", setOf("movie_one"), emptySet())
        )
        assertFalse(WatchlistFocusPolicy.isValid("invalid", emptySet(), emptySet()))
    }
}
