package com.saab.tv.ui.details

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesActionLabelTest {
    @Test fun nextEpisodeIsNotLabelledResume() {
        assertEquals(SeriesResumeActionKind.PLAY_NEXT_EPISODE, seriesResumeActionKind(1, 4, true))
        assertEquals(SeriesResumeActionKind.PLAY_NEXT, seriesResumeActionKind(null, null, true))
    }
    @Test fun inProgressEpisodeRemainsResume() {
        assertEquals(SeriesResumeActionKind.RESUME_EPISODE, seriesResumeActionKind(1, 3, false))
        assertEquals(SeriesResumeActionKind.RESUME, seriesResumeActionKind(null, null, false))
    }
}
