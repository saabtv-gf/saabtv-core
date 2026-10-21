package com.saab.tv.ui.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.model.tmdb.*
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.testing.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import retrofit2.Response
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeOrchestrationP1Test {
    private lateinit var f: FeatureFixture
    private lateinit var vm: HomeViewModel
    private var testTmdbService: TmdbService? = null
    private val movie=MetaItem("tt1","movie","Movie")
    @Before fun setup() = runBlocking {
        f=FeatureFixture(RuntimeEnvironment.getApplication())
        f.dao.insertProfile(ProfileEntity(id=1,name="One",isActive=true,tmdbEnabled=false))
        f.dao.insertProfile(ProfileEntity(id=2,name="Two"))
        f.app.configuration.saveRuntimeState(1); vm=f.home()
    }
    @After fun cleanup() { testTmdbService?.clearApiKey(); vm.viewModelScope.cancel(); f.close() }
    @Test fun watchlistToggleAddsThenRemovesOnlySelectedProfile() = runBlocking {
        f.dao.addToWatchlist(WatchlistEntity(2,"tt1","movie","Other",null,1))
        vm.toggleWatchlist(1,movie); awaitAppState { runBlocking { f.dao.isInWatchlist(1,"tt1") } }
        assertEquals("Movie",f.dao.getWatchlistItem(1,"tt1")?.title)
        vm.toggleWatchlist(1,movie); awaitAppState { runBlocking { !f.dao.isInWatchlist(1,"tt1") } }
        assertTrue(f.dao.isInWatchlist(2,"tt1"))
    }
    @Test fun quickActionMarkMovieWatchedIsProfileScopedAndRemovesItsWatchlistEntry() = runBlocking {
        f.dao.addToWatchlist(WatchlistEntity(1,"tt1","movie","Movie",null,1))
        f.dao.addToWatchlist(WatchlistEntity(2,"tt1","movie","Other Profile Copy",null,1))

        vm.markTitleWatched(1,movie)

        awaitAppState { runBlocking {
            f.dao.getHistoryItemForProfile(1,"tt1")?.watched == true && !f.dao.isInWatchlist(1,"tt1")
        } }
        assertFalse(f.dao.isInWatchlist(1,"tt1"))
        assertTrue(f.dao.isInWatchlist(2,"tt1"))
        assertTrue(vm.isTitleWatched(1,movie))
    }
    @Test fun quickActionMarkSeriesWatchedMarksEpisodesAndCompletesSeries() = runBlocking {
        val series = movie.copy(type="series", videos=listOf(
            MetaVideo(id="tt1:1:1",title="Pilot",season=1,episode=1),
            MetaVideo(id="tt1:1:2",title="Second",season=1,episode=2)
        ))
        f.dao.addToWatchlist(WatchlistEntity(1,"tt1","series","Movie",null,1))

        vm.markTitleWatched(1,series)

        awaitAppState { runBlocking { f.dao.getSeriesNextUpForProfile(1).any { it.seriesId == "tt1" && it.isComplete } } }
        assertTrue(f.dao.getHistoryItemForProfile(1,"tt1:1:1")?.watched == true)
        assertTrue(f.dao.getHistoryItemForProfile(1,"tt1:1:2")?.watched == true)
        assertFalse(f.dao.isInWatchlist(1,"tt1"))
        assertTrue(vm.isTitleWatched(1,series))
    }
    @Test fun clearMovieProgressDoesNotClearOtherProfileOrTitle() = runBlocking {
        listOf(1 to "tt1",2 to "tt1",1 to "tt2").forEach { (profile,id) ->
            f.dao.insertHistory(WatchHistoryEntity(profile,id,"Title",null,position=10000,duration=100000,lastWatched=1,type="movie"))
        }
        vm.clearContinueProgress(1,movie)
        awaitAppState { runBlocking { f.dao.getHistoryItemForProfile(1,"tt1") == null } }
        assertNotNull(f.dao.getHistoryItemForProfile(2,"tt1")); assertNotNull(f.dao.getHistoryItemForProfile(1,"tt2"))
    }
    @Test fun clearSeriesProgressClearsAllItsEpisodesButNotOtherSeries() = runBlocking {
        listOf("tt1:1:1","tt1:2:2","tt2:1:1").forEach { id ->
            f.dao.insertHistory(WatchHistoryEntity(1,id,"Episode",null,position=10000,duration=100000,lastWatched=1,type="series"))
        }
        vm.clearContinueProgress(1,movie.copy(type="series"))
        awaitAppState { runBlocking { f.dao.getHistoryItemForProfile(1,"tt1:2:2") == null } }
        assertNull(f.dao.getHistoryItemForProfile(1,"tt1:1:1")); assertNotNull(f.dao.getHistoryItemForProfile(1,"tt2:1:1"))
    }
    @Test fun embeddedTrailerMetadataAvoidsExtraProviderLookup() = runBlocking {
        val result=vm.trailerFor(movie.copy(trailerStreams=listOf(MetaTrailerStream(ytId="B3tR6qQjbgI",title="Trailer"))))
        assertEquals("B3tR6qQjbgI",result?.first); assertTrue(f.api.calls.isEmpty())
    }
    @Test fun noProfileMakesTrailerPrefetchANoOp() = runBlocking {
        f.dao.insertProfile(f.dao.getProfileById(1)!!.copy(isActive=false))
        vm.prefetchTrailerSources(movie); assertTrue(f.api.calls.isEmpty())
    }
    @Test fun emptyCatalogCompletesLoadingWithoutGhostRowsAndResetsFocus() {
        vm.setLastFocusedKey("old")
        vm.loadScreen("movies",ProfileEntity(id=1,name="One",tmdbEnabled=false))
        awaitAppState { !vm.state.value.isLoading && vm.state.value.loadedScreen == "movies" }
        assertTrue(vm.state.value.rows.isEmpty()); assertNull(vm.state.value.lastFocusedKey)
    }

    @Test fun loadMoreCatalogPageDeduplicatesAndAppendsNewItems() = runBlocking {
        val addonUrl = "https://fixture.invalid"
        val catalogUrl = "$addonUrl/catalog/movie/top.json"
        f.dao.insertAddon(AddonEntity(
            transportUrl = addonUrl, id = "fixture", name = "Fixture", version = "1", description = null, iconUrl = null,
            catalogsJson = """[{"id":"top","type":"movie","name":"Top","extra":[{"name":"skip"}]}]""",
            supportsMeta = true, supportsStream = true
        ))
        f.dao.saveCatalogConfig(CatalogConfigEntity(
            uniqueId = "fixture-movie-top", transportUrl = addonUrl, addonName = "Fixture",
            catalogType = "movie", catalogId = "top", showInHome = true
        ))
        fun item(id: String) = MetaItem(
            id = id, type = "movie", name = id,
            poster = "android.resource://android/drawable/sym_def_app_icon", background = "background", logo = "logo",
            description = "description", releaseInfo = "2026", imdbRating = "8.0", runtime = "90m", genres = listOf("Drama")
        )
        f.api.catalogPages[catalogUrl] = CatalogResponse(listOf(item("tt1"), item("tt2")))
        f.api.catalogPages[catalogUrl.replace(".json", "/skip=2.json")] =
            CatalogResponse(listOf(item("tt2"), item("tt3")))

        vm.loadScreen("home", ProfileEntity(id = 1, name = "One", tmdbEnabled = false))
        awaitAppState { !vm.state.value.isLoading && vm.state.value.loadedScreen == "home" }
        val row = vm.state.value.rows.single()
        assertTrue(row.supportsSkip)
        assertEquals(listOf("tt1", "tt2"), row.items.map { it.asActionItem().id })

        vm.loadMoreItems(row.id)
        awaitAppState { vm.state.value.rows.singleOrNull()?.items?.map { it.asActionItem().id } == listOf("tt1", "tt2", "tt3") }
        assertTrue(f.api.calls.contains(catalogUrl.replace(".json", "/skip=2.json")))
    }

    @Test fun openingViewAllLoadsItsCatalogAndPublishesPagingState() = runBlocking {
        val addonUrl = "https://fixture.invalid"
        val catalogUrl = "$addonUrl/catalog/movie/top.json"
        f.dao.insertAddon(AddonEntity(
            transportUrl = addonUrl, id = "fixture", name = "Fixture", version = "1", description = null, iconUrl = null,
            catalogsJson = """[{"id":"top","type":"movie","name":"Top","extra":[{"name":"skip"}]}]""",
            supportsMeta = true, supportsStream = true
        ))
        f.dao.saveCatalogConfig(CatalogConfigEntity(
            uniqueId = "fixture-movie-top", transportUrl = addonUrl, addonName = "Fixture",
            catalogType = "movie", catalogId = "top", showInHome = true
        ))
        f.api.catalogPages[catalogUrl] = CatalogResponse(listOf(movie))
        val opened = mutableListOf<Pair<String, List<MetaItem>>>()

        vm.openHub(com.saab.tv.domain.HubItem("fixture-movie-top", "Top", "fixture-movie-top")) { title, items ->
            opened += title to items
        }

        awaitAppState { opened.isNotEmpty() && !vm.state.value.loadingHubIds.contains("fixture-movie-top") }
        assertEquals("Fixture - Top", opened.single().first)
        assertEquals(listOf(movie.id), opened.single().second.map { it.id })
        assertTrue(vm.state.value.rows.any { it.id == "fixture-movie-top" })
        assertTrue(vm.state.value.loadableRowIds.contains("fixture-movie-top"))
        assertFalse(vm.state.value.hubErrorIds.contains("fixture-movie-top"))
    }

    @Test fun openingUnavailableViewAllCatalogPublishesRetryableErrorInsteadOfBlankGrid() = runBlocking {
        var callbackCount = 0

        vm.openHub(com.saab.tv.domain.HubItem("missing", "Missing Catalog", "missing")) { _, _ ->
            callbackCount++
        }

        awaitAppState {
            "missing" in vm.state.value.hubErrorIds && "missing" !in vm.state.value.loadingHubIds
        }
        assertEquals(0, callbackCount)
    }

    @Test fun lateHubResponseDoesNotMutateTheNextSelectedTab() = runBlocking {
        val addonUrl = "https://fixture.invalid"
        val catalogUrl = "$addonUrl/catalog/movie/top.json"
        f.dao.insertAddon(AddonEntity(
            transportUrl = addonUrl, id = "fixture", name = "Fixture", version = "1", description = null, iconUrl = null,
            catalogsJson = """[{"id":"top","type":"movie","name":"Top"}]""",
            supportsMeta = true, supportsStream = true
        ))
        f.dao.saveCatalogConfig(CatalogConfigEntity(
            uniqueId = "fixture-movie-top", transportUrl = addonUrl, addonName = "Fixture",
            catalogType = "movie", catalogId = "top", showInHome = true
        ))
        val responseGate = CompletableDeferred<Unit>()
        f.api.catalogPages[catalogUrl] = CatalogResponse(listOf(movie))
        f.api.catalogGate = responseGate
        var callbackCount = 0

        vm.openHub(com.saab.tv.domain.HubItem("fixture-movie-top", "Top", "fixture-movie-top")) { _, _ ->
            callbackCount++
        }
        awaitAppState { catalogUrl in f.api.calls }
        vm.loadScreen("series", f.dao.getProfileById(1))
        awaitAppState { !vm.state.value.isLoading && vm.state.value.loadedScreen == "series" }
        responseGate.complete(Unit)
        awaitAppState { "fixture-movie-top" !in vm.state.value.loadingHubIds }

        assertEquals(0, callbackCount)
        assertFalse(vm.state.value.rows.any { it.id == "fixture-movie-top" })
    }

    @Test fun tmdbViewAllLoadsNextPageAndAppendsOnlyNewCanonicalTitles() = runBlocking {
        val requestedRecommendationPages = mutableListOf<Int>()
        val tmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            when (method.name) {
                "getMovieRecommendations" -> {
                    val page = args!![3] as Int
                    requestedRecommendationPages += page
                    val id = if (page == 1) 101 else 102
                    Response.success(200, TmdbRecommendationsResponse(
                        results = listOf(TmdbRecommendationResult(
                            id = id, title = "Recommendation $page", posterPath = "/$id.jpg",
                            originalLanguage = "en", voteAverage = 8.0, voteCount = 100
                        )),
                        totalPages = 2
                    ))
                }
                "getMovieDetails" -> Response.success(200, TmdbDetailsResponse(
                    id = args!![0] as Int, title = "Seed", genres = listOf(TmdbGenre(18, "Drama")),
                    originalLanguage = "en"
                ))
                "discoverMovies" -> Response.success(200, TmdbDiscoverResponse(totalPages = 1))
                "discoverTv" -> Response.success(200, TmdbDiscoverResponse(
                    results = listOf(TmdbDiscoverResult(
                        id = 901, name = "Series cross-type match", posterPath = "/901.jpg",
                        originalLanguage = "en", voteAverage = 8.0, voteCount = 100, popularity = 50.0
                    )),
                    totalPages = 1
                ))
                else -> error("Unexpected TMDB request: ${method.name}")
            }
        } as TmdbApiService
        val tmdbService = TmdbService(f.context, tmdbApi)
        com.saab.tv.data.security.SecurePreferences.create(
            f.context, "tmdb_credentials", "saabtv_tmdb_master_key"
        ).preferences.edit().putString("api_key", "test-key").apply()
        assertTrue("Fixture TMDB credentials must be available", tmdbService.hasApiKey())
        testTmdbService = tmdbService
        val metadataService = TmdbMetadataService(tmdbApi, tmdbService)
        vm = HomeViewModel(
            f.app.repository,
            f.dao,
            f.context,
            tmdbService,
            metadataService,
            com.saab.tv.data.ott.OttCatalogRepository(
                f.context,
                OkHttpClient.Builder().addInterceptor { error("Unexpected OTT request in home fixture") }.build()
            ),
            f.cache,
            f.sync,
            f.sources,
            f.tracks,
            f.traktSync,
            f.userStateCommands
        )
        val profile = f.dao.getProfileById(1)!!.copy(tmdbEnabled = true)
        f.dao.updateProfile(profile)
        f.dao.insertHistory(WatchHistoryEntity(
            profileId = 1, id = "tmdb:10", title = "Seed", poster = null,
            position = 5_000, duration = 100_000, lastWatched = 10, type = "movie", watched = false
        ))

        vm.loadScreen("home", profile)
        awaitAppState { vm.state.value.personalizedRows.any { it.id == "tmdb-top-suggestions" } }
        val initial = vm.state.value.personalizedRows.single { it.id == "tmdb-top-suggestions" }
        assertEquals(
            listOf("Recommendation 1", "Series cross-type match"),
            initial.items.map { it.asActionItem().name }
        )
        assertEquals(listOf("movie", "series"), initial.items.map { it.asActionItem().type })

        vm.loadMoreItems("tmdb-top-suggestions")
        awaitAppState {
            vm.state.value.personalizedRows.singleOrNull { it.id == "tmdb-top-suggestions" }
                ?.items?.any { it.asActionItem().name == "Recommendation 2" } == true
        }

        val loaded = vm.state.value.personalizedRows.single { it.id == "tmdb-top-suggestions" }
        assertEquals(
            listOf("Recommendation 1", "Series cross-type match", "Recommendation 2"),
            loaded.items.map { it.asActionItem().name }
        )
        assertTrue("The grid's View All page should request page two", requestedRecommendationPages.contains(2))
    }

    @Test fun coldHomeShowsMixedDiscoveryButDoesNotInventHistorySuggestions() = runBlocking {
        val tmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, args ->
            val sortBy = args?.getOrNull(3) as? String
            val isNew = sortBy == "primary_release_date.desc" || sortBy == "first_air_date.desc"
            when (method.name) {
                "discoverMovies" -> {
                    val id = if (isNew) 301 else 302
                    Response.success(200, TmdbDiscoverResponse(results = listOf(
                        TmdbDiscoverResult(
                            id = id, title = "Movie $id", posterPath = "/$id.jpg",
                            releaseDate = "2026-06-01", originalLanguage = "en", popularity = 30.0
                        )
                    )))
                }
                "discoverTv" -> {
                    val id = if (isNew) 401 else 403
                    Response.success(200, TmdbDiscoverResponse(results = listOf(
                        TmdbDiscoverResult(
                            id = id, name = "Series $id", posterPath = "/$id.jpg",
                            firstAirDate = "2026-06-01", originalLanguage = "en", popularity = 25.0
                        )
                    )))
                }
                else -> error("Cold-start recommendations should not request ${method.name}")
            }
        } as TmdbApiService
        val tmdbService = TmdbService(f.context, tmdbApi)
        com.saab.tv.data.security.SecurePreferences.create(
            f.context, "tmdb_credentials", "saabtv_tmdb_master_key"
        ).preferences.edit().putString("api_key", "test-key").apply()
        testTmdbService = tmdbService
        vm = HomeViewModel(
            f.app.repository,
            f.dao,
            f.context,
            tmdbService,
            TmdbMetadataService(tmdbApi, tmdbService),
            com.saab.tv.data.ott.OttCatalogRepository(
                f.context,
                OkHttpClient.Builder().addInterceptor { error("Unexpected OTT request in cold-start fixture") }.build()
            ),
            f.cache,
            f.sync,
            f.sources,
            f.tracks,
            f.traktSync,
            f.userStateCommands
        )
        val profile = f.dao.getProfileById(1)!!.copy(tmdbEnabled = true)

        vm.loadScreen("home", profile)
        awaitAppState { vm.state.value.personalizedRows.any { it.id == "tmdb-new-for-you" } }

        val rows = vm.state.value.personalizedRows
        assertFalse(rows.any { it.id == "tmdb-top-suggestions" })
        assertEquals(setOf("movie", "series"), rows.single { it.id == "tmdb-new-for-you" }
            .items.map { it.asActionItem().type }.toSet())
        assertEquals(setOf("movie", "series"), rows.single { it.id == "tmdb-popular-in-taste" }
            .items.map { it.asActionItem().type }.toSet())
    }

    @Test fun tmdbEnrichmentStartedOnOldScreenCannotWriteIntoTheNextScreen() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val requestReturned = CountDownLatch(1)
        val tmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getMovieDetails" -> {
                    requestStarted.countDown()
                    try {
                        check(releaseRequest.await(5, TimeUnit.SECONDS)) { "Test did not release the held TMDB response" }
                        Response.success(TmdbDetailsResponse(id = 42, title = "Stale enrichment"))
                    } finally {
                        requestReturned.countDown()
                    }
                }
                else -> Response.success<Any>(null)
            }
        } as TmdbApiService
        val tmdbService = TmdbService(f.context, tmdbApi)
        com.saab.tv.data.security.SecurePreferences.create(
            f.context, "tmdb_credentials", "saabtv_tmdb_master_key"
        ).preferences.edit().putString("api_key", "test-key").apply()
        testTmdbService = tmdbService
        vm.viewModelScope.cancel()
        vm = HomeViewModel(
            f.app.repository,
            f.dao,
            f.context,
            tmdbService,
            TmdbMetadataService(tmdbApi, tmdbService),
            com.saab.tv.data.ott.OttCatalogRepository(
                f.context,
                OkHttpClient.Builder().addInterceptor { error("Unexpected OTT request in home fixture") }.build()
            ),
            f.cache,
            f.sync,
            f.sources,
            f.tracks,
            f.traktSync,
            f.userStateCommands
        )
        val profile = f.dao.getProfileById(1)!!.copy(tmdbEnabled = true)
        val item = MetaItem("tmdb:42", "movie", "Stale")

        vm.loadScreen("movies", profile)
        awaitAppState { !vm.state.value.isLoading && vm.state.value.loadedScreen == "movies" }
        vm.ensureTmdbEnrichment(item)
        awaitAppState { requestStarted.count == 0L }
        assertEquals("The held metadata request should begin", 0L, requestStarted.count)

        vm.loadScreen("series", profile)
        awaitAppState { !vm.state.value.isLoading && vm.state.value.loadedScreen == "series" }
        releaseRequest.countDown()
        assertTrue("The held response should be released", requestReturned.await(5, TimeUnit.SECONDS))
        awaitAppState { vm.state.value.loadedScreen == "series" }
        delay(250)
        assertTrue("Old-screen metadata must not be written into the new screen", vm.state.value.enrichedMeta.isEmpty())
        assertTrue("Old-screen completion must not mark the new screen enriched", vm.state.value.tmdbEnrichedIds.isEmpty())
    }
}
