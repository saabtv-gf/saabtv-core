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

    @Test fun sourcePanelRetainsSeasonPackEpisodeFileIndex() {
        val url = "magnet:?xt=urn:btih:abc"
        val option = PlayerSourceOption(id = sourceOptionId(url, 1, "Show.S01E02.mkv"), url = url,
            label = "Show S1E2", fileIdx = 1, fileName = "Show.S01E02.mkv")
        val rebuilt = option.toStream()
        assertEquals(1, rebuilt.fileIdx)
        assertEquals(option.id, sourceOptionId(rebuilt.url.orEmpty(), rebuilt.fileIdx ?: -1,
            rebuilt.behaviorHints?.filename.orEmpty()))
    }
}
