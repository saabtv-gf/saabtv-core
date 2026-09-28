package com.saab.tv

import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourcePayloadTest {
    @Test
    fun seasonPackEpisodesWithSameMagnetKeepDistinctFileSelections() {
        val magnet = "magnet:?xt=urn:btih:${"a".repeat(40)}"
        val first = Stream(url = magnet, fileIdx = 0,
            behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.mkv"))
        val second = Stream(url = magnet, fileIdx = 1,
            behaviorHints = StreamBehaviorHints(filename = "Show.S01E02.mkv"))

        val payload = buildSourcePayload(listOf(first, second), "Show")

        assertEquals(2, payload.size)
        assertEquals(listOf(0, 1), payload.map { it.fileIdx })
        assertEquals(2, payload.map { it.id }.distinct().size)
        assertEquals(second, findTorrentSwitchSource(listOf(first, second), magnet, 1,
            "Show.S01E02.mkv"))
        assertEquals(null, findTorrentSwitchSource(listOf(first, second), magnet, 2,
            "Show.S01E03.mkv"))
    }

    @Test
    fun payloadPreservesRankOrderAndScoringMetadata() {
        val higherRanked = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p 8 GB Seeds: 510",
            url = "https://example.com/high",
            infoHash = "high-hash",
            torBoxChecked = true,
            torBoxCached = true,
            torBoxSeeders = 73,
            behaviorHints = StreamBehaviorHints(
                filename = "Movie.High.mkv",
                videoSize = 8_589_934_592L
            )
        )
        val lowerRanked = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p 8 GB Seeds: 500",
            url = "https://example.com/low",
            infoHash = "low-hash",
            behaviorHints = StreamBehaviorHints(
                filename = "Movie.Low.mkv",
                videoSize = 8_589_934_592L
            )
        )

        val payload = buildSourcePayload(listOf(higherRanked, lowerRanked), "Movie")

        assertEquals(listOf("https://example.com/high", "https://example.com/low"), payload.map { it.url })
        assertEquals("high-hash", payload.first().infoHash)
        assertEquals(8_589_934_592L, payload.first().videoSize)
        assertEquals("Movie.High.mkv", payload.first().fileName)
        assertEquals(true, payload.first().torBoxChecked)
        assertEquals(true, payload.first().torBoxCached)
        assertEquals(73, payload.first().torBoxSeeders)
    }
}
