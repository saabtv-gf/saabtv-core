package com.saab.tv.ui.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.testing.*
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.model.tmdb.TmdbExternalIdsResponse
import com.saab.tv.data.tmdb.TmdbMetaPreview
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowLooper
import retrofit2.Response
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SearchViewModelRegressionTest {
    private class SearchApi : StremioApiService {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun getCatalog(url: String): CatalogResponse {
            calls += url
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
    @Before fun setup() {
        api = SearchApi(); app = OfflineAppFixture(RuntimeEnvironment.getApplication(), api)
        val tmdbApi = forbiddenApi<TmdbApiService>()
        val tmdb = TmdbService(RuntimeEnvironment.getApplication(), tmdbApi)
        vm = SearchViewModel(app.repository, TmdbMetadataService(tmdbApi, tmdb), tmdb)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); app.close() }
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
    @Test fun searchSeparatesTypesAndDeduplicatesWithinEachType() {
        vm.onQueryChange("Old Title"); assertTrue(vm.state.value.isLoading)
        finishSearch()
        assertEquals(2, vm.state.value.results.size)
        assertEquals("movie", vm.state.value.movies.single().type)
        assertEquals("series", vm.state.value.series.single().type)
        assertEquals("Old Title", vm.state.value.query); assertNull(vm.state.value.error)
    }
    @Test fun changingQueryImmediatelyClearsOldSelectableResults() {
        vm.onQueryChange("Old Title"); finishSearch()
        vm.onQueryChange("Newest")
        assertTrue(vm.state.value.results.isEmpty()); assertTrue(vm.state.value.movies.isEmpty())
        assertTrue(vm.state.value.series.isEmpty()); assertTrue(vm.state.value.isLoading)
        finishSearch(); assertTrue(vm.state.value.results.all { it.id == "new" })
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

    @Test fun tmdbSearchResolvesCandidateIdsWithBoundedConcurrency() {
        val activeLookups = AtomicInteger()
        val peakLookups = AtomicInteger()
        val tmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getMovieExternalIds" -> {
                    val active = activeLookups.incrementAndGet()
                    peakLookups.updateAndGet { maxOf(it, active) }
                    try {
                        Thread.sleep(30)
                        Response.success(200, TmdbExternalIdsResponse(args!![0] as Int, "tt${args[0]}"))
                    } finally {
                        activeLookups.decrementAndGet()
                    }
                }
                else -> error("Unexpected TMDB API call: ${method.name}")
            }
        } as TmdbApiService
        val tmdb = TmdbService(RuntimeEnvironment.getApplication(), tmdbApi)
        val tmdbMetadata = TmdbMetadataService(tmdbApi, tmdb)
        vm = SearchViewModel(app.repository, tmdbMetadata, tmdb)
        val candidates = (1..15).map { id ->
            TmdbMetaPreview(id, "movie", "Thriller $id", "/$id.jpg", null, null, null, null)
        }
        val resolved = runBlocking { vm.resolveTmdbCandidates(candidates) }

        assertEquals(15, resolved.size)
        assertTrue(resolved.all { it.id.startsWith("tt") })
        assertTrue("ID lookups should overlap but stay capped at three", peakLookups.get() in 2..3)
    }
}
