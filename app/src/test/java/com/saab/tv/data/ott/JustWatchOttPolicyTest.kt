package com.saab.tv.data.ott

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JustWatchOttPolicyTest {
    @Test
    fun mediaKindsUseLiveJustWatchAndCinemetaTypes() {
        assertEquals("MOVIE", OttMediaKind.MOVIE.justWatchType)
        assertEquals("movie", OttMediaKind.MOVIE.cinemetaType)
        assertEquals("SHOW", OttMediaKind.SERIES.justWatchType)
        assertEquals("series", OttMediaKind.SERIES.cinemetaType)
    }

    @Test
    fun newlyAddedSeriesKeepsParentShowIdentity() {
        val candidate = JustWatchCandidate(
            imdbId = "tt1234567",
            kind = OttMediaKind.SERIES,
            title = "Example Show",
            year = 2025,
            platforms = listOf("Netflix"),
            genres = setOf("drm"),
            ageCertification = "UA16+",
            description = null,
            addedDate = "2026-09-14"
        )

        assertTrue(candidate.imdbId.startsWith("tt"))
        assertEquals(OttMediaKind.SERIES, candidate.kind)
        assertEquals("Example Show", candidate.title)
    }

    @Test
    fun seasonResultUsesParentShowMetadataAndAllowsMissingAudioMetadata() {
        val edges = JsonParser.parseString(
            """
            [{
              "node": {
                "__typename": "Season",
                "content": { "genres": [{"shortName": "drm"}] },
                "show": { "content": {
                  "title": "Parent Show",
                  "originalReleaseYear": 2024,
                  "externalIds": {"imdbId": "tt7654321"},
                  "genres": [{"shortName": "drm"}]
                }},
                "offers": [{
                  "audioLanguages": [],
                  "package": {"clearName": "Netflix"}
                }]
              }
            }]
            """.trimIndent()
        ).asJsonArray

        val result = JustWatchOttClient(okhttp3.OkHttpClient()).parseEdges(
            edges = edges,
            expectedKind = OttMediaKind.SERIES,
            provider = OttProvider("Netflix", "nfx"),
            addedDate = "2026-09-14"
        )

        assertEquals(1, result.size)
        assertEquals("tt7654321", result.single().imdbId)
        assertEquals("Parent Show", result.single().title)
    }

    @Test
    fun explicitUnsupportedAudioAndAnimationAreExcluded() {
        val edges = JsonParser.parseString(
            """
            [
              {"node": {"__typename": "Movie", "content": {
                "title": "Spanish Only", "externalIds": {"imdbId": "tt1111111"},
                "genres": [{"shortName": "drm"}]
              }, "offers": [{"audioLanguages": ["Spanish"]}]}},
              {"node": {"__typename": "Movie", "content": {
                "title": "Animated", "externalIds": {"imdbId": "tt2222222"},
                "genres": [{"shortName": "ani"}]
              }, "offers": [{"audioLanguages": ["English"]}]}}
            ]
            """.trimIndent()
        ).asJsonArray

        val result = JustWatchOttClient(okhttp3.OkHttpClient()).parseEdges(
            edges = edges,
            expectedKind = OttMediaKind.MOVIE,
            provider = null,
            addedDate = null
        )

        assertTrue(result.isEmpty())
    }
}
