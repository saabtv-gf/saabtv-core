package com.saab.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbNaturalQuery
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.domain.schema.toCanonicalProjection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val tmdbMetadata: TmdbMetadataService,
    private val tmdbService: TmdbService,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    enum class SearchError { REQUEST_FAILED }
    enum class SearchProvider { CINEMETA, TMDB }

    private data class ProviderBatch(val items: List<TitleCardProjection>, val failed: Boolean = false)

    data class SearchState(
        val query: String = "", val results: List<TitleCardProjection> = emptyList(),
        val movies: List<TitleCardProjection> = emptyList(), val series: List<TitleCardProjection> = emptyList(),
        val isLoading: Boolean = false, val error: SearchError? = null,
        val recentSearches: List<String> = emptyList(),
        val failedProviders: Set<SearchProvider> = emptySet(),
        val resultsQuery: String? = null,
        val mediaFilter: SearchMediaFilter = SearchMediaFilter.MOVIES,
        val searchedQuery: String? = null,
        val tmdbSearchAvailable: Boolean = false,
        val hasMoreResults: Boolean = false,
        val isLoadingMoreResults: Boolean = false,
        val loadMoreResultsFailed: Boolean = false
    ) {
        val resultsAreCurrent: Boolean get() = resultsQuery == query
        val hasSubmittedCurrentQuery: Boolean get() = searchedQuery == query.trim()
    }
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private var searchJob: Job? = null
    private var tmdbPageJob: Job? = null
    private var searchGeneration = 0L
    private var tmdbSearchIntent: TmdbNaturalQuery? = null
    private var tmdbSearchPage = 1
    private var tmdbSearchHasMore = false
    private var tmdbPageLoading = false
    private var activeSearchSessionId: Long? = null
    private var tmdbSearchEnabled = false
    private var tmdbSearchLanguage = "en"
    private var activeProfileId: Int? = null
    private var recentSearches = emptyList<String>()

    fun configureSearchProfile(profileId: Int?) {
        if (activeProfileId == profileId) return
        activeProfileId = profileId
        recentSearches = profileId?.let { id ->
            savedStateHandle.get<ArrayList<String>>(recentSearchesKey(id))?.take(MAX_RECENT_SEARCHES).orEmpty()
        }.orEmpty()
        // Do not carry another profile's query or results into this profile.
        clearSearch()
        _state.value = _state.value.copy(recentSearches = recentSearches)
    }

    fun clearRecentSearches() {
        activeProfileId?.let { savedStateHandle.remove<ArrayList<String>>(recentSearchesKey(it)) }
        recentSearches = emptyList()
        _state.value = _state.value.copy(recentSearches = emptyList())
    }

    fun configureTmdbSearch(enabled: Boolean, language: String) {
        tmdbSearchEnabled = enabled && tmdbService.hasApiKey()
        tmdbSearchLanguage = language.ifBlank { "en" }
        _state.value = _state.value.copy(tmdbSearchAvailable = tmdbSearchEnabled)
    }

    fun beginSearchSession(sessionId: Long): Boolean {
        if (activeSearchSessionId == sessionId) return false
        activeSearchSessionId = sessionId
        clearSearch()
        return true
    }
    fun clearSearch() {
        onQueryChange("")
        setMediaFilter(SearchMediaFilter.MOVIES)
    }

    fun setMediaFilter(filter: SearchMediaFilter) {
        if (_state.value.mediaFilter != filter) _state.value = _state.value.copy(mediaFilter = filter)
    }

    fun appendCharacter(char: String) = onQueryChange(_state.value.query + char)
    fun removeCharacter() = onQueryChange(_state.value.query.dropLast(1))

    fun onQueryChange(newQuery: String) = startSearch(newQuery, submitShortQuery = false, immediate = false)

    /** Explicit keyboard/remote submit supports two-character exact titles. */
    fun submitQuery() {
        val current = _state.value
        if (current.query.trim().length < 2 || current.isLoading) return
        if (current.resultsAreCurrent && current.error == null && current.failedProviders.isEmpty()) return
        startSearch(current.query, submitShortQuery = true, immediate = true)
    }

    fun retrySearch() {
        val current = _state.value
        if (current.query.trim().length >= 2 && !current.isLoading) {
            startSearch(current.query, submitShortQuery = true, immediate = true)
        }
    }

    fun selectRecentSearch(query: String) {
        if (query.trim().length in 2 until 3) startSearch(query, submitShortQuery = true, immediate = true)
        else onQueryChange(query)
    }

    private fun startSearch(newQuery: String, submitShortQuery: Boolean, immediate: Boolean) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        tmdbPageJob?.cancel()
        tmdbSearchIntent = null
        tmdbSearchPage = 1
        tmdbSearchHasMore = false
        tmdbPageLoading = false
        val normalizedQuery = newQuery.trim()
        if (normalizedQuery.length < 3 && !(submitShortQuery && normalizedQuery.length >= 2)) {
            if (newQuery.isBlank()) {
                _state.value = SearchState(
                    query = newQuery,
                    recentSearches = recentSearches,
                    tmdbSearchAvailable = tmdbSearchEnabled
                )
            } else {
                // Keep the previous batch in place while the user is editing. It is
                // visually marked stale and the screen disables all title actions.
                _state.value = _state.value.copy(
                    query = newQuery,
                    isLoading = false,
                    error = null,
                    failedProviders = emptySet(),
                    searchedQuery = null,
                    hasMoreResults = false,
                    isLoadingMoreResults = false,
                    loadMoreResultsFailed = false,
                    recentSearches = recentSearches
                )
            }
            return
        }
        // Keep the last successful batch on screen during debounce/network work.
        // SearchScreen marks it stale and prevents opening titles/actions until the
        // batch belongs to this query, so continuity never risks the wrong title.
        _state.value = _state.value.copy(
            query = newQuery,
            isLoading = true,
            error = null,
            failedProviders = emptySet(),
            hasMoreResults = false,
            isLoadingMoreResults = false,
            loadMoreResultsFailed = false,
            recentSearches = recentSearches,
            searchedQuery = normalizedQuery,
            tmdbSearchAvailable = tmdbSearchEnabled
        )
        searchJob = viewModelScope.launch {
            if (!immediate) delay(250)
            try {
                com.saab.tv.AppDiagnostics.event("Search", "Request Started")
                val intent = if (tmdbSearchEnabled) TmdbNaturalQuery.parse(normalizedQuery) else null
                var tmdbPageHasMore = false
                val catalogRequest = async {
                    try {
                        val outcome = repository.searchMoviesWithStatus(normalizedQuery)
                        ProviderBatch(
                            items = outcome.items.mapNotNull { it.toCanonicalProjection() },
                            failed = outcome.failedTypes.isNotEmpty()
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ProviderBatch(emptyList(), failed = true)
                    }
                }
                val tmdbRequest = intent?.let { queryIntent ->
                    async {
                        try {
                            tmdbMetadata.discoverByIntentPage(
                                queryIntent,
                                tmdbSearchLanguage,
                                page = 1,
                                maxItems = 15
                            ).also { tmdbPageHasMore = it.hasMore }
                                .let { ProviderBatch(it.items, failed = it.failed) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ProviderBatch(emptyList(), failed = true)
                        }
                    }
                }

                var catalogItems = emptyList<TitleCardProjection>()
                var tmdbItems = emptyList<TitleCardProjection>()
                var catalogDone = false
                var tmdbDone = tmdbRequest == null
                var failedProviders = emptySet<SearchProvider>()
                val expectedProviders = buildSet {
                    add(SearchProvider.CINEMETA)
                    if (tmdbRequest != null) add(SearchProvider.TMDB)
                }
                suspend fun publishResults() {
                    if (_state.value.query != newQuery || generation != searchGeneration) return
                    val allProvidersDone = catalogDone && tmdbDone
                    if (failedProviders.containsAll(expectedProviders) &&
                        catalogItems.isEmpty() && tmdbItems.isEmpty()
                    ) {
                        _state.value = _state.value.copy(
                            isLoading = !allProvidersDone,
                            error = if (allProvidersDone) SearchError.REQUEST_FAILED else null,
                            failedProviders = failedProviders
                        )
                        return
                    }
                    // Deduplicate provider results using the same verified alias policy,
                    // then publish only the provider-independent projection in VM state.
                    val results = repository.canonicalizeCatalogItems(
                        (tmdbItems + catalogItems).map(TitleCardProjection::asMetaItem)
                    ).filter { it.type == "movie" || it.type == "series" }
                        .mapNotNull { it.toCanonicalProjection() }
                    if (results.isEmpty() && !allProvidersDone) {
                        _state.value = _state.value.copy(
                            isLoading = true,
                            error = null,
                            failedProviders = failedProviders
                        )
                        return
                    }
                    _state.value = SearchState(
                        query = newQuery,
                        results = results,
                        movies = results.filter { it.kind.keyPart == "movie" },
                        series = results.filter { it.kind.keyPart == "series" },
                        isLoading = !(catalogDone && tmdbDone),
                        error = null,
                        recentSearches = recentSearches,
                        failedProviders = failedProviders,
                        resultsQuery = newQuery,
                        mediaFilter = _state.value.mediaFilter,
                        searchedQuery = normalizedQuery,
                        tmdbSearchAvailable = tmdbSearchEnabled
                    )
                }

                if (tmdbRequest == null) {
                    val batch = catalogRequest.await()
                    catalogItems = batch.items
                    if (batch.failed) failedProviders += SearchProvider.CINEMETA
                    catalogDone = true
                    publishResults()
                } else {
                    val first = select<Pair<Boolean, ProviderBatch>> {
                        catalogRequest.onAwait { true to it }
                        tmdbRequest.onAwait { false to it }
                    }
                    if (first.first) {
                        catalogItems = first.second.items
                        if (first.second.failed) failedProviders += SearchProvider.CINEMETA
                        catalogDone = true
                    } else {
                        tmdbItems = first.second.items
                        if (first.second.failed) failedProviders += SearchProvider.TMDB
                        tmdbDone = true
                    }
                    publishResults()

                    if (!catalogDone) {
                        val batch = catalogRequest.await()
                        catalogItems = batch.items
                        if (batch.failed) failedProviders += SearchProvider.CINEMETA
                        catalogDone = true
                        publishResults()
                    }
                    if (!tmdbDone) {
                        val batch = tmdbRequest.await()
                        tmdbItems = batch.items
                        if (batch.failed) failedProviders += SearchProvider.TMDB
                        tmdbDone = true
                        publishResults()
                    }
                }

                if (_state.value.query != newQuery) return@launch
                if (generation != searchGeneration) return@launch
                if (intent != null) {
                    tmdbSearchIntent = intent
                    tmdbSearchPage = 1
                    tmdbSearchHasMore = tmdbPageHasMore
                }
                _state.value = _state.value.copy(hasMoreResults = tmdbSearchHasMore)
                rememberRecentSearch(newQuery)
                val results = _state.value.results
                com.saab.tv.AppDiagnostics.event("Search", "Request Completed", "results=${results.size}")
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("Search", "Request Failed", failure)
                if (_state.value.query == newQuery) _state.value = _state.value.copy(
                    isLoading = false,
                    error = SearchError.REQUEST_FAILED,
                    recentSearches = recentSearches
                )
            }
        }
    }

    /** Load the next natural-language TMDB page as a result rail approaches its end. */
    fun loadMoreResults() {
        val intent = tmdbSearchIntent ?: return
        if (!tmdbSearchEnabled || !tmdbSearchHasMore || tmdbPageLoading) return
        val query = _state.value.query
        val generation = searchGeneration
        tmdbPageLoading = true
        _state.value = _state.value.copy(
            isLoading = true,
            isLoadingMoreResults = true,
            loadMoreResultsFailed = false
        )
        tmdbPageJob = viewModelScope.launch {
            try {
                var pageNumber = tmdbSearchPage
                var hasMore = true
                var attempts = 0
                val additions = mutableListOf<TitleCardProjection>()
                val knownKeys = _state.value.results.mapTo(mutableSetOf()) { it.presentationKey }
                do {
                    val page = tmdbMetadata.discoverByIntentPage(
                        intent,
                        tmdbSearchLanguage,
                        page = pageNumber + 1,
                        maxItems = 15
                    )
                    if (generation != searchGeneration || _state.value.query != query) return@launch
                    if (page.failed) {
                        com.saab.tv.AppDiagnostics.event("Search", "Next Page Failed", "provider=TMDB")
                        _state.value = _state.value.copy(
                            isLoading = false,
                            isLoadingMoreResults = false,
                            loadMoreResultsFailed = true,
                            failedProviders = _state.value.failedProviders + SearchProvider.TMDB
                        )
                        return@launch
                    }
                    val canonicalPage = repository.canonicalizeCatalogItems(
                        page.items.map(TitleCardProjection::asMetaItem)
                    ).filter { it.type == "movie" || it.type == "series" }
                        .mapNotNull { it.toCanonicalProjection() }
                    canonicalPage.forEach { item ->
                        if (knownKeys.add(item.presentationKey)) additions += item
                    }
                    pageNumber = page.page
                    hasMore = page.hasMore
                    attempts++
                    // Skip a few pages containing only provider aliases already in the first page.
                } while (additions.isEmpty() && hasMore && attempts < 3)

                val results = _state.value.results + additions
                _state.value = _state.value.copy(
                    results = results,
                    movies = results.filter { it.kind.keyPart == "movie" },
                    series = results.filter { it.kind.keyPart == "series" },
                    isLoading = false,
                    hasMoreResults = hasMore,
                    isLoadingMoreResults = false,
                    loadMoreResultsFailed = false,
                    failedProviders = _state.value.failedProviders - SearchProvider.TMDB
                )
                tmdbSearchPage = pageNumber
                tmdbSearchHasMore = hasMore
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("Search", "Next Page Failed", failure)
                if (generation == searchGeneration && _state.value.query == query) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isLoadingMoreResults = false,
                        loadMoreResultsFailed = true,
                        failedProviders = _state.value.failedProviders + SearchProvider.TMDB
                    )
                }
            } finally {
                if (generation == searchGeneration) tmdbPageLoading = false
            }
        }
    }

    private fun rememberRecentSearch(query: String) {
        val profileId = activeProfileId ?: return
        val normalized = query.trim().takeIf(String::isNotEmpty) ?: return
        recentSearches = (listOf(normalized) + recentSearches.filterNot { it.equals(normalized, ignoreCase = true) })
            .take(MAX_RECENT_SEARCHES)
        savedStateHandle[recentSearchesKey(profileId)] = ArrayList(recentSearches)
        _state.value = _state.value.copy(recentSearches = recentSearches)
    }

    private fun recentSearchesKey(profileId: Int) = "search.recent.$profileId"

    private companion object {
        const val MAX_RECENT_SEARCHES = 8
    }
}
