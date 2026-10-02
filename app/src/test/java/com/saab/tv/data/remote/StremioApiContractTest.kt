package com.saab.tv.data.remote

import com.saab.tv.domain.normalizeEpisodeList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Offline HTTP fixtures: never contacts Cinemeta or a user's configured addon. */
class StremioApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: StremioApiService
    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(StremioApiService::class.java)
    }
    @After fun tearDown() { server.shutdown() }
    private fun enqueue(json: String) { server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json")) }

    @Test fun manifestSupportsStringAndObjectResourcesAndCatalogExtras() = runBlocking {
        enqueue("""{"id":"addon","name":"Test","version":"1","description":"desc","logo":"logo",
          "resources":["meta",{"name":"stream","types":["movie"]}],"types":["movie","series"],
          "idPrefixes":["tt"],"catalogs":[{"type":"movie","id":"popular","name":"Popular",
          "extra":[{"name":"genre","isRequired":false,"options":["Drama"]},{"name":"search","isRequired":true}]}]}""")
        val manifest = api.getManifest(server.url("/manifest.json").toString())
        assertEquals("addon", manifest.id); assertEquals("Test", manifest.name); assertEquals("1", manifest.version)
        assertEquals("desc", manifest.description); assertEquals("logo", manifest.logo)
        assertEquals("meta", manifest.resources!![0].asString)
        assertEquals("stream", manifest.resources!![1].asJsonObject["name"].asString)
        assertEquals(listOf("movie", "series"), manifest.types); assertEquals(listOf("tt"), manifest.idPrefixes)
        val catalog = manifest.catalogs!!.single()
        assertEquals("movie", catalog.type); assertEquals("popular", catalog.id); assertEquals("Popular", catalog.name)
        assertEquals(listOf("Drama"), catalog.extra!![0].options)
        assertFalse(catalog.extra!![0].isRequired); assertTrue(catalog.extra!![1].isRequired)
        assertEquals("/manifest.json", server.takeRequest().path)
    }

    @Test fun catalogPreservesRatingAndArtworkRatherThanReplacingThem() = runBlocking {
        enqueue("""{"metas":[{"id":"tt1","type":"movie","name":"Title","poster":"poster",
          "background":"backdrop","logo":"logo","description":"synopsis","imdbRating":"8.7",
          "releaseInfo":"2026","runtime":"120 min","genres":["Drama"]}],"hasMore":true}""")
        val response = api.getCatalog(server.url("/catalog/movie/top.json").toString())
        val item = response.metas!!.single()
        assertEquals("tt1", item.id); assertEquals("movie", item.type); assertEquals("Title", item.name)
        assertEquals("8.7", item.imdbRating); assertEquals("poster", item.poster); assertEquals("backdrop", item.background)
        assertEquals("logo", item.logo); assertEquals("synopsis", item.description); assertEquals("2026", item.releaseInfo)
        assertEquals("120 min", item.runtime); assertEquals(listOf("Drama"), item.genres); assertEquals(true, response.hasMore)
        assertNull(item.resumePlaybackId); assertNull(item.addonBaseUrl); assertFalse(item.hasNewEpisode)
    }

    @Test fun seriesResponsePreservesEpisodeNumbersAndAlternateNameField() = runBlocking {
        enqueue("""{"meta":{"id":"tt1","type":"series","name":"Series","videos":[
          {"id":"native2","name":"Second","season":2,"episode":2,"released":"2026-01-02",
          "thumbnail":"thumb","overview":"description"},
          {"id":"native1","title":"First","season":2,"episode":1},
          {"id":"special","title":"Special","season":0,"episode":101}],
          "trailers":[{"source":"youtube-id","type":"Trailer"}],
          "trailerStreams":[{"title":"Trailer","ytId":"youtube-id","externalUrl":"external","url":"stream"}]}}""")
        val item = api.getMeta(server.url("/meta/series/tt1.json").toString()).meta
        val episodes = normalizeEpisodeList(item.videos!!)
        assertEquals(listOf(1, 2), episodes.map { it.episode }); assertEquals(listOf(2, 2), episodes.map { it.season })
        assertEquals("First", episodes[0].title); assertEquals("Second", episodes[1].title)
        assertEquals("native2", episodes[1].id); assertEquals("2026-01-02", episodes[1].released)
        assertEquals("thumb", episodes[1].thumbnail); assertEquals("description", episodes[1].overview)
        assertEquals("youtube-id", item.trailers!!.single().source); assertEquals("Trailer", item.trailers!!.single().type)
        val trailer = item.trailerStreams!!.single()
        assertEquals("Trailer", trailer.title); assertEquals("youtube-id", trailer.ytId)
        assertEquals("external", trailer.externalUrl); assertEquals("stream", trailer.url)
    }

    @Test fun streamResponsePreservesAvailabilityFileAndEmbeddedSubtitles() = runBlocking {
        enqueue("""{"streams":[{"name":"Torrentio","title":"Movie","description":"desc","url":"https://video",
          "infoHash":"abc","fileIdx":3,"sources":["tracker"],"seeders":42,"torBoxCached":true,
          "torBoxSeeders":9,"torBoxChecked":true,"addonTransportUrl":"https://addon",
          "behaviorHints":{"bingeGroup":"group","filename":"movie.mkv","videoSize":12000000000,"seeders":43},
          "subtitles":[{"id":"sub","lang":"eng","url":"https://sub","name":"Embedded","transportUrl":"https://addon"}]}]}""")
        val stream = api.getStreams(server.url("/stream/movie/tt1.json").toString()).streams!!.single()
        assertEquals("Torrentio", stream.name); assertEquals("Movie", stream.title); assertEquals("desc", stream.description)
        assertEquals("https://video", stream.url); assertEquals("abc", stream.infoHash); assertEquals(3, stream.fileIdx)
        assertEquals(listOf("tracker"), stream.sources); assertEquals(42, stream.seeders)
        assertEquals(true, stream.torBoxCached); assertEquals(9, stream.torBoxSeeders); assertTrue(stream.torBoxChecked)
        assertEquals("https://addon", stream.addonTransportUrl)
        val hints = stream.behaviorHints!!
        assertEquals("group", hints.bingeGroup); assertEquals("movie.mkv", hints.filename)
        assertEquals(12000000000L, hints.videoSize); assertEquals(43, hints.seeders)
        val sub = stream.subtitles!!.single()
        assertEquals("sub", sub.id); assertEquals("eng", sub.lang); assertEquals("https://sub", sub.url)
        assertEquals("Embedded", sub.name); assertEquals("https://addon", sub.transportUrl)
    }

    @Test fun subtitleResponsePreservesReleaseNameForMatching() = runBlocking {
        enqueue("""{"subtitles":[{"id":"s1","lang":"en","url":"https://sub","name":"Movie.1080p.WEB-DL"}]}""")
        val sub = api.getSubtitles(server.url("/subtitles/movie/tt1.json").toString()).subtitles.single()
        assertEquals("s1", sub.id); assertEquals("en", sub.lang); assertEquals("https://sub", sub.url)
        assertEquals("Movie.1080p.WEB-DL", sub.name)
    }

    @Test fun emptyCatalogAndStreamResponsesRemainEmpty() = runBlocking {
        enqueue("""{"metas":[],"hasMore":false}""")
        assertTrue(api.getCatalog(server.url("/catalog.json").toString()).metas!!.isEmpty())
        enqueue("""{"streams":[]}""")
        assertTrue(api.getStreams(server.url("/streams.json").toString()).streams!!.isEmpty())
        enqueue("""{"subtitles":[]}""")
        assertTrue(api.getSubtitles(server.url("/subtitles.json").toString()).subtitles.isEmpty())
    }

    @Test fun nonSuccessStatusIsNotSilentlyDecodedAsMetadata() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        try { api.getMeta(server.url("/meta.json").toString()); fail("503 must fail") }
        catch (error: HttpException) { assertEquals(503, error.code()) }
    }

    @Test fun malformedJsonFailsInsteadOfProducingAnEmptySuccessfulResult() = runBlocking {
        enqueue("{invalid json")
        try { api.getCatalog(server.url("/catalog.json").toString()); fail("Malformed response must fail") }
        catch (error: java.io.IOException) { assertNotNull(error.message) }
    }
}
