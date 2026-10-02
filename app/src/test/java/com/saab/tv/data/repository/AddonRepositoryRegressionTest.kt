package com.saab.tv.data.repository

import android.app.Application
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.*
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.data.stream.TorBoxAvailabilityService
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
class AddonRepositoryRegressionTest {
    private class Api : StremioApiService {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val catalogs = mutableMapOf<String, CatalogResponse>()
        val metadata = mutableMapOf<String, MetaItem>()
        val streams = mutableMapOf<String, StreamResponse>()
        var manifest = Manifest(id = "addon", name = "Addon", version = "1")
        override suspend fun getCatalog(url: String): CatalogResponse { calls += url; return catalogs[url] ?: throw IOException("Not in fixture") }
        override suspend fun getMeta(url: String): MetaResponse { calls += url; return MetaResponse(metadata[url] ?: throw IOException("Not in fixture")) }
        override suspend fun getStreams(url: String): StreamResponse { calls += url; return streams[url] ?: throw IOException("Not in fixture") }
        override suspend fun getManifest(url: String): Manifest { calls += url; return manifest }
        override suspend fun getSubtitles(url: String): SubtitleResponse = error("Not a subtitle test")
    }
    private lateinit var db: SaabTvDatabase
    private lateinit var api: Api
    private lateinit var repository: AddonRepository
    private val dao get() = db.addonDao()
    private val base = "https://addon.test"
    private fun item(id: String = "tt1", type: String = "movie") = MetaItem(id, type, "Title", poster = "poster")
    private fun addon(enabled: Boolean = true, meta: Boolean = true, stream: Boolean = true) =
        AddonEntity(base, "addon", "Addon", "1", null, null, isEnabled = enabled,
            supportsMeta = meta, supportsStream = stream, typesJson = "[\"movie\",\"series\"]")
    private fun config(id: String = "top") = CatalogConfigEntity(id, base, "Addon", "movie", id, showInHome = true)
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        api = Api(); repository = AddonRepository(api, dao, TorBoxAvailabilityService(RuntimeEnvironment.getApplication()))
    }
    @After fun tearDown() { db.close() }

    @Test fun nextCatalogPageUsesSkipExtraAndHandlesMissingProviderResponse() = runBlocking {
        api.catalogs["$base/catalog/movie/top.json"] = CatalogResponse(listOf(item()))
        api.catalogs["$base/catalog/movie/top/skip=20.json"] = CatalogResponse(listOf(item("tt2")))
        assertEquals(listOf(item()), repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 0))
        assertEquals(listOf(item("tt2")), repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 20))
        assertTrue(repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 40).isEmpty())
    }
    @Test fun searchEncodesQueriesFiltersTypesAndRetainsCinemetaOrigin() = runBlocking {
        val movieUrl = "https://v3-cinemeta.strem.io/catalog/movie/top/search=Title%20Name.json"
        val seriesUrl = "https://v3-cinemeta.strem.io/catalog/series/top/search=Title%20Name.json"
        api.catalogs[movieUrl] = CatalogResponse(listOf(item()))
        api.catalogs[seriesUrl] = CatalogResponse(listOf(item("tt2", "series")))
        assertTrue(repository.searchMovies("a").isEmpty()); assertTrue(repository.searchCinemeta(" a ", "series").isEmpty())
        assertEquals(2, repository.searchMovies("Title Name").size)
        val series = repository.searchCinemeta(" Title Name ", "SERIES").single()
        assertEquals("tt2", series.id); assertEquals("https://v3-cinemeta.strem.io", series.addonBaseUrl)
        assertTrue(repository.searchMovies("missing").isEmpty())
    }
    @Test fun malformedNonNullMetadataIsSanitizedBeforeDisplayingCards() = runBlocking {
        val malformed = Gson().fromJson("""{"id":null,"name":"Bad","type":"movie"}""", MetaItem::class.java)
        api.catalogs["$base/catalog/movie/top.json"] = CatalogResponse(listOf(malformed, item()))
        assertEquals(listOf(item()), repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 0))
    }
    @Test fun discoverSkipsSearchOnlyCatalogsAndReadsGenreAndPaginationSupport() = runBlocking {
        val catalogs = listOf(CatalogManifest("movie", "top", "Popular", listOf(
            CatalogExtra("genre", options = listOf("Drama")), CatalogExtra("skip"))),
            CatalogManifest("movie", "search", "Search", listOf(CatalogExtra("search", true))))
        dao.insertAddon(addon().copy(catalogsJson = Gson().toJson(catalogs), nickname = "Custom"))
        val discover = repository.getDiscoverCatalogs().single()
        assertEquals("Custom", discover.addonName); assertEquals("Popular", discover.catalogName)
        assertEquals(listOf("Drama"), discover.genres); assertTrue(discover.supportsSkip)
        val url = "$base/catalog/movie/top/genre=Science+Fiction&skip=20.json"
        api.catalogs[url] = CatalogResponse(listOf(item()))
        assertEquals(listOf(item()), repository.fetchDiscoverPage(base, "movie", "top", "Science Fiction", 20))
        assertTrue(repository.fetchDiscoverPage(base, "movie", "top").isEmpty())
    }
    @Test fun dashboardFiltersScreensOrdersRowsAndKeepsOriginAndCustomTitle() = runBlocking {
        dao.insertAddon(addon().copy(catalogsJson = """[{"id":"top","type":"movie","name":"Popular","extra":[{"name":"skip"}]}]"""))
        dao.saveCatalogConfigs(listOf(config().copy(customTitle = "Custom", homeOrder = 2), config("first").copy(homeOrder = 1)))
        api.catalogs["$base/catalog/movie/top.json"] = CatalogResponse(listOf(item()))
        api.catalogs["$base/catalog/movie/first.json"] = CatalogResponse(listOf(item("tt2")))
        val rows = repository.getDashboardRows("home")
        assertEquals(listOf("first", "top"), rows.map { it.configId }); assertEquals("Custom", rows[1].title)
        assertTrue(rows[1].supportsSkip); assertEquals(base, rows[1].items.single().addonBaseUrl)
        assertEquals(1, repository.getDashboardRows("home", skipConfigs = 1, maxConfigs = 1).size)
        assertTrue(repository.getDashboardRows("movies").isEmpty()); assertTrue(repository.getDashboardRows("unknown").isEmpty())
    }
    @Test fun categoryPreviewCapsCardsAndMissingOrDisabledAddonIsIgnored() = runBlocking {
        assertNull(repository.getCategoryRowPreview("missing", 1))
        dao.saveCatalogConfig(config()); assertNull(repository.getCategoryRowPreview("top", 1))
        dao.insertAddon(addon(false)); assertNull(repository.getCategoryRowPreview("top", 1))
        dao.insertAddon(addon())
        api.catalogs["$base/catalog/movie/top.json"] = CatalogResponse(listOf(item(), item("tt2")))
        assertEquals(1, repository.getCategoryRowPreview("top", 1)!!.items.size)
        assertEquals(1, repository.getCategoryRowPreview("top", 0)!!.items.size)
    }
    @Test fun seriesMetadataAlwaysUsesCanonicalCinemetaAndRejectsWrongIdentity() = runBlocking {
        api.metadata["https://v3-cinemeta.strem.io/meta/series/tt1.json"] = item(type = "series").copy(
            videos = listOf(MetaVideo("tt1:1:1", season = 1, episode = 1)))
        val meta = repository.resolveMetaDetails("series", "tt1", base)!!
        assertEquals("https://v3-cinemeta.strem.io", meta.addonBaseUrl)
        assertEquals(1, meta.videos!!.single().episode); assertFalse(api.calls.any { it.startsWith(base) })
        api.metadata["https://v3-cinemeta.strem.io/meta/series/tt2.json"] = item("wrong", "series")
        assertNull(repository.resolveMetaDetails("series", "tt2", base))
        assertNull(repository.resolveMetaDetails("series", "missing", base))
    }
    @Test fun movieMetadataFallsBackFromPreferredToEnabledMetaAddonAndCinemeta() = runBlocking {
        dao.insertAddon(addon())
        api.metadata["$base/meta/movie/tt1.json"] = item()
        assertEquals(item(), repository.resolveMetaDetails("movie", "tt1", "https://missing.test"))
        api.metadata["$base/meta/movie/tt2.json"] = item("wrong")
        api.metadata["https://v3-cinemeta.strem.io/meta/movie/tt2.json"] = item("tt2")
        assertEquals("tt2", repository.resolveMetaDetails("movie", "tt2")!!.id)
        assertNull(repository.resolveMetaDetails("movie", "missing"))
    }
    @Test fun installingRenamingAndDeletingAddonKeepsCatalogConfigurationConsistent() = runBlocking {
        api.manifest = Manifest("addon", "Addon", "1", resources = JsonParser.parseString("""["meta",{"name":"stream"}]""").asJsonArray.toList(),
            types = listOf("movie", "series"), catalogs = listOf(CatalogManifest("movie", "top", "Popular"), CatalogManifest("series", "top", "Shows")))
        repository.installAddon("$base/manifest.json")
        assertTrue(dao.getAddon(base)!!.supportsMeta); assertTrue(dao.getAddon(base)!!.supportsStream)
        val configs = dao.getAllCatalogConfigs().first()
        assertEquals(2, configs.size); assertTrue(configs.all { it.showInHome })
        assertTrue(configs.first { it.catalogType == "movie" }.showInMovies)
        assertFalse(configs.first { it.catalogType == "series" }.showInMovies)
        repository.renameAddon(base, "Custom"); assertEquals("Custom", repository.getAddons().first().single().nickname)
        assertEquals(mapOf(base to 999), repository.getAddonSortOrders())
        repository.updateAddons(listOf(dao.getAddon(base)!!.copy(sortOrder = 3)))
        assertEquals(3, repository.getAddonSortOrders()[base])
        assertEquals(api.manifest, repository.fetchManifest("$base/manifest.json"))
        repository.deleteAddon(base); assertTrue(dao.getAllCatalogConfigs().first().isEmpty()); assertTrue(repository.getAddons().first().isEmpty())
    }
    @Test fun hubRowsFilterScreensSortItemsAndRecoverFromUnknownShape() = runBlocking {
        dao.insertHubRows(listOf(HubRowEntity("one", "One", "invalid", showInHome = true, homeOrder = 2),
            HubRowEntity("two", "Two", "HORIZONTAL", showInHome = true, homeOrder = 1)))
        dao.insertHubRowItems(listOf(HubRowItemEntity("one", "last", "Last", itemOrder = 2),
            HubRowItemEntity("one", "first", "First", itemOrder = 1)))
        val rows = repository.getHubRows()
        assertEquals(listOf("two", "one"), rows.map { it.id }); assertEquals(listOf("First", "Last"), rows[1].items.map { it.title })
        assertTrue(repository.getHubRows("movies").isEmpty()); assertTrue(repository.getHubRows("unknown").isEmpty())
    }
}
