package com.saab.tv.data.cache

import com.saab.tv.ui.player.base.PlayerSourceOption
import org.junit.Assert.*
import org.junit.Test

class ThumbnailPolicyBranchRegressionTest {
    private fun source(hash: String?, index: Int, filename: String) = PlayerSourceOption(
        id = "a", label = "A", url = "https://example.com/video", infoHash = hash,
        fileIdx = index, fileName = filename)

    @Test fun cacheIdentityUsesFileIdentityOnlyWhenItIsReliable() {
        val url = "https://example.com/first"
        val fallback = ThumbnailTimelinePolicy.cacheId("tt1", url, null)
        for (candidate in listOf(source(null, 1, "a"), source("", 1, "a"), source(" ", 1, "a"), source("abc", -1, ""))) {
            assertEquals(fallback, ThumbnailTimelinePolicy.cacheId("tt1", url, candidate))
        }
        val namedFile = ThumbnailTimelinePolicy.cacheId("tt1", url, source("abc", -1, "a"))
        val indexedFile = ThumbnailTimelinePolicy.cacheId("tt1", url, source("ABC", 1, ""))
        assertNotEquals(fallback, namedFile)
        assertNotEquals(namedFile, indexedFile)
        assertEquals(indexedFile, ThumbnailTimelinePolicy.cacheId("tt1", "https://example.com/renewed", source("abc", 1, "")))
        assertTrue(ThumbnailTimelinePolicy.belongsTo(indexedFile, "tt1"))
        assertTrue(ThumbnailTimelinePolicy.belongsTo("tt1", "tt1"))
        assertFalse(ThumbnailTimelinePolicy.belongsTo("tt10", "tt1"))
        assertTrue(ThumbnailTimelinePolicy.isSourceScoped(indexedFile))
        assertFalse(ThumbnailTimelinePolicy.isSourceScoped("tt1"))
    }
    @Test fun thumbnailGridHandlesHalfIntervalNegativeAndOverflowBoundaries() {
        assertEquals(0L, ThumbnailTimelinePolicy.gridPosition(-1, 30))
        assertEquals(0L, ThumbnailTimelinePolicy.gridPosition(14_999, 30))
        assertEquals(30_000L, ThumbnailTimelinePolicy.gridPosition(15_000, 30))
        assertEquals(1000L, ThumbnailTimelinePolicy.gridPosition(500, 0))
        assertEquals(Long.MAX_VALUE / 1000 * 1000, ThumbnailTimelinePolicy.gridPosition(Long.MAX_VALUE, 1))
    }
    @Test fun captureRequiresCorrectTimestampAndFinishedSeek() {
        assertFalse(ThumbnailTimelinePolicy.captureMatches(-1, 0))
        assertFalse(ThumbnailTimelinePolicy.captureMatches(1000, null))
        assertFalse(ThumbnailTimelinePolicy.captureMatches(1000, -1))
        assertTrue(ThumbnailTimelinePolicy.captureMatches(1000, 900))
        assertTrue(ThumbnailTimelinePolicy.captureMatches(1000, 1100))
        assertFalse(ThumbnailTimelinePolicy.captureMatches(1000, 1101))
        assertFalse(ThumbnailTimelinePolicy.seekReady(1000, 1000, true, true))
        assertFalse(ThumbnailTimelinePolicy.seekReady(1000, 1000, false, false))
        assertFalse(ThumbnailTimelinePolicy.seekReady(1000, 1200, false, true))
        assertTrue(ThumbnailTimelinePolicy.seekReady(1000, 1000, false, true))
    }
    @Test fun everyPlaybackAndThermalGateCanStopBackgroundWork() {
        fun canRun(disabledGate: Int = -1, unknown: Boolean = false, buffered: Long = 1000, thermal: Int = 0) = ThumbnailWorkPolicy.canRun(
            isPlaying = disabledGate != 0, isReady = disabledGate != 1,
            hasRenderedFirstFrame = disabledGate != 2, isBuffering = disabledGate == 3,
            isSeeking = disabledGate == 4, hasPlaybackError = disabledGate == 5,
            playbackUnderStress = disabledGate == 6, bufferIsUnknown = unknown,
            bufferedAheadMs = buffered, minimumBufferedAheadMs = 1000,
            thermalStatus = thermal, moderateThermalStatus = 2)
        assertTrue(canRun())
        for (gate in 0..6) assertFalse("gate=$gate", canRun(gate))
        assertFalse(canRun(buffered = 999))
        assertTrue(canRun(unknown = true, buffered = 0))
        assertFalse(canRun(thermal = 2))
        assertFalse(canRun(thermal = 3))
    }
    @Test fun thumbnailWorkerPssMustBeKnownAndWithinTheMemoryBudget() {
        val ample = 512L * 1024 * 1024
        assertFalse(SeekThumbnailWorkerMemoryPolicy.hasHeadroom(false, ample, 0, -1))
        assertTrue(SeekThumbnailWorkerMemoryPolicy.hasHeadroom(false, ample, 0, 0))
        assertTrue(SeekThumbnailWorkerMemoryPolicy.hasHeadroom(false, ample, 0, 256 * 1024))
        assertFalse(SeekThumbnailWorkerMemoryPolicy.hasHeadroom(false, ample, 0, 256 * 1024 + 1))
        assertFalse(SeekThumbnailWorkerMemoryPolicy.hasHeadroom(true, ample, 0, 0))
        assertFalse(SeekThumbnailWorkerMemoryPolicy.hasHeadroom(false, 128L * 1024 * 1024 - 1, -1, 0))
    }
}
