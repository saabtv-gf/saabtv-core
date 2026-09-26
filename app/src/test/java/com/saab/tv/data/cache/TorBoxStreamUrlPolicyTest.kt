package com.saab.tv.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBoxStreamUrlPolicyTest {
    @Test
    fun addsRedirectToTorBoxRequestDownloadPermalink() {
        val input = "https://api.torbox.app/v1/api/torrents/requestdl?token=secret&torrent_id=1&file_id=2"
        val result = TorBoxStreamUrlPolicy.forThumbnailWorker(input)

        assertTrue(result.contains("redirect=true"))
        assertTrue(result.contains("token=secret"))
    }

    @Test
    fun preservesResolvedCdnUrl() {
        val input = "https://cdn.torbox.app/video/file.mkv?signature=abc"
        assertEquals(input, TorBoxStreamUrlPolicy.forThumbnailWorker(input))
        assertTrue(TorBoxStreamUrlPolicy.isTorBoxUrl(input))
    }

    @Test
    fun rejectsLocalTorrentServerForBackgroundExtraction() {
        assertFalse(TorBoxStreamUrlPolicy.isRemoteHttpStream("http://127.0.0.1:8090/stream"))
        assertTrue(TorBoxStreamUrlPolicy.isRemoteHttpStream("https://cdn.torbox.app/video.mkv"))
    }
}
