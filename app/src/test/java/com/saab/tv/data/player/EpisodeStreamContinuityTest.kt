package com.saab.tv.data.player

import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.*
import org.junit.Test

class EpisodeStreamContinuityTest {
    @Test fun changedReleaseIsPreferredOverTopRankedRelease() {
        fun stream(file: String, url: String) = Stream(url = url, addonTransportUrl = "addon",
            behaviorHints = StreamBehaviorHints(filename = file))
        val previous = stream("Show.S01E01.1080p.WEB-GROUP.mkv", "https://cdn/old")
        val top = stream("Show.S01E02.2160p.WEB-OTHER.mkv", "https://cdn/top")
        val sameRelease = stream("Show.S01E02.1080p.WEB-GROUP.mkv", "https://cdn/new")
        assertEquals(sameRelease, EpisodeStreamContinuity.findContinuation(previous, listOf(top, sameRelease)))
        assertNull(EpisodeStreamContinuity.findContinuation(previous, listOf(top)))
    }

    @Test fun sameTorrentWinsDespiteDifferentEpisodeFile() {
        val previous = Stream(infoHash = "ABC", fileIdx = 1)
        val next = Stream(infoHash = "abc", fileIdx = 2)
        assertEquals(next, EpisodeStreamContinuity.findContinuation(previous, listOf(Stream(infoHash = "other"), next)))
    }
}
