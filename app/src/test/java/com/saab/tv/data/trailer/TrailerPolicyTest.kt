package com.saab.tv.data.trailer

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaTrailer
import com.saab.tv.data.model.stremio.MetaTrailerStream
import org.junit.Assert.*
import org.junit.Test

class TrailerPolicyTest {
    @Test fun rejectedSignedUrlsAdvanceWithoutRepeatedRetries() {
        listOf(400, 401, 403, 404, 410, 416).forEach { assertTrue(TrailerPolicy.terminalHttpStatus(it)) }
        listOf(200, 408, 429, 500, 502, 503).forEach { assertFalse(TrailerPolicy.terminalHttpStatus(it)) }
    }
    private val id = "dQw4w9WgXcQ"
    @Test fun dashManifestDoesNotCapFourKAt1080() {
        assertEquals(2160, TrailerPolicy.manifestHeight("<MPD><Representation height='1080'/><Representation height=\"2160\"/></MPD>"))
    }
    @Test fun malformedManifestCannotInventAResolution() {
        assertEquals(0, TrailerPolicy.manifestHeight("<MPD><Representation height='unknown'/></MPD>"))
    }

    @Test fun acceptsCinemetaIdsAndYoutubeLinks() {
        for (input in listOf(id, " https://youtu.be/$id?t=10 ",
            "https://www.youtube.com/watch?v=$id&hl=en", "youtube.com/embed/$id",
            "https://www.youtube.com/shorts/$id", "https://youtube-nocookie.com/embed/$id")) {
            assertEquals(input, id, TrailerPolicy.videoId(input))
        }
    }
    @Test fun rejectsUntrustedAndMalformedLinks() {
        for (input in listOf("bad-id", "https://evilyoutu.be/$id",
            "https://youtube.com.evil.test/watch?v=$id", "https://evil.test/?v=$id",
            "javascript:$id", "file://youtube.com/watch?v=$id")) {
            assertNull(input, TrailerPolicy.videoId(input))
        }
    }
    @Test fun fourKAlwaysPrecedesLowerResolutionRegardlessOfClient() {
        val sorted = TrailerPolicy.rank(listOf(
            TrailerPlaybackVariant("android1080", height = 1080),
            TrailerPlaybackVariant("ios4k", height = 2160),
            TrailerPlaybackVariant("vision720", height = 720)))
        assertEquals(listOf(2160, 1080, 720), sorted.map { it.height })
    }
    @Test fun keepsAlternativeAudioAndDropsDuplicatePairs() {
        assertEquals(2, TrailerPolicy.rank(listOf(
            TrailerPlaybackVariant("video", "audio1"),
            TrailerPlaybackVariant("video", "audio1"),
            TrailerPlaybackVariant("video", "audio2"))).size)
    }
    @Test fun cinemetaYtIdUsesMetadataTitle() {
        assertEquals(id to "Official Trailer", TrailerPolicy.metadataTrailer(MetaItem(
            name = "Movie", trailerStreams = listOf(MetaTrailerStream(title = "Official Trailer", ytId = id)))))
    }
    @Test fun skipsInvalidStreamsAndUsesLegacyTrailer() {
        assertEquals(id to "Movie Trailer", TrailerPolicy.metadataTrailer(MetaItem(
            name = "Movie", trailerStreams = listOf(MetaTrailerStream(url = "https://evil.test")),
            trailers = listOf(MetaTrailer(source = id, type = "Trailer")))))
    }
}
