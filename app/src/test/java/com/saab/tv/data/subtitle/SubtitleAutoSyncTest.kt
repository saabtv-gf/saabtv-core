package com.saab.tv.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncTest {
    @Test
    fun parsesSrtAndVttCueTimes() {
        val cues = SubtitleAutoSync.parseSubtitleSpans(
            """1
            |00:01:02,350 --> 00:01:05,500
            |Hello
            |
            |00:02:03.40 --> 00:02:06.50
            |World
            """.trimMargin()
        )
        assertEquals(listOf(62_350L to 65_500L, 123_400L to 126_500L), cues)
    }

    @Test
    fun overlapImprovesForCorrectShift() {
        val speech = listOf(1_000L to 2_000L, 4_000L to 5_000L)
        val subtitles = listOf(2_000L to 3_000L, 5_000L to 6_000L)
        assertTrue(
            SubtitleAutoSync.overlapRatio(speech, subtitles, -1_000L) >
                SubtitleAutoSync.overlapRatio(speech, subtitles, 0L)
        )
    }
}
