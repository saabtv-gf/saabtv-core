package com.saab.tv.ui.components

import com.saab.tv.ui.components.SeriesCardStackLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCardStackTest {
    @Test fun onlySeriesUseTheStackedPosterTreatment() {
        assertTrue(usesSeriesPosterStack("series"))
        assertTrue(usesSeriesPosterStack("tv"))
        assertEquals(false, usesSeriesPosterStack("movie"))
        assertEquals(false, usesSeriesPosterStack(null))
    }

    @Test fun seriesPosterUsesTwoCloselyPackedLayersBehindItsCover() {
        assertEquals(2, seriesCardStackLayers.size)
        assertTrue(seriesCardStackLayers.all {
            it.widthFraction > .94f && it.heightFraction > .95f
        })
        assertEquals(.45f, seriesCardStackLayers.first().alpha, 0f)
        assertEquals(.70f, seriesCardStackLayers.last().alpha, 0f)
        val (back, middle) = seriesCardStackLayers
        assertEquals(16f, back.xOffsetDp, 0f)
        assertEquals(8f, back.yOffsetDp, 0f)
        assertEquals(8f, middle.xOffsetDp, 0f)
        assertEquals(4f, middle.yOffsetDp, 0f)
        val posterRight = 140f
        val middleRight = 140f * middle.widthFraction + 8f
        val backRight = 140f * back.widthFraction + 16f
        assertTrue(posterRight < middleRight && middleRight < backRight)
    }
}
