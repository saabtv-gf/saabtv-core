package com.saab.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaabTvLandscapeArtworkTest {
    @Test fun landscapeCardsUseOnlyBackdropArtwork() {
        assertEquals("https://image.test/backdrop.jpg", landscapeArtworkUrl("https://image.test/backdrop.jpg"))
        assertNull(landscapeArtworkUrl(null))
        assertNull(landscapeArtworkUrl("  "))
    }

    @Test fun missingLandscapeArtworkUsesInitialsEvenWhenPortraitPosterExists() {
        assertTrue(shouldShowLandscapeInitialArtwork(null))
        assertTrue(shouldShowLandscapeInitialArtwork("  "))
        assertTrue(shouldShowLandscapeInitialArtwork("backdrop.jpg", loadFailed = true))
        assertFalse(shouldShowLandscapeInitialArtwork("backdrop.jpg"))
    }
}
