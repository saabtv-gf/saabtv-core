package com.saab.tv.ui.home

import com.saab.tv.ui.components.shouldShowArtworkFallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCardPosterArtworkTest {
    @Test fun artworkFallbackAppearsUntilTheCurrentTitleArtworkIsVisible() {
        assertTrue(shouldShowArtworkFallback(hasDisplayedArtwork = false))
        assertFalse(shouldShowArtworkFallback(hasDisplayedArtwork = true))
    }
}
