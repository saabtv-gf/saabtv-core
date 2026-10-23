package com.saab.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbHomeRailLoadPlanTest {
    @Test fun noConfiguredRailsDoesNoPersonalizationWork() {
        val plan = tmdbHomeRailLoadPlan(emptyList())

        assertFalse(plan.hasVisibleRails)
        assertFalse(plan.needsTasteSeeds)
        assertFalse(plan.needsInterestDetails)
        assertFalse(plan.needsWatchlist)
    }

    @Test fun newForYouOnlyDoesNotReadHistoryOrFetchTasteEnrichment() {
        val plan = tmdbHomeRailLoadPlan(listOf("tmdb-new-for-you"))

        assertTrue(plan.hasVisibleRails)
        assertFalse(plan.needsTasteSeeds)
        assertFalse(plan.needsInterestDetails)
        assertFalse(plan.needsWatchlist)
        assertTrue(plan.newForYou)
    }

    @Test fun topSuggestionsUsesWatchHistoryButNotEnrichment() {
        val plan = tmdbHomeRailLoadPlan(listOf("tmdb-top-suggestions"))

        assertTrue(plan.needsTasteSeeds)
        assertFalse(plan.needsInterestDetails)
        assertFalse(plan.needsWatchlist)
        assertTrue(plan.topSuggestions)
    }

    @Test fun tasteRailsShareBoundedHistoryAndEnrichmentInputs() {
        val plan = tmdbHomeRailLoadPlan(
            listOf("tmdb-favorite-genres", "tmdb-popular-in-taste")
        )

        assertTrue(plan.needsTasteSeeds)
        assertTrue(plan.needsInterestDetails)
        assertTrue(plan.needsWatchlist)
        assertTrue(plan.favoriteGenres)
        assertTrue(plan.popularInTaste)
    }

    @Test fun unknownAndDuplicateRailIdsDoNotEnableExtraWork() {
        val plan = tmdbHomeRailLoadPlan(
            listOf("tmdb-new-for-you", "unknown", "tmdb-new-for-you")
        )

        assertEquals(TmdbHomeRailLoadPlan(false, false, true, false), plan)
    }
}
