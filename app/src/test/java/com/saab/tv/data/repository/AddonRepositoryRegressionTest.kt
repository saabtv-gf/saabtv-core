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
import com.saab.tv.domain.schema.CanonicalEntityKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        var catalogGate: CompletableDeferred<Unit>? = null
        val metadata = mutableMapOf<String, MetaItem>()
        val streams = mutableMapOf<String, StreamResponse>()
        val streamHandlers = mutableMapOf<String, suspend () -> StreamResponse>()
        var manifest = Manifest(id = "addon", name = "Addon", version = "1")
        override suspend fun getCatalog(url: String): CatalogResponse {
            calls += url
            catalogGate?.await()
            return catalogs[url] ?: throw IOException("Not in fixture")
        }
        override suspend fun getMeta(url: String): MetaResponse { calls += url; return MetaResponse(metadata[url] ?: throw IOException("Not in fixture")) }
        override suspend fun getStreams(url: String): StreamResponse {
            calls += url
            return streamHandlers[url]?.invoke() ?: streams[url] ?: throw IOException("Not in fixture")
        }
        override suspend fun getManifest(url: String): Manifest { calls += url; return manifest }
        override suspend fun getSubtitles(url: String): SubtitleResponse = error("Not a subtitle test")
    }
    private lateinit var db: SaabTvDatabase
    private lateinit var api: Api
    private lateinit var repository: AddonRepository
    private lateinit var canonicalTitles: MetadataStore
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
        api = Api(); canonicalTitles = MetadataStore()
        repository = AddonRepository(api, dao, TorBoxAvailabilityService(RuntimeEnvironment.getApplication()), canonicalTitles)
    }
    @After fun tearDown() { db.close() }

    @Test fun nextCatalogPageUsesSkipExtraAndHandlesMissingProviderResponse() = runBlocking {
        api.catalogs["$base/catalog/movie/top.json"] = CatalogResponse(listOf(item()))
        api.catalogs["$base/catalog/movie/top/skip=20.json"] = CatalogResponse(listOf(item("tt2")))
        assertEquals(listOf(item().copy(addonBaseUrl = base)), repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 0))
        assertEquals(listOf(item("tt2").copy(addonBaseUrl = base)), repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 20))
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
    @Test fun searchReportsOneFailedTypeWithoutDiscardingTheSuccessfulType() = runBlocking {
        val movieUrl = "https://v3-cinemeta.strem.io/catalog/movie/top/search=Title.json"
        api.catalogs[movieUrl] = CatalogResponse(listOf(item()))

        val outcome = repository.searchMoviesWithStatus("Title")

        assertEquals(listOf(item().copy(addonBaseUrl = "https://v3-cinemeta.strem.io")), outcome.items)
        assertEquals(setOf("series"), outcome.failedTypes)
        assertEquals(outcome.items, repository.searchMovies("Title"))
    }

    @Test fun explicitShortSearchSupportsTwoCharacterTitleQueries() = runBlocking {
        val movieUrl = "https://v3-cinemeta.strem.io/catalog/movie/top/search=it.json"
        val seriesUrl = "https://v3-cinemeta.strem.io/catalog/series/top/search=it.json"
        api.catalogs[movieUrl] = CatalogResponse(listOf(item("tt-it-movie")))
        api.catalogs[seriesUrl] = CatalogResponse(listOf(item("tt-it-series", "series")))

        val outcome = repository.searchMoviesWithStatus(" it ")

        assertEquals(setOf("tt-it-movie", "tt-it-series"), outcome.items.map { it.id }.toSet())
        assertTrue(outcome.failedTypes.isEmpty())
        assertTrue(api.calls.contains(movieUrl))
        assertTrue(api.calls.contains(seriesUrl))
    }
    @Test fun malformedNonNullMetadataIsSanitizedBeforeDisplayingCards() = runBlocking {
        val malformed = Gson().fromJson("""{"id":null,"name":"Bad","type":"movie"}""", MetaItem::class.java)
        api.catalogs["$base/catalog/movie/top.json"] = CatalogResponse(listOf(malformed, item()))
        assertEquals(listOf(item().copy(addonBaseUrl = base)), repository.fetchNextCatalogPage("$base/catalog/movie/top.json", 0))
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
        assertEquals(listOf(item().copy(addonBaseUrl = base)), repository.fetchDiscoverPage(base, "movie", "top", "Science Fiction", 20))
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

    @Test fun categoryPreviewDistinguishesAnEmptyCatalogFromAProviderFailure() = runBlocking {
        dao.insertAddon(addon().copy(catalogsJson = """[{"id":"top","type":"movie","extra":[{"name":"skip"}]}]"""))
        dao.saveCatalogConfig(config())
        val url = "$base/catalog/movie/top.json"

        api.catalogs[url] = CatalogResponse(emptyList())
        val empty = repository.getCategoryRowPreviewWithStatus("top", 100)
        assertFalse(empty.failed)
        val emptyRow = requireNotNull(empty.row)
        assertTrue(emptyRow.items.isEmpty())
        assertTrue(emptyRow.supportsSkip)

        api.catalogs.remove(url)
        val failed = repository.getCategoryRowPreviewWithStatus("top", 100)
        assertTrue(failed.failed)
        assertNull(failed.row)
    }

    @Test fun categoryPreviewTimeoutBecomesRetryableProviderFailure() = runBlocking {
        dao.insertAddon(addon())
        dao.saveCatalogConfig(config())
        api.catalogGate = CompletableDeferred()

        val result = repository.getCategoryRowPreviewWithStatus("top", 100, timeoutMs = 1)

        assertTrue(result.failed)
        assertNull(result.row)
    }

    @Test fun progressiveStreamDiscoveryPublishesFastAddonBeforeSlowAddonCompletes() = runBlocking {
        val fastUrl = "https://fast-addon.test"
        val slowUrl = "https://slow-addon.test"
        val slowGate = CompletableDeferred<Unit>()
        val partials = Channel<List<Stream>>(capacity = 2)
        dao.insertAddon(addon().copy(transportUrl = fastUrl, id = "fast", name = "Fast"))
        dao.insertAddon(addon().copy(transportUrl = slowUrl, id = "slow", name = "Slow"))
        val fastRequest = "$fastUrl/stream/movie/tt1.json"
        val slowRequest = "$slowUrl/stream/movie/tt1.json"
        api.streamHandlers[fastRequest] = {
            StreamResponse(listOf(Stream(name = "Fast result", url = "https://media.test/fast.mkv")))
        }
        api.streamHandlers[slowRequest] = {
            slowGate.await()
            StreamResponse(listOf(Stream(name = "Slow result", url = "https://media.test/slow.mkv")))
        }

        val finalResults = async {
            repository.getStreamsProgressively("movie", "tt1") { partials.trySend(it) }
        }
        val firstBatch = withTimeout(2_000) { partials.receive() }
        assertEquals(1, firstBatch.size)
        assertTrue(firstBatch.single().name.orEmpty().contains("[Fast]"))
        assertTrue("Discovery must not wait for the slow provider", !finalResults.isCompleted)

        slowGate.complete(Unit)
        val allResults = withTimeout(2_000) { finalResults.await() }
        val finalPartial = withTimeout(2_000) { partials.receive() }
        assertEquals(setOf(fastUrl, slowUrl), allResults.mapNotNull { it.addonTransportUrl }.toSet())
        assertEquals(2, allResults.size)
        assertEquals("Final return and last partial must carry identical availability evidence", allResults, finalPartial)
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
    @Test fun completeCinemetaSeriesMetadataUsesPersistentCanonicalCacheOnRepeat() = runBlocking {
        val id = "tt1234567"
        val url = "https://v3-cinemeta.strem.io/meta/series/$id.json"
        val item = MetaItem(id = id, type = "series", name = "Cached series",
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            videos = listOf(MetaVideo("$id:2:5", "Episode five", season = 2, episode = 5)))
        api.metadata[url] = item
        val canonical = CanonicalMetadataRepository(db.canonicalMetadataDao())
        val persistentRepository = AddonRepository(
            api, dao, TorBoxAvailabilityService(RuntimeEnvironment.getApplication()), canonical, canonical
        )

        assertEquals(item.videos, persistentRepository.resolveMetaDetails("series", id)?.videos)
        assertEquals(item.videos, persistentRepository.resolveMetaDetails("series", id)?.videos)
        assertEquals(1, api.calls.count { it == url })
    }

    @Test fun catalogProjectionUsesCanonicalArtworkAndDeduplicatesVerifiedAliases() = runBlocking {
        val canonical = CanonicalMetadataRepository(db.canonicalMetadataDao())
        val imdb = requireNotNull(com.saab.tv.domain.schema.ContentIdentity.imdb(
            com.saab.tv.domain.schema.ContentKind.MOVIE, "tt0000042"))
        val tmdb = requireNotNull(com.saab.tv.domain.schema.ContentIdentity.tmdb(
            com.saab.tv.domain.schema.ContentKind.MOVIE, 42))
        canonical.linkVerifiedIdentities(imdb, tmdb)
        canonical.putTitle(CanonicalTitleEntity(
            identityKey = imdb.stableKey,
            locale = "",
            kind = "movie",
            title = "Canonical title",
            titleSource = "TMDB",
            poster = "canonical-poster",
            posterSource = "TMDB",
            backdrop = "canonical-backdrop",
            backdropSource = "TMDB",
            logo = "canonical-logo",
            logoSource = "TMDB",
            lastFetchedAtMillis = System.currentTimeMillis()
        ))
        val repo = AddonRepository(api, dao, TorBoxAvailabilityService(RuntimeEnvironment.getApplication()),
            canonical, canonical, canonical)
        val rows = repo.canonicalizeCatalogItems(listOf(
            item("tt0000042"),
            MetaItem(id = "tmdb:42", type = "movie", name = "TMDB fallback", poster = "fallback")
        ))

        assertEquals(1, rows.size)
        assertEquals("Canonical title", rows.single().name)
        assertEquals("canonical-poster", rows.single().poster)
        assertEquals("canonical-backdrop", rows.single().background)
        assertEquals("canonical-logo", rows.single().logo)
    }

    @Test fun canonicalUserStateKeysResolveVerifiedMovieAndSeriesAliases() = runBlocking {
        val canonical = CanonicalMetadataRepository(db.canonicalMetadataDao())
        val tmdbMovie = requireNotNull(com.saab.tv.domain.schema.ContentIdentity.tmdb(
            com.saab.tv.domain.schema.ContentKind.MOVIE, 42))
        val imdbMovie = requireNotNull(com.saab.tv.domain.schema.ContentIdentity.imdb(
            com.saab.tv.domain.schema.ContentKind.MOVIE, "tt0000042"))
        val tmdbSeries = requireNotNull(com.saab.tv.domain.schema.ContentIdentity.tmdb(
            com.saab.tv.domain.schema.ContentKind.SERIES, 42))
        val imdbSeries = requireNotNull(com.saab.tv.domain.schema.ContentIdentity.imdb(
            com.saab.tv.domain.schema.ContentKind.SERIES, "tt0000043"))
        canonical.linkVerifiedIdentities(tmdbMovie, imdbMovie)
        canonical.linkVerifiedIdentities(tmdbSeries, imdbSeries)
        val repo = AddonRepository(
            api, dao, TorBoxAvailabilityService(RuntimeEnvironment.getApplication()), canonical, canonical, canonical
        )

        assertEquals(imdbMovie.stableKey, repo.canonicalIdentityKey("tmdb:42", "movie"))
        assertEquals(imdbMovie.stableKey, repo.canonicalIdentityKey(item("tmdb:42")))
        assertEquals(
            CanonicalEntityKey.playable("tmdb:42:2:5", "series", canonicalSeriesIdentityKey = imdbSeries.stableKey),
            repo.canonicalPlayableIdentityKey("tmdb:42:2:5", "series")
        )
        assertNotNull(repo.canonicalIdentityKey("opaque-17", "movie", base))
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
    @Test fun cinematMetaMovieDetailsAreCachedAndPreferredAddonIsOnlyUsedWhenCinemetaIsUnavailable() = runBlocking {
        val cinemetaUrl = "https://v3-cinemeta.strem.io/meta/movie/tt1.json"
        api.metadata[cinemetaUrl] = item().copy(
            description = "Cached synopsis", runtime = "1h 20m", releaseInfo = "2025", imdbRating = "8.1"
        )
        val fetched = repository.resolveMetaDetails("movie", "tt1")!!
        assertEquals("Cached synopsis", fetched.description)
        assertEquals(1, api.calls.count { it == cinemetaUrl })

        api.metadata.remove(cinemetaUrl)
        val cached = repository.resolveMetaDetails("movie", "tt1")!!
        assertEquals("Title", cached.name)
        assertEquals("Cached synopsis", cached.description)
        assertEquals("1h 20m", cached.runtime)
        assertEquals(1, api.calls.count { it == cinemetaUrl })

        dao.insertAddon(addon())
        api.metadata["$base/meta/movie/tt1.json"] = item().copy(name = "Preferred addon title")
        assertEquals("Title", repository.resolveMetaDetails("movie", "tt1", base)?.name)
        assertEquals(0, api.calls.count { it == "$base/meta/movie/tt1.json" })

        api.metadata["$base/meta/movie/tt2.json"] = item("tt2").copy(name = "Preferred addon title")
        assertEquals("Preferred addon title", repository.resolveMetaDetails("movie", "tt2", base)?.name)
        assertEquals(0, api.calls.count { it == "$base/meta/movie/tt1.json" })
        assertEquals(1, api.calls.count { it == "$base/meta/movie/tt2.json" })
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

    private class MetadataStore : CanonicalTitleStore {
        private val rows = mutableMapOf<Pair<String, String>, CanonicalTitleEntity>()
        override suspend fun getTitle(identity: com.saab.tv.domain.schema.ContentIdentity, locale: String) =
            rows[identity.stableKey to locale]
        override suspend fun putTitle(title: CanonicalTitleEntity) {
            rows[title.identityKey to title.locale] = title
        }
    }
}
