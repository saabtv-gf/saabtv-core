package com.saab.tv

import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourcePayloadTest {
    @Test
    fun payloadPreservesRankOrderAndScoringMetadata() {
        val higherRanked = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p 8 GB Seeds: 510",
            url = "https://example.com/high",
            infoHash = "high-hash",
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
    }
}
