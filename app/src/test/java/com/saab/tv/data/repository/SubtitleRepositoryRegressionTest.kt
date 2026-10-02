package com.saab.tv.data.repository

import android.app.Application
import androidx.room.Room
import com.google.gson.JsonParser
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.remote.StremioApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubtitleRepositoryRegressionTest {
    private class Api : StremioApiService {
        val manifests = mutableMapOf<String, Manifest>()
        val subtitles = mutableMapOf<String, List<StreamSubtitle>>()
        val manifestCalls = Collections.synchronizedList(mutableListOf<String>())
        val subtitleCalls = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun getManifest(url: String): Manifest {
            manifestCalls += url
            return manifests[url] ?: throw IOException("Manifest unavailable")
        }
        override suspend fun getSubtitles(url: String): SubtitleResponse {
            subtitleCalls += url
            return SubtitleResponse(subtitles[url].orEmpty())
        }
        override suspend fun getCatalog(url: String): CatalogResponse = error("Not a catalog test")
        override suspend fun getMeta(url: String): MetaResponse = error("Not a metadata test")
        override suspend fun getStreams(url: String): StreamResponse = error("Not a stream test")
    }
    private lateinit var db: SaabTvDatabase
    private lateinit var api: Api
    private lateinit var repository: SubtitleRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        api = Api(); repository = SubtitleRepository(api, db.addonDao())
    }
    @After fun tearDown() { db.close() }
    private suspend fun addon(base: String = "https://addon.test", resources: String = "[\"subtitles\"]", enabled: Boolean = true) {
        db.addonDao().insertAddon(AddonEntity(base, "id", "Provider", "1", null, null, isEnabled = enabled, nickname = "Custom"))
        api.manifests["$base/manifest.json"] = Manifest(resources = JsonParser.parseString(resources).asJsonArray.toList())
    }

    @Test fun officialSubtitlesAreAvailableWithoutInstalledAddons() = runBlocking {
        val url = "https://opensubtitles-v3.strem.io/subtitles/movie/tt123.json"
        api.subtitles[url] = listOf(StreamSubtitle("s1", " EN_us ", "https://sub.test/one", "release"))
        val sub = repository.getSubtitles(" MOVIE ", " tt123 ").single()
        assertEquals("s1", sub.id); assertEquals("en-us", sub.lang); assertEquals("OpenSubtitles", sub.addonName)
        assertEquals("release", sub.releaseName); assertEquals("https://sub.test/one", sub.url)
        assertEquals(listOf(url), api.subtitleCalls)
    }
    @Test fun unsupportedOfficialIdsAndTypesDoNotSendRequests() = runBlocking {
        listOf("movie" to "custom-id", "channel" to "tt123", "series" to "tt123", "series" to ":1:2",
            "series" to "tt123:x:2", "series" to "tt123:1:x").forEach { (type, id) ->
            assertTrue(repository.getSubtitles(type, id).isEmpty())
        }
        assertTrue(api.subtitleCalls.isEmpty())
    }
    @Test fun episodeExtraParametersAreEncodedAndInvalidOptionalValuesOmitted() = runBlocking {
        repository.getSubtitles("series", "tt123:2:3", " hash+value ", 123, " Movie Name.mkv ")
        assertEquals(listOf("https://opensubtitles-v3.strem.io/subtitles/series/tt123:2:3/videoHash=hash%2Bvalue&videoSize=123&filename=Movie%20Name.mkv.json"), api.subtitleCalls)
        api.subtitleCalls.clear()
        repository.getSubtitles("movie", "tt123", " ", 0, " ")
        assertEquals(listOf("https://opensubtitles-v3.strem.io/subtitles/movie/tt123.json"), api.subtitleCalls)
    }
    @Test fun capabilityRulesRestrictTypeAndPrefixAndAreCached() = runBlocking {
        addon(resources = """[{"name":"subtitles","types":[" movie ",null,1,""],"idPrefixes":["tt"]}]""")
        repository.getSubtitles("series", "tt123:1:1")
        assertFalse(api.subtitleCalls.any { it.startsWith("https://addon.test/") })
        repository.getSubtitles("movie", "custom")
        assertFalse(api.subtitleCalls.any { it.startsWith("https://addon.test/") })
        repository.getSubtitles("movie", "tt123")
        assertTrue("https://addon.test/subtitles/movie/tt123.json" in api.subtitleCalls)
        assertEquals(1, api.manifestCalls.size)
    }
    @Test fun unrelatedAndMalformedResourcesAreNotQueried() = runBlocking {
        addon(resources = """["stream",3,null,[],{}, {"name":false},{"name":"meta"}]""")
        repository.getSubtitles("movie", "tt123")
        assertFalse(api.subtitleCalls.any { it.startsWith("https://addon.test/") })
    }
    @Test fun unknownCapabilityUsesFallbackButDisabledAddonsDoNotRun() = runBlocking {
        addon(); api.manifests.clear()
        addon("https://disabled.test", enabled = false)
        repository.getSubtitles("movie", "tt123")
        assertTrue("https://addon.test/subtitles/movie/tt123.json" in api.subtitleCalls)
        assertFalse(api.manifestCalls.any { it.contains("disabled") })
        assertEquals(2, db.addonDao().getAllAddons().first().size)
    }
    @Test fun relativeUrlsAreResolvedUnsafeSchemesRejectedAndBlankIdsGenerated() = runBlocking {
        addon(resources = """[" Subtitle "]""")
        api.subtitles["https://addon.test/subtitles/movie/custom.json"] = listOf(
            StreamSubtitle(" ", " ", "/one.srt"), StreamSubtitle("id2", null, "relative/two.srt"),
            StreamSubtitle("unsafe", "en", "file:///secret"), StreamSubtitle(url = " "),
            StreamSubtitle(url = "javascript:alert(1)"))
        val result = repository.getSubtitles("movie", "custom")
        assertEquals(listOf("https://addon.test/one.srt", "https://addon.test/relative/two.srt"), result.map { it.url })
        assertTrue(result[0].id.startsWith("Custom:")); assertNull(result[0].lang); assertEquals("id2", result[1].id)
        assertTrue(result.all { it.addonName == "Custom" })
    }
    @Test fun duplicatesUseUrlAndLanguageIdentityAndBestReleaseRanksFirst() = runBlocking {
        addon()
        val matching = StreamSubtitle("best", "en", "https://sub.test/best", "Movie.1080p")
        val other = StreamSubtitle("other", "en", "https://sub.test/other", "Different")
        api.subtitles["https://addon.test/subtitles/movie/tt123/filename=Movie.1080p.mkv.json"] = listOf(other, matching)
        api.subtitles["https://opensubtitles-v3.strem.io/subtitles/movie/tt123/filename=Movie.1080p.mkv.json"] =
            listOf(matching.copy(id = "duplicate", url = "HTTPS://SUB.TEST/BEST", lang = "EN"), matching.copy(id = "telugu", lang = "te"))
        val result = repository.getSubtitles("movie", "tt123", filename = "Movie.1080p.mkv")
        assertEquals(3, result.size); assertEquals(setOf("best", "telugu"), result.take(2).map { it.id }.toSet())
        assertEquals("other", result.last().id)
    }
}
