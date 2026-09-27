package com.saab.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: AddonRepository) : ViewModel() {
    data class SearchState(
        val query: String = "", val results: List<MetaItem> = emptyList(),
        val movies: List<MetaItem> = emptyList(), val series: List<MetaItem> = emptyList(),
        val isLoading: Boolean = false, val error: String? = null
    )
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private var searchJob: Job? = null
    private var activeSearchSessionId: Long? = null

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
            delay(350)
            try {
                com.saab.tv.AppDiagnostics.event("Search", "Request Started")
                val results = repository.searchMovies(newQuery.trim())
                    .filter { it.type == "movie" || it.type == "series" }
                    .distinctBy { it.type to it.id }
                if (_state.value.query != newQuery) return@launch
                com.saab.tv.AppDiagnostics.event("Search", "Request Completed", "results=${results.size}")
                _state.value = SearchState(
                    query = newQuery, results = results,
                    movies = results.filter { it.type == "movie" },
                    series = results.filter { it.type == "series" }
                )
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
