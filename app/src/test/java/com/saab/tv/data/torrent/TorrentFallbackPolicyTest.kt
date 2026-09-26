package com.saab.tv.data.torrent

import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentFallbackPolicyTest {
    @Test
    fun selectedTorrentIsFollowedByTwoNextBestDistinctTorrents() {
        val selected = Stream(infoHash = "aaa", fileIdx = 0)
        val duplicate = Stream(infoHash = "AAA", fileIdx = 0)
        val second = Stream(infoHash = "bbb", fileIdx = 0)
        val direct = Stream(url = "https://example.com/video.mkv")
        val third = Stream(infoHash = "ccc", fileIdx = 2)
        val fourth = Stream(infoHash = "ddd", fileIdx = 0)

        assertEquals(
            listOf(selected, second, third),
            TorrentFallbackPolicy.buildAttempts(
                selected,
                listOf(duplicate, second, direct, third, fourth),
                maxRetries = 2
            )
        )
    }
}
