package com.saab.tv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCardBadgePolicyTest {
    @Test fun watchlistBadgeIsVisibleOnlyForAnItemInTheActiveProfileSet() {
        val activeProfileWatchlist = setOf("tt-movie", "cinemeta:series")
        assertTrue(shouldShowWatchlistBadge("tt-movie", activeProfileWatchlist))
        assertTrue(shouldShowWatchlistBadge("cinemeta:series", activeProfileWatchlist))
        assertFalse(shouldShowWatchlistBadge("other-title", activeProfileWatchlist))
        assertFalse(shouldShowWatchlistBadge("tt-movie", emptySet()))
        assertFalse(shouldShowWatchlistBadge(null, activeProfileWatchlist))
    }

    @Test fun watchedBadgeAndWatchlistBadgeRemainIndependentStates() {
        assertTrue(shouldShowWatchedBadge(true, "title", emptySet()))
        assertFalse(shouldShowWatchedBadge(false, "title", emptySet()))
        assertTrue(shouldShowWatchlistBadge("title", setOf("title")))
        assertFalse(shouldShowWatchlistBadge("title", emptySet()))
    }
}
