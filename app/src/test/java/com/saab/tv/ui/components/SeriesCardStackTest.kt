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
        assertTrue(seriesCardStackLayers.all { it.fillAlpha == 1f })
        assertTrue(seriesCardStackLayers.all { it.shadowElevationDp in 1f..4f })
        assertEquals(.18f, seriesCardStackLayers.first().borderAlpha, 0f)
        assertEquals(.25f, seriesCardStackLayers.last().borderAlpha, 0f)
        val (back, middle) = seriesCardStackLayers
        assertEquals(16f, back.xOffsetDp, 0f)
        assertEquals(8f, back.yOffsetDp, 0f)
        assertEquals(8f, middle.xOffsetDp, 0f)
        assertEquals(4f, middle.yOffsetDp, 0f)

        // Keep a visible stack cue while retaining a few dp before the next card.
        val posterWidth = 140f
        fun rightEdge(layer: SeriesCardStackLayer) =
            posterWidth - (posterWidth * (1f - layer.widthFraction) / 2f) + layer.xOffsetDp
        assertTrue(rightEdge(back) > rightEdge(middle))
        assertTrue(rightEdge(back) - posterWidth in 0f..16f)
        assertTrue(20f - (rightEdge(back) - posterWidth) >= 4f)
    }
}
