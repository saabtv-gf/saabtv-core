package com.saab.tv.ui.watchlist

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistFocusPolicyTest {
    @Test
    fun resolvesTheSavedTitleAfterItMovesToAnotherLibrarySection() {
        val rows = listOf(
            "Watchlist" to listOf(MetaItem("other", "movie", "Other")),
            "Watched" to listOf(MetaItem("saved", "movie", "Saved"))
        )

        assertEquals(
            MyLibraryFocusTarget(1, "library:Watched", "saved", 0),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "saved")
        )
    }

    @Test
    fun prefersTheSavedSectionWhenTheSameTitleAppearsInSeveralRows() {
        val rows = listOf(
            "Continue Watching" to listOf(MetaItem("same", "movie", "Same")),
            "Watched" to listOf(MetaItem("same", "movie", "Same"))
        )

        assertEquals(
            MyLibraryFocusTarget(1, "library:Watched", "same", 0),
            WatchlistFocusPolicy.resolve(rows, "Watched", "same")
        )
    }

    @Test
    fun restoresTheExactAddonTitleWhenProviderIdsCollide() {
        val first = MetaItem(
            id = "shared-id",
            type = "movie",
            name = "First Add-on Title",
            addonBaseUrl = "https://first.example/manifest.json"
        )
        val second = MetaItem(
            id = "shared-id",
            type = "movie",
            name = "Second Add-on Title",
            addonBaseUrl = "https://second.example/manifest.json"
        )
        assertNotEquals(first.canonicalPresentationKey(), second.canonicalPresentationKey())

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", second.canonicalPresentationKey(), 1),
            WatchlistFocusPolicy.resolve(
                listOf("Watchlist" to listOf(first, second)),
                "Watchlist",
                second.canonicalPresentationKey(),
                savedItemIndex = 0
            )
        )
    }

    @Test
    fun doesNotTreatMissingCanonicalKeyAsAnotherTitlesProviderId() {
        val remaining = listOf(
            MetaItem("legacy/movie/removed", "movie", "Different Title"),
            MetaItem("next", "movie", "Next Title")
        )

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", "next", 1),
            WatchlistFocusPolicy.resolve(
                listOf("Watchlist" to remaining),
                "Watchlist",
                "legacy/movie/removed",
                savedItemIndex = 1
            )
        )
    }

    @Test
    fun fallsForwardToTheCardThatMovedIntoTheRemovedCardsSlot() {
        val rows = listOf(
            "Watchlist" to listOf(
                MetaItem("before", "movie", "Before"),
                MetaItem("after", "movie", "After")
            )
        )

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", "after", 1),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "removed", savedItemIndex = 1)
        )
    }

    @Test
    fun clampsTheSavedPositionWhenTheRemovedCardWasAtTheEnd() {
        val rows = listOf(
            "Watchlist" to listOf(
                MetaItem("first", "movie", "First"),
                MetaItem("last", "movie", "Last")
            )
        )

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", "last", 1),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "removed", savedItemIndex = 2)
        )
    }

    @Test
    fun usesTheNearestRemainingSectionWhenTheFocusedSectionDisappears() {
        val rows = listOf(
            "Continue Watching" to listOf(MetaItem("continue", "movie", "Continue")),
            "Watched" to listOf(MetaItem("watched", "movie", "Watched"))
        )

        assertEquals(
            MyLibraryFocusTarget(1, "library:Watched", "watched", 0),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "removed", savedItemIndex = 4)
        )
    }

    @Test
    fun returnsNoTargetWhenEveryVisibleSectionIsEmpty() {
        assertEquals(
            null,
            WatchlistFocusPolicy.resolve(
                listOf("Watchlist" to emptyList()),
                "Watchlist",
                "removed",
                savedItemIndex = 0
            )
        )
    }

    @Test
    fun rejectsBlankSavedIdentitiesAndFallsBackForUnknownSections() {
        val rows = listOf("Watchlist" to listOf(MetaItem("first", "movie", "First")))

        assertEquals(null, WatchlistFocusPolicy.resolve(rows, "Watchlist", null))
        assertEquals(null, WatchlistFocusPolicy.resolve(rows, "Watchlist", "  "))
        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", "first", 0),
            WatchlistFocusPolicy.resolve(rows, "Imported", "removed")
        )
    }

    @Test
    fun canonicalNamespacesAreNotReinterpretedAsRawProviderIds() {
        val rows = listOf("Watchlist" to listOf(MetaItem("next", "movie", "Next")))

        listOf("addon/", "imdb/", "legacy/", "tmdb/").forEach { prefix ->
            assertEquals(
                MyLibraryFocusTarget(0, "library:Watchlist", "next", 0),
                WatchlistFocusPolicy.resolve(rows, "Watchlist", "${prefix}movie/removed")
            )
        }
    }

    @Test
    fun fallbackToTheSameSectionDefaultsToItsFirstCardWhenNoSlotWasSaved() {
        val rows = listOf("Watchlist" to listOf(
            MetaItem("first", "movie", "First"),
            MetaItem("second", "movie", "Second")
        ))

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", "first", 0),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "removed")
        )
    }

    @Test
    fun equalDistanceSectionFallbackPrefersTheNextSection() {
        val rows = listOf(
            "Continue Watching" to listOf(MetaItem("continue", "movie", "Continue")),
            "Watched" to listOf(MetaItem("watched", "movie", "Watched"))
        )

        assertEquals(
            MyLibraryFocusTarget(1, "library:Watched", "watched", 0),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "removed")
        )
    }

    @Test
    fun equalDistanceFallbackIsStableWhenRowsArriveInReverseOrder() {
        val rows = listOf(
            "Watched" to listOf(MetaItem("watched", "movie", "Watched")),
            "Continue Watching" to listOf(MetaItem("continue", "movie", "Continue"))
        )

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watched", "watched", 0),
            WatchlistFocusPolicy.resolve(rows, "Watchlist", "removed")
        )
    }

    @Test
    fun knownSectionFallbackUsesTheClosestAvailableSection() {
        val rows = listOf(
            "Watchlist" to listOf(MetaItem("watchlist", "movie", "Watchlist")),
            "Watched" to listOf(MetaItem("watched", "movie", "Watched"))
        )

        assertEquals(
            MyLibraryFocusTarget(0, "library:Watchlist", "watchlist", 0),
            WatchlistFocusPolicy.resolve(rows, "Continue Watching", "removed")
        )
    }

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
