package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDisplayFormatterTest {
    @Test
    fun displaysContentTitleAndCompactLanguageSummary() {
        val stream = Stream(
            name = "Torrentio",
            title = "Release.Name.2026.2160p.English.Hindi.Tamil.Malayalam.8.3.GB",
            description = "Seeds: 450"
        )

        val display = StreamDisplayFormatter.format("Example Movie", stream)

        assertEquals("Example Movie", display.title)
        assertTrue(display.details.startsWith("Torrentio • TorBox Not Checked • English, Hindi +2 Other Languages"))
        assertTrue(display.details.contains("4K"))
        assertTrue(display.details.contains("450 Seeders"))
        assertFalse(display.title.contains("2160p"))
    }

    @Test
    fun infersUnlistedLanguageFromDualAudioMarker() {
        val stream = Stream(title = "Example 1080p English Dual Audio")

        assertEquals("English +1 Other Language", StreamDisplayFormatter.languageSummary(stream))
    }

    @Test
    fun displaysDolbyVisionAndSpecificHdrFormatOnEachSource() {
        val stream = Stream(
            title = "Example.Movie.2160p.English.DV.HDR10Plus.8.GB",
            description = "Seeds: 200"
        )

        val display = StreamDisplayFormatter.format("Example Movie", stream)

        assertTrue(display.details.contains("DV"))
        assertTrue(display.details.contains("HDR10+"))
        assertTrue(display.details.contains("4K"))
    }

    @Test
    fun displaysMediaFusionProviderFromAddonTransport() {
        val stream = Stream(
            title = "Example Movie 1080p English",
            addonTransportUrl = "https://mediafusion.example/manifest.json"
        )

        val display = StreamDisplayFormatter.format("Example Movie", stream)

        assertTrue(display.details.startsWith("MediaFusion • TorBox Not Checked • English"))
        assertTrue(display.details.contains("Seeders Not Reported"))
    }

    @Test
    fun displaysStructuredSeederMetadata() {
        val display = StreamDisplayFormatter.format(
            "Example Movie",
            Stream(name = "Torrentio", title = "1080p", seeders = 73)
        )

        assertTrue(display.details.contains("73 Seeders"))
        assertFalse(display.details.contains("Not Reported"))
    }

    @Test
    fun displaysPreferredLanguagesFromCompactAddonMetadata() {
        val stream = Stream(
            description = "🌐 EN + TE + HI + TA + ML"
        )

        assertEquals(
            "English, Telugu, Hindi +2 Other Languages",
            StreamDisplayFormatter.languageSummary(stream)
        )
    }
}
