package com.saab.tv.data.cache

import com.saab.tv.ui.player.base.PlayerSourceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailSourceSelectorTest {
    @Test
    fun selectsSmallSdrAvcSourceWithoutChangingPlaybackSource() {
        val playing = source("4k", "https://cdn.example/4k.mkv", 2160, 24, listOf("hevc", "hdr"))
        val preview = source("720p", "https://cdn.example/720.mkv", 720, 2, listOf("h264"))

        val result = ThumbnailSourceSelector.select(playing.url, listOf(playing, preview))

        assertEquals(preview.url, result?.source?.url)
    }

    @Test
    fun fastAvcPoolWinsBeforeLeastSizeComparison() {
        val hevc480 = source("480 HEVC", "https://cdn.example/480.mkv", 480, 1, listOf("hevc"))
        val avc720 = source("720 AVC", "https://cdn.example/720.mkv", 720, 2, listOf("h264"))

        assertEquals(
            avc720.url,
            ThumbnailSourceSelector.select(avc720.url, listOf(hevc480, avc720))?.source?.url
        )
    }

    @Test
    fun doesNotMixDifferentEditions() {
        val theatrical = source("Movie 4K", "https://cdn.example/main.mkv", 2160, 20, listOf("hevc"))
        val extended = source("Movie Extended 720p", "https://cdn.example/extended.mkv", 720, 2, listOf("h264"))

        assertEquals(
            theatrical.url,
            ThumbnailSourceSelector.select(theatrical.url, listOf(theatrical, extended))?.source?.url
        )
    }

    @Test
    fun rejectsLocalAndCamSources() {
        val local = source("720p", "http://127.0.0.1:8090/video", 720, 2, listOf("h264"))
        val cam = source("CAM", "https://cdn.example/cam.mkv", 0, 1, listOf("h264"))

        assertNull(ThumbnailSourceSelector.select(local.url, listOf(local, cam)))
    }

    @Test
    fun rejectsTinyAlternateWhenItsSeederCountIsInsufficient() {
        val playing = source("Movie 4K", "https://cdn.example/4k.mkv", 2160, 20, listOf("hevc"), 80)
        val deadTiny = source("Movie 480p", "https://cdn.example/dead.mkv", 480, 1, listOf("h264"), 1)
        val healthy = source("Movie 720p", "https://cdn.example/healthy.mkv", 720, 3, listOf("h264"), 25)

        assertEquals(
            healthy.url,
            ThumbnailSourceSelector.select(playing.url, listOf(playing, deadTiny, healthy))?.source?.url
        )
    }

    @Test
    fun fallsBackToKnownPlayingStreamWhenNoAlternateIsReliable() {
        val playing = source("Movie 4K", "https://cdn.example/4k.mkv", 2160, 20, listOf("hevc"), 50)
        val unknown = source("Movie 720p", "https://cdn.example/unknown.mkv", 720, 2, listOf("h264"), null)
        val dead = source("Movie 480p", "https://cdn.example/dead.mkv", 480, 1, listOf("h264"), 0)

        assertEquals(
            playing.url,
            ThumbnailSourceSelector.select(playing.url, listOf(playing, unknown, dead))?.source?.url
        )
    }

    @Test
    fun explicitlyCachedSourceDoesNotRequireLiveSeeders() {
        val playing = source("Movie 4K", "https://cdn.example/4k.mkv", 2160, 20, listOf("hevc"), 40)
        val cached = source("Movie 720p Cached", "https://cdn.example/cached.mkv", 720, 2, listOf("h264"), 0)

        assertEquals(
            cached.url,
            ThumbnailSourceSelector.select(playing.url, listOf(playing, cached))?.source?.url
        )
    }

    private fun source(
        label: String,
        url: String,
        height: Int,
        sizeGb: Long,
        formats: List<String>,
        seeders: Int? = 100
    ) = PlayerSourceOption(
        id = url,
        url = url,
        label = label,
        qualityHeight = height,
        videoSize = sizeGb * 1_073_741_824L,
        seeders = seeders,
        formats = formats
    )
}
