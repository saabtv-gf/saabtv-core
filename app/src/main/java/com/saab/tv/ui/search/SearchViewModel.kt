package com.saab.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbMetaPreview
import com.saab.tv.data.tmdb.TmdbNaturalQuery
import com.saab.tv.data.tmdb.TmdbService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.selects.select
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val tmdbMetadata: TmdbMetadataService,
    private val tmdbService: TmdbService
) : ViewModel() {
    data class SearchState(
        val query: String = "", val results: List<MetaItem> = emptyList(),
        val movies: List<MetaItem> = emptyList(), val series: List<MetaItem> = emptyList(),
        val isLoading: Boolean = false, val error: String? = null
    )
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private var searchJob: Job? = null
    private var activeSearchSessionId: Long? = null
    private var tmdbSearchEnabled = false
    private var tmdbSearchLanguage = "en"

    fun configureTmdbSearch(enabled: Boolean, language: String) {
        tmdbSearchEnabled = enabled && tmdbService.hasApiKey()
        tmdbSearchLanguage = language.ifBlank { "en" }
    }

    internal suspend fun resolveTmdbCandidates(candidates: List<TmdbMetaPreview>): List<MetaItem> =
        coroutineScope {
            val idLookupLimit = Semaphore(3)
            candidates.map { candidate -> async {
                val imdbId = idLookupLimit.withPermit {
                    tmdbService.tmdbToImdb(candidate.tmdbId, candidate.type)
                } ?: return@async null
                MetaItem(
                    id = imdbId, type = candidate.type, name = candidate.name,
                    poster = candidate.poster, background = candidate.backdrop,
                    description = candidate.description, releaseInfo = candidate.releaseInfo
                )
            } }.awaitAll().filterNotNull()
        }

    fun beginSearchSession(sessionId: Long): Boolean {
        if (activeSearchSessionId == sessionId) return false
        activeSearchSessionId = sessionId
        clearSearch()
        return true
    }
    fun clearSearch() = onQueryChange("")
    fun appendCharacter(char: String) = onQueryChange(_state.value.query + char)
    fun removeCharacter() = onQueryChange(_state.value.query.dropLast(1))

    fun onQueryChange(newQuery: String) {
        searchJob?.cancel()
        // Never leave titles for an old query selectable under a new query.
        _state.value = SearchState(query = newQuery, isLoading = newQuery.trim().length >= 3)
        if (newQuery.trim().length < 3) return
        searchJob = viewModelScope.launch {
            delay(250)
            try {
                com.saab.tv.AppDiagnostics.event("Search", "Request Started")
                val intent = if (tmdbSearchEnabled) TmdbNaturalQuery.parse(newQuery.trim()) else null
                val catalogRequest = async {
                    try { repository.searchMovies(newQuery.trim()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                }
                val tmdbRequest = intent?.let { queryIntent ->
                    async {
                        try {
                            resolveTmdbCandidates(
                                tmdbMetadata.discoverByIntent(queryIntent, tmdbSearchLanguage, 15)
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }

                var catalogItems = emptyList<MetaItem>()
                var tmdbItems = emptyList<MetaItem>()
                var catalogDone = false
                var tmdbDone = tmdbRequest == null
                suspend fun publishResults() {
                    if (_state.value.query != newQuery) return
                    val results = (tmdbItems + catalogItems)
                        .filter { it.type == "movie" || it.type == "series" }
                        .distinctBy { it.type to it.id }
                    _state.value = SearchState(
                        query = newQuery,
                        results = results,
                        movies = results.filter { it.type == "movie" },
                        series = results.filter { it.type == "series" },
                        isLoading = !(catalogDone && tmdbDone)
                    )
                }

                if (tmdbRequest == null) {
                    catalogItems = catalogRequest.await()
                    catalogDone = true
                    publishResults()
                } else {
                    val first = select<Pair<Boolean, List<MetaItem>>> {
                        catalogRequest.onAwait { true to it }
                        tmdbRequest.onAwait { false to it }
                    }
                    if (first.first) {
                        catalogItems = first.second
                        catalogDone = true
                    } else {
                        tmdbItems = first.second
                        tmdbDone = true
                    }
                    publishResults()

                    if (!catalogDone) {
                        catalogItems = catalogRequest.await()
                        catalogDone = true
                        publishResults()
                    }
                    if (!tmdbDone) {
                        tmdbItems = tmdbRequest.await()
                        tmdbDone = true
                        publishResults()
                    }
                }

                if (_state.value.query != newQuery) return@launch
                val results = _state.value.results
                com.saab.tv.AppDiagnostics.event("Search", "Request Completed", "results=${results.size}")
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("Search", "Request Failed", failure)
                if (_state.value.query == newQuery) _state.value = SearchState(
                    query = newQuery, error = "Search Failed. Please Try Again."
                )
            }
        }
    }
}
