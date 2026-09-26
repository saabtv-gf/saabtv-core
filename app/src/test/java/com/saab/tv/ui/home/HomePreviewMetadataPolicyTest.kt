package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreviewMetadataPolicyTest {
    @Test
    fun enrichedRatingWinsEvenWhenEnrichmentHasNoLogo() {
        val stale = MetaItem(id = "tt123", type = "movie", imdbRating = "6.1", logo = "old")
        val enriched = stale.copy(imdbRating = "8.4", logo = null)

        val selected = HomePreviewMetadataPolicy.select(
            current = stale,
            enriched = enriched,
            hero = stale,
            row = stale,
            history = null
        )

        assertEquals("8.4", selected.imdbRating)
    }

    @Test
    fun updatedRowWinsOverSparseHistoryMetadata() {
        val current = MetaItem(id = "tt456", type = "series")
        val row = current.copy(imdbRating = "9.0")
        val history = current.copy(name = "Saved History Title")

        val selected = HomePreviewMetadataPolicy.select(
            current = current,
            enriched = null,
            hero = null,
            row = row,
            history = history
        )

        assertEquals("9.0", selected.imdbRating)
    }
}
