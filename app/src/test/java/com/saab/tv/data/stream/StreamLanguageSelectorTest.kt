package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamLanguageSelectorTest {
    @Test
    fun returnsFirstSortedMatchAsBestLanguageStream() {
        val bestTelugu = Stream(title = "Movie 2160p Telugu Seeds: 200")
        val secondTelugu = Stream(title = "Movie 1080p Telugu Seeds: 500")
        val english = Stream(title = "Movie 2160p English Seeds: 100")

        assertEquals(
            bestTelugu,
            StreamLanguageSelector.bestAvailable(
                listOf(bestTelugu, secondTelugu, english),
                "te"
            )
        )
    }

    @Test
    fun exposesOnlyLanguagesThatHaveStreams() {
        val streams = listOf(
            Stream(title = "Movie 2160p English"),
            Stream(title = "Movie 1080p Telugu"),
            Stream(title = "Movie 720p Hindi"),
            Stream(title = "Movie 720p Tamil")
        )

        assertEquals(
            listOf("en", "te", "hi"),
            StreamLanguageSelector.availableLanguages(streams).map { it.code }
        )
        assertNull(StreamLanguageSelector.bestAvailable(streams, "ml"))
    }

    @Test
    fun prefersTorrentioWhenBothProvidersHaveRequestedLanguage() {
        val mediaFusion = Stream(
            name = "[MediaFusion] 4K",
            title = "Movie Telugu Seeds: 900"
        )
        val torrentio = Stream(
            name = "[Torrentio] 4K",
            title = "Movie Telugu Seeds: 50"
        )

        assertEquals(
            torrentio,
            StreamLanguageSelector.bestAvailable(listOf(mediaFusion, torrentio), "te")
        )
    }

    @Test
    fun fallsBackToMediaFusionWhenTorrentioLacksRequestedLanguage() {
        val torrentioEnglish = Stream(
            name = "[Torrentio] 4K",
            title = "Movie English"
        )
        val mediaFusionHindi = Stream(
            name = "[MediaFusion] 4K",
            title = "Movie Hindi"
        )

        assertEquals(
            mediaFusionHindi,
            StreamLanguageSelector.bestAvailable(listOf(torrentioEnglish, mediaFusionHindi), "hi")
        )
    }
}
