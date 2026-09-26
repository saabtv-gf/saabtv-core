package com.saab.tv.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekThumbnailCacheTest {
    @Test
    fun supportsOnlyConfiguredIntervals() {
        assertEquals(10, SeekThumbnailCache.normalizeInterval(10))
        assertEquals(20, SeekThumbnailCache.normalizeInterval(20))
        assertEquals(30, SeekThumbnailCache.normalizeInterval(30))
        assertEquals(10, SeekThumbnailCache.normalizeInterval(15))
    }

    @Test
    fun quantizesPlaybackPositionToIntervalStart() {
        assertEquals(0L, SeekThumbnailCache.quantizePositionSeconds(9_999L, 10))
        assertEquals(10L, SeekThumbnailCache.quantizePositionSeconds(10_000L, 10))
        assertEquals(20L, SeekThumbnailCache.quantizePositionSeconds(39_999L, 20))
        assertEquals(60L, SeekThumbnailCache.quantizePositionSeconds(89_000L, 30))
    }

    @Test
    fun completeStoryboardIncludesEveryConfiguredInterval() {
        assertEquals(
            listOf(0L, 10_000L, 20_000L, 30_000L),
            SeekThumbnailCache.storyboardTargets(durationMs = 35_000L, intervalSeconds = 10)
        )
    }

    @Test
    fun startupStoryboardIsBoundedToFirstFiveMinutes() {
        val targets = SeekThumbnailCache.startupStoryboardTargets(
            durationMs = 3L * 60L * 60L * 1_000L,
            intervalSeconds = 10,
            startupWindowMs = 5L * 60L * 1_000L
        )

        assertEquals(31, targets.size)
        assertEquals(0L, targets.first())
        assertEquals(300_000L, targets.last())
    }

    @Test
    fun firstCachedFrameIsVisibleAsProgressForLongVideos() {
        assertEquals(0, SeekThumbnailCache.calculateProgressPercent(0, 1_080))
        assertEquals(1, SeekThumbnailCache.calculateProgressPercent(1, 1_080))
        assertEquals(3, SeekThumbnailCache.calculateProgressPercent(31, 1_080))
        assertEquals(100, SeekThumbnailCache.calculateProgressPercent(1_080, 1_080))
    }

    @Test
    fun progressivePlanWarmsStartThenCoversAndRefinesTimeline() {
        val targets = SeekThumbnailCache.progressiveStoryboardTargets(
            durationMs = 20L * 60L * 1_000L,
            intervalSeconds = 10
        )

        assertEquals((0L..300_000L step 10_000L).toList(), targets.take(31))
        assertEquals(targets.size, targets.distinct().size)
        assertEquals(true, 1_190_000L in targets)
        assertEquals(true, 310_000L in targets)
    }

    @Test
    fun progressivePlanAlwaysAlignsWithTwentySecondCacheBuckets() {
        val targets = SeekThumbnailCache.progressiveStoryboardTargets(
            durationMs = 20L * 60L * 1_000L,
            intervalSeconds = 20
        )

        assertEquals(true, targets.all { it % 20_000L == 0L })
        assertEquals(targets.size, targets.distinct().size)
    }

    @Test
    fun persistentDecoderMovesBlackProneZeroFrameToTheEnd() {
        val targets = SeekThumbnailCache.persistentDecoderTargets(
            durationMs = 90_000L,
            intervalSeconds = 10
        )

        assertEquals(10_000L, targets.first())
        assertEquals(0L, targets.last())
        assertEquals(targets.size, targets.distinct().size)
    }

    @Test
    fun persistentDecoderPrioritizesResumeWithoutLosingEarlierFrames() {
        val targets = SeekThumbnailCache.persistentDecoderTargets(
            durationMs = 2L * 60L * 60L * 1_000L,
            intervalSeconds = 10,
            priorityPositionMs = 60L * 60L * 1_000L
        )

        assertEquals(60L * 60L * 1_000L, targets.first())
        assertEquals(
            SeekThumbnailCache.storyboardTargets(2L * 60L * 60L * 1_000L, 10).toSet(),
            targets.toSet()
        )
        assertEquals(targets.size, targets.distinct().size)
    }

    @Test
    fun forwardRemainderPrecedesOlderGapsForAllIntervals() {
        for (interval in listOf(10, 20, 30)) {
            val targets = SeekThumbnailCache.persistentDecoderTargets(120_000L, interval, 60_000L)
            assertEquals(60_000L, targets.first())
            assertEquals(SeekThumbnailCache.storyboardTargets(120_000L, interval).toSet(), targets.toSet())
            assertEquals(true, targets.indexOf(90_000L.takeIf { interval != 20 } ?: 100_000L) < targets.indexOf(0L))
        }
    }
}
