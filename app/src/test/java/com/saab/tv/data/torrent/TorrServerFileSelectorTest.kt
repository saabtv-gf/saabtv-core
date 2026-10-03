package com.saab.tv.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrServerFileSelectorTest {
    private val files = listOf(
        TorrServerFile(1, "Show/subtitles.srt", 50_000_000),
        TorrServerFile(2, "Show/Show.S01E01.mkv", 1_000_000_000),
        TorrServerFile(7, "Show/Show.S01E02.MP4", 900_000_000),
        TorrServerFile(8, "Show/bonus.webm", 200_000_000)
    )

    @Test fun exactFilenameHintWinsEvenWhenTorrentOrderingDiffers() {
        assertEquals(files[2], TorrServerFileSelector.resolve(files, 0, "Show.S01E02.MP4"))
    }

    @Test fun filenameHintMatchesLeafNameCaseInsensitively() {
        assertEquals(files[1], TorrServerFileSelector.resolve(files, -1, "show.s01e01.mkv"))
    }

    @Test fun addonIndexMapsToTorrServerOneBasedIdBeforeListPosition() {
        assertEquals(files[2], TorrServerFileSelector.resolve(files, 6))
    }

    @Test fun addonIndexFallsBackToVideoAtPositionWhenIdsAreNotOffset() {
        val reorderedIds = listOf(files[0], files[1].copy(id = 20), files[2].copy(id = 21))
        assertEquals(reorderedIds[1], TorrServerFileSelector.resolve(reorderedIds, 1))
    }

    @Test fun largestVideoWinsWhenHintIsMissingAndSubtitleIsLarger() {
        assertEquals(files[1], TorrServerFileSelector.resolve(files, -1, "missing.mkv"))
    }

    @Test fun largestFileIsLastResortWhenNoVideoExtensionIsRecognized() {
        val nonVideo = listOf(TorrServerFile(1, "readme.txt", 10), TorrServerFile(2, "captions.srt", 100))
        assertEquals(nonVideo[1], TorrServerFileSelector.resolve(nonVideo, 0))
    }

    @Test fun emptyMetadataDoesNotInventAFile() {
        assertNull(TorrServerFileSelector.resolve(emptyList(), -1))
    }
}
