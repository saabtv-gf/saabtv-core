package com.saab.tv.data.torrent

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the TorrServer wire contract with deterministic server replies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TorrServerApiRegressionTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TorrServerApi
    private val hash = "a".repeat(40)
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=fixture"

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = TorrServerApi(server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun addTorrentPostsExpectedPayloadAndReturnsJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"hash":"$hash"}"""))

        val body = api.addTorrent(magnet, "Fixture title")
        val request = server.takeRequest()

        assertEquals("/torrents", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val payload = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("add", payload.get("action").asString)
        assertEquals(magnet, payload.get("link").asString)
        assertEquals("Fixture title", payload.get("title").asString)
        assertEquals(false, payload.get("save_to_db").asBoolean)
        assertEquals(hash, body.get("hash").asString)
    }

    @Test fun addTorrentReportsHttpFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))

        val failure = runCatching { api.addTorrent(magnet) }.exceptionOrNull()

        assertEquals("Failed to add torrent: HTTP 502", failure?.message)
    }

    @Test fun statsUsesInfoHashAndParsesEveryCounter() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"stat":3,"active_peers":4,"total_peers":8,"connected_seeders":2,"download_speed":1234,"upload_speed":56,"bytes_read":789,"torrent_size":9000,"preloaded_bytes":6000}"""
        ))

        val stats = api.getTorrentStats(magnet)
        val request = server.takeRequest()
        val payload = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("$hash", payload.get("hash").asString)
        assertEquals(3, stats.stat)
        assertEquals(4, stats.activePeers)
        assertEquals(8, stats.totalPeers)
        assertEquals(2, stats.connectedSeeders)
        assertEquals(1234L, stats.downloadSpeed)
        assertEquals(56L, stats.uploadSpeed)
        assertEquals(789L, stats.bytesRead)
        assertEquals(9000L, stats.torrentSize)
        assertEquals(6000L, stats.preloadedBytes)
        assertEquals("Streaming", stats.statusText())
    }

    @Test fun failedStatsRequestReturnsSafeEmptyStats() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(TorrentStats(), api.getTorrentStats(magnet))
    }

    @Test fun statsWithOmittedCountersUsesZeroDefaults() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(TorrentStats(), api.getTorrentStats(magnet))
    }

    @Test fun statusTextCoversAllServerStatesAndUnknownState() {
        assertEquals("Connecting to peers...", TorrentStats(stat = 0).statusText())
        assertEquals("Fetching metadata...", TorrentStats(stat = 1).statusText())
        assertEquals("Buffering...", TorrentStats(stat = 2).statusText())
        assertEquals("Streaming", TorrentStats(stat = 3).statusText())
        assertEquals("Stopped", TorrentStats(stat = 4).statusText())
        assertEquals("Connecting...", TorrentStats(stat = 99).statusText())
    }

    @Test fun dropTorrentUsesHashAndIgnoresServerFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        api.dropTorrent(magnet)

        val request = server.takeRequest()
        val payload = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("drop", payload.get("action").asString)
        assertEquals(hash, payload.get("hash").asString)
    }

    @Test fun dropTorrentIgnoresTransportFailure() = runBlocking {
        server.shutdown()

        api.dropTorrent(magnet)
    }

    @Test fun streamUrlEncodesMagnetAndPreservesSelectedFileIndex() {
        val url = api.getStreamUrl(magnet, 7)

        assertTrue(url.startsWith(server.url("/").toString().trimEnd('/')))
        assertTrue(url.contains("link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hash"))
        assertTrue(url.endsWith("&index=7&play"))
    }

    @Test fun fileListParsesEntriesAndAppliesSafeDefaults() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"file_stats":[{"id":4,"path":"Show/S01E02.mkv","length":123456},{"path":"Show/subs.srt"}]}"""
        ))

        assertEquals(
            listOf(TorrServerFile(4, "Show/S01E02.mkv", 123456), TorrServerFile(1, "Show/subs.srt", 0)),
            api.getFileList(magnet)
        )
    }

    @Test fun unsuccessfulOrMalformedFileListFailsClosedToEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(api.getFileList(magnet).isEmpty())

        server.enqueue(MockResponse().setBody("not-json"))
        assertTrue(api.getFileList(magnet).isEmpty())
    }

    @Test fun magnetWithoutInfoHashUsesTheProvidedIdentifier() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        api.getTorrentStats("plain-torrent-id")

        val payload = com.google.gson.JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("plain-torrent-id", payload.get("hash").asString)
    }
}
