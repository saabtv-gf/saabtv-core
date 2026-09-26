package com.saab.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class SubtitleReleaseMatcherTest {
    @Test fun exactReleaseBeatsAnotherEncodeAndWrongEpisode() {
        val file = "Show.S01E02.1080p.WEB-GROUP.mkv"
        assertTrue(SubtitleReleaseMatcher.score(file, "Show.S01E02.1080p.WEB-GROUP.srt") >
            SubtitleReleaseMatcher.score(file, "Show.S01E02.720p.OTHER.srt"))
        assertTrue(SubtitleReleaseMatcher.score(file, "Show.S01E03.1080p.WEB-GROUP.srt") < 0)
    }
    @Test fun unknownMetadataDoesNotInventAMatch() {
        assertEquals(0, SubtitleReleaseMatcher.score("Movie.mkv", null))
        assertTrue(SubtitleReleaseMatcher.score("Movie.Extended.mkv", "Movie.Theatrical.srt") < 0)
    }
}
