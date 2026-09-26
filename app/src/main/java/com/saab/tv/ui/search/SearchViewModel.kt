package com.saab.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.repository.AddonRepository.DiscoverCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AddonRepository
) : ViewModel() {

    companion object {
        private const val DISCOVER_INITIAL_LIMIT = 100
        private const val DISCOVER_BATCH_SIZE = 50
    }

    data class SearchState(
        val query: String = "",
        val results: List<MetaItem> = emptyList(),
        val movies: List<MetaItem> = emptyList(),
        val series: List<MetaItem> = emptyList(),
        val isLoading: Boolean = false,

        // Discover
        val discoverCatalogs: List<DiscoverCatalog> = emptyList(),
        val availableTypes: List<String> = emptyList(),
        val selectedType: String = "movie",
        val availableCatalogs: List<DiscoverCatalog> = emptyList(),
        val selectedCatalog: DiscoverCatalog? = null,
        val availableGenres: List<String> = emptyList(),
        val selectedGenre: String? = null,
        val discoverItems: List<MetaItem> = emptyList(),
        val isDiscoverLoading: Boolean = false,
        val hasMoreDiscover: Boolean = true
    )

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    private var searchJob: Job? = null
    private var discoverJob: Job? = null
    private var loadMoreDiscoverJob: Job? = null
    private var discoverGeneration = 0L
    private var isLoadingMoreDiscover = false
    private var pendingDiscoverItems = mutableListOf<MetaItem>()
    private var allFetchedIds = mutableSetOf<String>()
    private var activeSearchSessionId: Long? = null

    // Discover grid scroll position — survives navigation to details and back
    var discoverScrollIndex: Int = 0
        private set
    var discoverScrollOffset: Int = 0
        private set

    fun updateDiscoverScrollPosition(index: Int, offset: Int) {
        discoverScrollIndex = index
        discoverScrollOffset = offset
    }

    init {
        loadDiscoverCatalogs()
    }

    // ═══════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════

    /** Clears a query once when Search is opened from another main navigation tab. */
    fun beginSearchSession(sessionId: Long): Boolean {
        if (activeSearchSessionId == sessionId) return false
        activeSearchSessionId = sessionId
        onQueryChange("")
        return true
    }

    fun clearSearch() {
        onQueryChange("")
    }

    fun appendCharacter(char: String) {
        val current = _state.value.query
        onQueryChange(current + char)
    }

    fun removeCharacter() {
        val current = _state.value.query
        if (current.isNotEmpty()) {
            onQueryChange(current.dropLast(1))
        }
    }

    fun onQueryChange(newQuery: String) {
        _state.value = _state.value.copy(query = newQuery)
        searchJob?.cancel()

        if (newQuery.length < 3) {
            _state.value = _state.value.copy(
                results = emptyList(), movies = emptyList(),
                series = emptyList(), isLoading = false
            )
            return
        }

        searchJob = viewModelScope.launch {
            delay(500)
            performSearch(newQuery)
        }
    }

    private suspend fun performSearch(query: String) {
        _state.value = _state.value.copy(isLoading = true)
        try {
            val results = repository.searchMovies(query)
            if (_state.value.query != query) return
            val movies = results.filter { it.type == "movie" }
            val series = results.filter { it.type == "series" }
            _state.value = _state.value.copy(
                results = results, movies = movies,
                series = series, isLoading = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (_state.value.query == query) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    // ═══════════════════════════════════════
    // DISCOVER
    // ═══════════════════════════════════════

    private fun loadDiscoverCatalogs() {
        viewModelScope.launch {
            try {
                val catalogs = repository.getDiscoverCatalogs()
                    .filter { it.type.isNotBlank() && it.catalogId.isNotBlank() }
                if (catalogs.isEmpty()) return@launch

                val types = catalogs.map { it.type }.distinct().sorted()
                val defaultType = if ("movie" in types) "movie" else types.firstOrNull()
                    ?: return@launch

                _state.value = _state.value.copy(discoverCatalogs = catalogs)
                selectType(defaultType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    discoverCatalogs = emptyList(),
                    isDiscoverLoading = false,
                    hasMoreDiscover = false
                )
            }
        }
    }

    fun selectType(type: String) {
        val allCatalogs = _state.value.discoverCatalogs
        val filtered = allCatalogs.filter { it.type == type }
        val defaultCatalog = filtered.firstOrNull()

        resetDiscoverBuffer()
        _state.value = _state.value.copy(
            selectedType = type,
            availableTypes = allCatalogs.map { it.type }.distinct().sorted(),
            availableCatalogs = filtered,
            selectedCatalog = defaultCatalog,
            availableGenres = buildGenreList(defaultCatalog),
            selectedGenre = null,
            discoverItems = emptyList(),
            isDiscoverLoading = false,
            hasMoreDiscover = true
        )
        loadDiscoverContent()
    }

    fun selectCatalog(catalog: DiscoverCatalog) {
        resetDiscoverBuffer()
        _state.value = _state.value.copy(
            selectedCatalog = catalog,
            availableGenres = buildGenreList(catalog),
            selectedGenre = null,
            discoverItems = emptyList(),
            isDiscoverLoading = false,
            hasMoreDiscover = true
        )
        loadDiscoverContent()
    }

    fun selectGenre(genre: String) {
        val actualGenre = if (genre == "All") null else genre
        resetDiscoverBuffer()
        _state.value = _state.value.copy(
            selectedGenre = actualGenre,
            discoverItems = emptyList(),
            isDiscoverLoading = false,
            hasMoreDiscover = true
        )
        loadDiscoverContent()
    }

    private fun resetDiscoverBuffer() {
        discoverGeneration++
        discoverJob?.cancel()
        loadMoreDiscoverJob?.cancel()
        isLoadingMoreDiscover = false
        pendingDiscoverItems.clear()
        allFetchedIds.clear()
        discoverScrollIndex = 0
        discoverScrollOffset = 0
    }

    private fun buildGenreList(catalog: DiscoverCatalog?): List<String> {
        val genres = catalog?.genres ?: emptyList()
        return if (genres.isNotEmpty()) listOf("All") + genres else emptyList()
    }

    private fun loadDiscoverContent() {
        discoverJob?.cancel()
        val catalog = _state.value.selectedCatalog ?: return
        val genre = _state.value.selectedGenre
        val generation = discoverGeneration
        discoverJob = viewModelScope.launch {
            _state.value = _state.value.copy(isDiscoverLoading = true)

            try {
                val items = repository.fetchDiscoverPage(
                    transportUrl = catalog.transportUrl,
                    type = catalog.type,
                    catalogId = catalog.catalogId,
                    genre = genre,
                    skip = 0
                )

                if (generation != discoverGeneration) return@launch

                // Track all fetched IDs for deduplication
                allFetchedIds.clear()
                items.forEach { allFetchedIds.add("${it.type}:${it.id}") }

                // Batch: show first DISCOVER_INITIAL_LIMIT, buffer the rest
                val visible = items.take(DISCOVER_INITIAL_LIMIT)
                pendingDiscoverItems.clear()
                if (items.size > DISCOVER_INITIAL_LIMIT) {
                    pendingDiscoverItems.addAll(items.drop(DISCOVER_INITIAL_LIMIT))
                }

                val hasMore = pendingDiscoverItems.isNotEmpty() ||
                        (catalog.supportsSkip && items.isNotEmpty())

                _state.value = _state.value.copy(
                    discoverItems = visible,
                    hasMoreDiscover = hasMore,
                    isDiscoverLoading = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == discoverGeneration) {
                    _state.value = _state.value.copy(isDiscoverLoading = false)
                }
            }
        }
    }

    fun loadMoreDiscover() {
        if (isLoadingMoreDiscover || !_state.value.hasMoreDiscover) return

        // First: reveal items from the pending buffer (no API call needed)
        if (pendingDiscoverItems.isNotEmpty()) {
            val batch = pendingDiscoverItems.take(DISCOVER_BATCH_SIZE)
            pendingDiscoverItems = pendingDiscoverItems.drop(DISCOVER_BATCH_SIZE).toMutableList()

            val catalog = _state.value.selectedCatalog
            val hasMore = pendingDiscoverItems.isNotEmpty() ||
                    (catalog?.supportsSkip == true)

            _state.value = _state.value.copy(
                discoverItems = _state.value.discoverItems + batch,
                hasMoreDiscover = hasMore
            )
            return
        }

        // Pending buffer empty — fetch next page from API
        val catalog = _state.value.selectedCatalog ?: return
        if (!catalog.supportsSkip) {
            _state.value = _state.value.copy(hasMoreDiscover = false)
            return
        }

        isLoadingMoreDiscover = true
        val genre = _state.value.selectedGenre
        val generation = discoverGeneration
        loadMoreDiscoverJob?.cancel()
        loadMoreDiscoverJob = viewModelScope.launch {
            try {
                // Skip = total fetched count (visible + already consumed from pending)
                val totalFetched = allFetchedIds.size
                val newItems = repository.fetchDiscoverPage(
                    transportUrl = catalog.transportUrl,
                    type = catalog.type,
                    catalogId = catalog.catalogId,
                    genre = genre,
                    skip = totalFetched
                )
                if (generation != discoverGeneration) return@launch
                // Deduplicate against all previously fetched items
                val newUniqueItems = newItems.filter { item ->
                    allFetchedIds.add("${item.type}:${item.id}")
                }
                if (newUniqueItems.isNotEmpty()) {
                    // Show first batch immediately, buffer the rest
                    val batch = newUniqueItems.take(DISCOVER_BATCH_SIZE)
                    if (newUniqueItems.size > DISCOVER_BATCH_SIZE) {
                        pendingDiscoverItems.addAll(newUniqueItems.drop(DISCOVER_BATCH_SIZE))
                    }
                    val hasMore = pendingDiscoverItems.isNotEmpty() ||
                            (catalog.supportsSkip && newItems.isNotEmpty())
                    _state.value = _state.value.copy(
                        discoverItems = _state.value.discoverItems + batch,
                        hasMoreDiscover = hasMore
                    )
                } else {
                    _state.value = _state.value.copy(hasMoreDiscover = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silently fail
            } finally {
                if (generation == discoverGeneration) {
                    isLoadingMoreDiscover = false
                }
            }
        }
    }
}
