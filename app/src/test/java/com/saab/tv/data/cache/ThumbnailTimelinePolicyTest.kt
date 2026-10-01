package com.saab.tv.data.cache

import com.saab.tv.ui.player.base.PlayerSourceOption
import org.junit.Assert.*
import org.junit.Test

class ThumbnailTimelinePolicyTest {
    @Test fun rejectsStaleAndDistantCaptures() {
        assertFalse(ThumbnailTimelinePolicy.captureMatches(30_000, 10_000))
        assertFalse(ThumbnailTimelinePolicy.captureMatches(30_000, null))
        assertTrue(ThumbnailTimelinePolicy.captureMatches(30_000, 30_042))
        assertFalse(ThumbnailTimelinePolicy.captureMatches(30_000, 30_101))
    }
    @Test fun waitsForDecodedRestartNotJustPosition() {
        assertFalse(ThumbnailTimelinePolicy.seekReady(30_000, 30_000, false, false))
        assertFalse(ThumbnailTimelinePolicy.seekReady(30_000, 30_000, true, true))
        assertTrue(ThumbnailTimelinePolicy.seekReady(30_000, 30_042, false, true))
    }
    @Test fun backwardAndZeroSeeksRejectOldFrames() {
        assertFalse(ThumbnailTimelinePolicy.seekReady(10_000, 60_000, false, true))
        assertTrue(ThumbnailTimelinePolicy.seekReady(0, 0, false, true))
    }
    @Test fun highlightedTimestampSharesIntervalGrid() {
        assertEquals(60_000, ThumbnailTimelinePolicy.gridPosition(49_000, 30))
        assertEquals(30_000, ThumbnailTimelinePolicy.gridPosition(42_000, 30))
        assertEquals(20_000, ThumbnailTimelinePolicy.gridPosition(17_000, 20))
        assertEquals(0, ThumbnailTimelinePolicy.gridPosition(-1, 30))
    }
    @Test fun differentThumbnailFilesGetDifferentCacheScopes() {
        assertNotEquals(ThumbnailTimelinePolicy.cacheId("tt1", "https://a", null),
            ThumbnailTimelinePolicy.cacheId("tt1", "https://b", null))
    }
    @Test fun knownFileIdentitySurvivesUrlRenewalButSeparatesEpisodes() {
        val file = PlayerSourceOption("a", "https://a", "A", infoHash = "ABC", fileIdx = 1, fileName = "e1.mkv")
        val id = ThumbnailTimelinePolicy.cacheId("tt1", file.url, file)
        assertEquals(id, ThumbnailTimelinePolicy.cacheId("tt1", "https://renewed", file))
        assertNotEquals(id, ThumbnailTimelinePolicy.cacheId("tt1", file.url, file.copy(fileIdx = 2)))
        assertTrue(ThumbnailTimelinePolicy.belongsTo(id, "tt1"))
        assertFalse(ThumbnailTimelinePolicy.belongsTo(id, "tt10"))
    }
}
