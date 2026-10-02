package com.saab.tv.data.trailer

import org.junit.Assert.*
import org.junit.Test

class YtDlpTrailerFormatsTest {
    @Test fun ranksFourKAbove720AndMergesSeparateAudio() {
        val result = YtDlpTrailerFormats.parse("""{"formats":[
          {"url":"https://r.googlevideo.com/audio","protocol":"https","vcodec":"none","acodec":"aac","abr":128},
          {"url":"https://r.googlevideo.com/720","protocol":"https","height":720,"vcodec":"avc1","acodec":"aac"},
          {"url":"https://r.googlevideo.com/4k","protocol":"https","height":2160,"vcodec":"vp9","acodec":"none"}
        ]}""")
        assertEquals(listOf(2160, 720), result.map { it.height })
        assertEquals("https://r.googlevideo.com/audio", result.first().audioUrl)
        assertNull(result.last().audioUrl)
    }

    @Test fun rejectsDrmFragmentedAndUntrustedUrls() {
        val result = YtDlpTrailerFormats.parse("""{"formats":[
          {"url":"https://r.googlevideo.com/drm","protocol":"https","height":2160,"vcodec":"vp9","acodec":"aac","has_drm":true},
          {"url":"https://r.googlevideo.com/dash","protocol":"https","height":2160,"vcodec":"vp9","acodec":"aac","fragments":[]},
          {"url":"https://googlevideo.com.attacker.invalid/video","protocol":"https","height":2160,"vcodec":"vp9","acodec":"aac"},
          {"url":"http://r.googlevideo.com/video","protocol":"https","height":2160,"vcodec":"vp9","acodec":"aac"}
        ]}""")
        assertTrue(result.isEmpty())
    }

    @Test fun videoOnlyNeedsAudioAndNullMetadataIsHandled() {
        assertTrue(YtDlpTrailerFormats.parse("""{"formats":[{"url":"https://r.googlevideo.com/video","protocol":"https","height":2160,"vcodec":"vp9","acodec":"none","has_drm":null}]}""").isEmpty())
    }
}
