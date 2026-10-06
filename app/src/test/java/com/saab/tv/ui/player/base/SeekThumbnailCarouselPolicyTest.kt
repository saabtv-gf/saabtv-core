package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekThumbnailCarouselPolicyTest {
    @Test
    fun carouselAlwaysHasFiveCardsForEveryCacheInterval() {
        assertEquals(5, SeekThumbnailCarouselPolicy.cardCount(10))
        assertEquals(5, SeekThumbnailCarouselPolicy.cardCount(20))
        assertEquals(5, SeekThumbnailCarouselPolicy.cardCount(30))
    }

    @Test
    fun offsetsAlwaysKeepSelectedThumbnailCentered() {
        assertEquals(listOf(-2, -1, 0, 1, 2), SeekThumbnailCarouselPolicy.offsets(10))
        assertEquals(listOf(-2, -1, 0, 1, 2), SeekThumbnailCarouselPolicy.offsets(20))
        assertEquals(listOf(-2, -1, 0, 1, 2), SeekThumbnailCarouselPolicy.offsets(30))
    }

    @Test
    fun centerThumbnailLoadsBeforeNeighbours() {
        assertEquals(listOf(2, 1, 3, 0, 4), SeekThumbnailCarouselPolicy.loadOrder(5))
        assertEquals(listOf(1, 0, 2), SeekThumbnailCarouselPolicy.loadOrder(3))
        assertEquals(listOf(0), SeekThumbnailCarouselPolicy.loadOrder(1))
    }

    @Test
    fun overlappingCachedFramesAreRetainedWhenSeekingByOneInterval() {
        assertEquals(
            listOf(1, 2, 3, 4, null),
            SeekThumbnailCarouselPolicy.retainedFrameIndices(
                listOf(0L, 10L, 20L, 30L, 40L),
                listOf(10L, 20L, 30L, 40L, 50L)
            )
        )
    }

    @Test
    fun unknownAndRevisitedPositionsMapOnlyToExistingFrames() {
        assertEquals(
            listOf(null, 0, null),
            SeekThumbnailCarouselPolicy.retainedFrameIndices(listOf(30L, 60L), listOf(null, 30L, 90L))
        )
    }
}
