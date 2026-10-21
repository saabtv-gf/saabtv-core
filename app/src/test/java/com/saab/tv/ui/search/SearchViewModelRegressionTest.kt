package com.saab.tv.ui.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.testing.*
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.model.tmdb.TmdbDiscoverResponse
import com.saab.tv.data.model.tmdb.TmdbDiscoverResult
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.lang.reflect.Proxy
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SearchViewModelRegressionTest {
    private class SearchApi : StremioApiService {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val failedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        override suspend fun getCatalog(url: String): CatalogResponse {
            calls += url
            if (failedUrls.any(url::contains)) error("Fixture provider failure")
            val type = if ("/series/" in url) "series" else "movie"
            val id = if ("Newest" in url) "new" else "old"
            val item = MetaItem(id, type, "Result $id")
            return CatalogResponse(listOf(item, item))
        }
        override suspend fun getManifest(url: String): Manifest = error("Unexpected manifest")
        override suspend fun getMeta(url: String): MetaResponse = error("Unexpected metadata")
        override suspend fun getStreams(url: String): StreamResponse = error("Unexpected streams")
        override suspend fun getSubtitles(url: String): SubtitleResponse = error("Unexpected subtitles")
    }
    private lateinit var app: OfflineAppFixture
    private lateinit var api: SearchApi
    private lateinit var vm: SearchViewModel
    private lateinit var savedStateHandle: SavedStateHandle
    private var testTmdbService: TmdbService? = null
    @Before fun setup() {
        api = SearchApi(); app = OfflineAppFixture(RuntimeEnvironment.getApplication(), api)
        savedStateHandle = SavedStateHandle()
        val tmdbApi = forbiddenApi<TmdbApiService>()
        val tmdb = TmdbService(RuntimeEnvironment.getApplication(), tmdbApi)
        vm = SearchViewModel(app.repository, TmdbMetadataService(tmdbApi, tmdb), tmdb, savedStateHandle)
    }
    @After fun cleanup() { testTmdbService?.clearApiKey(); vm.viewModelScope.cancel(); app.close() }
    private fun finishSearch() {
        ShadowLooper.idleMainLooper(); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        awaitAppState { !vm.state.value.isLoading }
    }
    @Test fun shortAndWhitespaceQueriesDoNotRequestNetwork() {
        listOf("", "a", "ab", "   ab   ").forEach { query ->
            vm.onQueryChange(query)
            assertEquals(query, vm.state.value.query); assertFalse(vm.state.value.isLoading)
        }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun explicitSubmitSearchesTwoCharacterTitlesWhileTypingStillWaitsForThree() {
        vm.onQueryChange("it")
        finishSearch()
        assertTrue("two-character edits should not fan out provider requests", api.calls.isEmpty())
        assertEquals("it", vm.state.value.query)
        assertFalse(vm.state.value.hasSubmittedCurrentQuery)

        vm.submitQuery()
        finishSearch()

        assertEquals(2, api.calls.size)
        assertTrue(api.calls.all { "search=it.json" in it })
        assertEquals(2, vm.state.value.results.size)
        assertTrue(vm.state.value.resultsAreCurrent)
        assertTrue(vm.state.value.hasSubmittedCurrentQuery)
    }

    @Test fun editingBelowAutomaticThresholdKeepsPreviousCardsVisibleButNonCurrent() {
        vm.onQueryChange("Old Title")
        finishSearch()
        val previous = vm.state.value.results

        vm.onQueryChange("it")

        assertEquals(previous, vm.state.value.results)
        assertEquals("Old Title", vm.state.value.resultsQuery)
        assertFalse(vm.state.value.resultsAreCurrent)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasSubmittedCurrentQuery)
    }

    @Test fun mediaFilterChangesPresentationWithoutRepeatingProviderRequests() {
        vm.onQueryChange("Old Title")
        finishSearch()
        val requestCount = api.calls.size

        vm.setMediaFilter(SearchMediaFilter.SERIES)

        assertEquals(SearchMediaFilter.SERIES, vm.state.value.mediaFilter)
        assertEquals(2, vm.state.value.results.size)
        assertEquals(1, vm.state.value.series.size)
        assertEquals(requestCount, api.calls.size)
    }

    @Test fun retryReissuesTheSameQueryAndRecentShortQueryCanBeSelected() {
        vm.configureSearchProfile(73)
        vm.onQueryChange("it")
        vm.submitQuery()
        finishSearch()
        assertEquals(listOf("it"), vm.state.value.recentSearches)

        api.calls.clear()
        vm.clearSearch()
        vm.selectRecentSearch("it")
        finishSearch()

        assertEquals("it", vm.state.value.query)
        assertEquals(2, api.calls.size)
        assertTrue(api.calls.all { "search=it.json" in it })
    }

    @Test fun retryRecoversFromAProviderOutageWithoutChangingTheQuery() {
        api.failedUrls += "/movie/"
        api.failedUrls += "/series/"
        vm.onQueryChange("Batman")
        finishSearch()
        assertEquals(SearchViewModel.SearchError.REQUEST_FAILED, vm.state.value.error)

        api.failedUrls.clear()
        vm.retrySearch()
        finishSearch()

        assertEquals("Batman", vm.state.value.query)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.resultsAreCurrent)
        assertEquals(2, vm.state.value.results.size)
    }
    @Test fun searchSeparatesTypesAndDeduplicatesWithinEachType() {
        vm.onQueryChange("Old Title"); assertTrue(vm.state.value.isLoading)
        finishSearch()
        assertEquals(2, vm.state.value.results.size)
        assertEquals("movie", vm.state.value.movies.single().kind.keyPart)
        assertEquals("series", vm.state.value.series.single().kind.keyPart)
        assertEquals("Old Title", vm.state.value.query); assertNull(vm.state.value.error)
    }
    @Test fun partialCinemetaFailureKeepsResultsAndExposesProviderFailure() {
        vm.configureSearchProfile(73)
        api.failedUrls += "/series/"
        vm.onQueryChange("Old Title")
        finishSearch()

        assertEquals(1, vm.state.value.movies.size)
        assertTrue(vm.state.value.series.isEmpty())
        assertTrue(vm.state.value.failedProviders.contains(SearchViewModel.SearchProvider.CINEMETA))
        assertEquals(listOf("Old Title"), vm.state.value.recentSearches)
    }
    @Test fun changingQueryKeepsPreviousResultsVisibleButMarksThemStaleUntilReplacementArrives() {
        vm.onQueryChange("Old Title"); finishSearch()
        val previousResults = vm.state.value.results
        assertTrue(vm.state.value.resultsAreCurrent)

        vm.onQueryChange("Newest")
        assertEquals(previousResults, vm.state.value.results)
        assertFalse(vm.state.value.resultsAreCurrent)
        assertEquals("Old Title", vm.state.value.resultsQuery)
        assertTrue(vm.state.value.isLoading)

        finishSearch()
        assertTrue(vm.state.value.results.all { it.sourceId == "new" })
        assertTrue(vm.state.value.resultsAreCurrent)
        assertEquals("Newest", vm.state.value.resultsQuery)
    }

    @Test fun totalProviderFailurePreservesLastGoodResultsAndReportsFailure() {
        vm.onQueryChange("Old Title"); finishSearch()
        val previousResults = vm.state.value.results
        api.failedUrls += "/movie/"
        api.failedUrls += "/series/"

        vm.onQueryChange("Newest")
        finishSearch()

        assertEquals(previousResults, vm.state.value.results)
        assertEquals("Old Title", vm.state.value.resultsQuery)
        assertFalse(vm.state.value.resultsAreCurrent)
        assertEquals(SearchViewModel.SearchError.REQUEST_FAILED, vm.state.value.error)
    }
    @Test fun rapidTypingCancelsDebounceForAllEarlierQueries() {
        vm.onQueryChange("Old Title"); vm.onQueryChange("Newest")
        finishSearch()
        assertEquals(2, api.calls.size); assertTrue(api.calls.all { "Newest" in it })
    }
    @Test fun clearCancelsPendingSearchAndResetsAllState() {
        vm.onQueryChange("Old Title"); vm.clearSearch(); finishSearch()
        assertEquals(SearchViewModel.SearchState(), vm.state.value); assertTrue(api.calls.isEmpty())
    }
    @Test fun searchDefaultsAndClearsToMoviesOnly() {
        assertEquals(SearchMediaFilter.MOVIES, vm.state.value.mediaFilter)
        vm.setMediaFilter(SearchMediaFilter.SERIES)
        vm.clearSearch()
        assertEquals(SearchMediaFilter.MOVIES, vm.state.value.mediaFilter)
    }
    @Test fun newSearchSessionClearsButSameSessionRetainsTypedQuery() {
        assertTrue(vm.beginSearchSession(1)); vm.onQueryChange("ab")
        assertFalse(vm.beginSearchSession(1)); assertEquals("ab", vm.state.value.query)
        assertTrue(vm.beginSearchSession(2)); assertEquals(SearchViewModel.SearchState(), vm.state.value)
    }
    @Test fun keyboardAppendDeleteAndEmptyDeleteAreSafe() {
        vm.removeCharacter(); assertEquals("", vm.state.value.query)
        vm.appendCharacter("a"); vm.appendCharacter("b"); assertEquals("ab", vm.state.value.query)
        vm.removeCharacter(); assertEquals("a", vm.state.value.query)
        vm.removeCharacter(); vm.removeCharacter(); assertEquals("", vm.state.value.query)
    }

    @Test fun recentSearchesAreDeduplicatedProfileScopedRestoredAndClearable() {
        vm.configureSearchProfile(73)
        vm.onQueryChange("Old Title")
        finishSearch()
        vm.onQueryChange("old title")
        finishSearch()
        vm.onQueryChange("Newest")
        finishSearch()

        assertEquals(listOf("Newest", "old title"), vm.state.value.recentSearches)
        vm.configureSearchProfile(74)
        assertTrue(vm.state.value.recentSearches.isEmpty())
        vm.configureSearchProfile(73)
        assertEquals(listOf("Newest", "old title"), vm.state.value.recentSearches)

        vm.clearRecentSearches()
        assertTrue(vm.state.value.recentSearches.isEmpty())
        vm.configureSearchProfile(74)
        vm.configureSearchProfile(73)
        assertTrue(vm.state.value.recentSearches.isEmpty())
    }

    @Test fun naturalLanguageSearchLoadsNextTmdbPageWithoutReplacingCurrentResults() {
        val requestedPages = Collections.synchronizedList(mutableListOf<Int>())
        val tmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, args ->
            if (method.name != "discoverMovies") error("Unexpected TMDB request: ${method.name}")
            val page = args!![2] as Int
            requestedPages += page
            val id = 700 + page
            Response.success(200, TmdbDiscoverResponse(
                page = page,
                totalPages = 2,
                results = listOf(TmdbDiscoverResult(
                    id = id,
                    title = "Popular Thriller $page",
                    posterPath = "/$id.jpg",
                    originalLanguage = "en",
                    voteAverage = 8.0,
                    voteCount = 100
                ))
            ))
        } as TmdbApiService
        val tmdbService = TmdbService(RuntimeEnvironment.getApplication(), tmdbApi)
        com.saab.tv.data.security.SecurePreferences.create(
            RuntimeEnvironment.getApplication(), "tmdb_credentials", "saabtv_tmdb_master_key"
        ).preferences.edit().putString("api_key", "test-key").apply()
        assertTrue("The test TMDB credential must be available", tmdbService.hasApiKey())
        testTmdbService = tmdbService
        vm = SearchViewModel(app.repository, TmdbMetadataService(tmdbApi, tmdbService), tmdbService, savedStateHandle)
        vm.configureTmdbSearch(enabled = true, language = "en")

        vm.onQueryChange("popular thriller movies")
        finishSearch()
        assertTrue(vm.state.value.hasMoreResults)
        assertTrue(vm.state.value.movies.any { it.title == "Popular Thriller 1" })

        vm.loadMoreResults()
        awaitAppState {
            !vm.state.value.isLoading && vm.state.value.movies.any { it.title == "Popular Thriller 2" }
        }

        assertEquals(listOf(1, 2), requestedPages.toList())
        assertFalse(vm.state.value.hasMoreResults)
        assertTrue(vm.state.value.movies.any { it.title == "Popular Thriller 1" })
    }

}
