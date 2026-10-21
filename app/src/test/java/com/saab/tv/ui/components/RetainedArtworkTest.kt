package com.saab.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedArtworkTest {
    private val previous = ColorPainter(Color.Red)
    private val current = ColorPainter(Color.Blue)

    @Test fun priorArtworkIsRetainedForTheSameIdentityUntilReplacementArrives() {
        assertSame(
            previous,
            artworkPainterForContent(
                current = null,
                previous = previous,
                currentContentKey = "title-a",
                previousContentKey = "title-a"
            )
        )
    }

    @Test fun artworkIsNeverBorrowedAcrossTitleReuse() {
        assertNull(
            artworkPainterForContent(
                current = null,
                previous = previous,
                currentContentKey = "title-b",
                previousContentKey = "title-a"
            )
        )
        assertSame(
            current,
            artworkPainterForContent(
                current = current,
                previous = previous,
                currentContentKey = "title-b",
                previousContentKey = "title-a"
            )
        )
    }

    @Test fun artworkIsNotRetainedWhenTheCurrentContentHasNoIdentity() {
        assertNull(
            artworkPainterForContent(
                current = null,
                previous = previous,
                currentContentKey = null,
                previousContentKey = null
            )
        )
    }

    @Test fun stableContentIdentitySurvivesArtworkEnrichment() {
        val beforeEnrichment = artworkContentIdentityKey("tmdb:42", "poster-v1", "Same Name")
        val afterEnrichment = artworkContentIdentityKey("tmdb:42", "poster-v2", "Same Name")

        assertEquals(beforeEnrichment, afterEnrichment)
    }

    @Test fun duplicateTitlesWithoutContentIdsDoNotShareRetainedArtwork() {
        val first = artworkContentIdentityKey(null, "poster-a", "Shared Title")
        val second = artworkContentIdentityKey(null, "poster-b", "Shared Title")

        assertNotEquals(first, second)
    }

    @Test fun validPendingArtworkDoesNotFlashFallbackButMissingOrFailedArtworkDoes() {
        assertFalse(
            shouldShowArtworkFallback(
                hasDisplayedArtwork = false,
                hasArtworkModel = true,
                requestFailed = false
            )
        )
        assertTrue(
            shouldShowArtworkFallback(
                hasDisplayedArtwork = false,
                hasArtworkModel = false
            )
        )
        assertTrue(
            shouldShowArtworkFallback(
                hasDisplayedArtwork = false,
                hasArtworkModel = true,
                requestFailed = true
            )
        )
        assertFalse(
            shouldShowArtworkFallback(
                hasDisplayedArtwork = true,
                hasArtworkModel = true,
                requestFailed = true
            )
        )
    }
}
