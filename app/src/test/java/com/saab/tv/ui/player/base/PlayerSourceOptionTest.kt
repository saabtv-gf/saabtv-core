package com.saab.tv.ui.player.base

import com.saab.tv.data.stream.StreamDisplayFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSourceOptionTest {
    @Test fun sourcePanelRetainsTorBoxEvidence() {
        val source = PlayerSourceOption(
            id = "source", url = "https://example.com/video", label = "Movie",
            name = "Torrentio", title = "Movie 1080p", seeders = 23,
            torBoxChecked = true, torBoxCached = true, torBoxSeeders = 23
        ).toStream()

        assertEquals(true, source.torBoxChecked)
        assertEquals(true, source.torBoxCached)
        assertEquals(23, source.torBoxSeeders)
        assertTrue(StreamDisplayFormatter.format("Movie", source).details.contains("TorBox Cached"))
        assertTrue(StreamDisplayFormatter.format("Movie", source).details.contains("23 Seeders · TorBox"))
    }
}
