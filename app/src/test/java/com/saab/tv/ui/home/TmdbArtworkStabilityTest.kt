package com.saab.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbArtworkStabilityTest {
    @Test
    fun keepsCatalogArtworkWhenTmdbEnrichmentArrivesLater() {
        assertEquals(
            "https://cinemeta.example/backdrop.jpg",
            preferExistingArtwork(
                "https://cinemeta.example/backdrop.jpg",
                "https://image.tmdb.org/t/p/w1280/tmdb-backdrop.jpg"
            )
        )
    }

    @Test
    fun fillsMissingArtworkFromTmdb() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/title-logo.png",
            preferExistingArtwork(null, "https://image.tmdb.org/t/p/w500/title-logo.png")
        )
    }

    @Test
    fun ignoresBlankArtworkUrls() {
        assertEquals("tmdb-logo", preferExistingArtwork("  ", "tmdb-logo"))
        assertNull(preferExistingArtwork("", " "))
    }
}
