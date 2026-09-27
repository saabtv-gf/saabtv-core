package com.saab.tv.ui.details

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesActionLabelTest {
    @Test fun nextEpisodeIsNotLabelledResume() {
        assertEquals("Play Next S1 E4", seriesResumeActionLabel(1, 4, true))
        assertEquals("Play Next Episode", seriesResumeActionLabel(null, null, true))
    }
    @Test fun inProgressEpisodeRemainsResume() {
        assertEquals("Resume S1 E3", seriesResumeActionLabel(1, 3, false))
    }
}
