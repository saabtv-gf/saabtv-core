package com.saab.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationRegressionTest {
    @Test fun subtitleMatchingNormalizesExtensionsRejectsWrongEpisodeAndEdition() {
        assertEquals(10000, SubtitleReleaseMatcher.score("Movie.1080p.MKV", "movie 1080p.srt"))
        assertEquals(-10000, SubtitleReleaseMatcher.score("Show.S01E02", "Show.S01E03"))
        assertEquals(-1000, SubtitleReleaseMatcher.score("Movie.Extended", "Movie.Theatrical"))
        assertEquals(39, SubtitleReleaseMatcher.score("Movie.1080p", "Movie.1080p.Group"))
        listOf(null, "", " ").forEach {
            assertEquals(0, SubtitleReleaseMatcher.score(it, "release"))
            assertEquals(0, SubtitleReleaseMatcher.score("file", it))
        }
    }
}
