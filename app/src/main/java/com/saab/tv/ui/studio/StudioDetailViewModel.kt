package com.saab.tv.ui.studio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.tmdb.TmdbDiscoverRail
import com.saab.tv.data.tmdb.TmdbEntityDetail
import com.saab.tv.data.tmdb.TmdbMetadataService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

private const val MAX_DISCOVER_PAGES = 3

sealed class StudioDetailState {
    data object Loading : StudioDetailState()
    data class Success(
        val entity: TmdbEntityDetail,
        val rails: List<TmdbDiscoverRail>
    ) : StudioDetailState()
    data class Error(val message: String) : StudioDetailState()
}

internal fun StudioDetailState.Success.appendDiscoverPage(
    mediaType: String,
    railType: String,
    page: Int,
    pageItems: List<com.saab.tv.domain.schema.TitleCardProjection>,
    totalPages: Int
): StudioDetailState.Success = copy(rails = rails.map { rail ->
    if (rail.mediaType != mediaType || rail.railType != railType) return@map rail

    val seenIds = rail.items.mapTo(mutableSetOf()) { it.identity.externalId }
    val newItems = pageItems.filter { seenIds.add(it.identity.externalId) }
    rail.copy(
        items = rail.items + newItems,
        currentPage = page,
        hasMore = page < totalPages && page < MAX_DISCOVER_PAGES
    )
})

@HiltViewModel
class StudioDetailViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val entityId: Int = (savedStateHandle.get<String>("entityId") ?: "0").toIntOrNull() ?: 0
    val entityKind: String = savedStateHandle.get<String>("entityKind") ?: "company"
    val entityName: String = java.net.URLDecoder.decode(
        savedStateHandle.get<String>("entityName") ?: "", "UTF-8"
    )
    val sourceType: String = savedStateHandle.get<String>("sourceType") ?: "movie"

    private val _state = MutableStateFlow<StudioDetailState>(StudioDetailState.Loading)
    val state: StateFlow<StudioDetailState> = _state

    private val inFlightLoads = mutableSetOf<String>()

    init {
        loadDetail()
    }

    fun retry() = loadDetail()

    fun loadMoreRail(mediaType: String, railType: String) {
        val currentState = _state.value as? StudioDetailState.Success ?: return
        val rail = currentState.rails.find { it.mediaType == mediaType && it.railType == railType } ?: return
        if (!rail.hasMore || rail.currentPage >= MAX_DISCOVER_PAGES) return

        val loadKey = "$mediaType:$railType"
        if (!inFlightLoads.add(loadKey)) return

        viewModelScope.launch {
            val nextPage = rail.currentPage + 1
            val sortBy = when (railType) {
                "top_rated" -> if (mediaType == "movie") "vote_average.desc" else "vote_average.desc"
                "recent" -> if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
                else -> "popularity.desc"
            }
            try {
                val (items, totalPages) = tmdbMetadataService.fetchDiscover(
                    entityId = entityId, kind = entityKind, mediaType = mediaType,
                    sortBy = sortBy, page = nextPage,
                    voteCountGte = if (railType == "top_rated") 200 else null,
                    dateLte = if (railType == "recent") LocalDate.now().toString() else null
                )
                val latestState = _state.value as? StudioDetailState.Success ?: return@launch
                _state.value = latestState.appendDiscoverPage(
                    mediaType = mediaType,
                    railType = railType,
                    page = nextPage,
                    pageItems = items,
                    totalPages = totalPages
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("TMDB", "Studio Rail Pagination Failed", failure)
            } finally {
                inFlightLoads.remove(loadKey)
            }
        }
    }

    private fun loadDetail() {
        _state.value = StudioDetailState.Loading
        viewModelScope.launch {
            val entity = tmdbMetadataService.fetchEntityDetail(entityId, entityKind)
            if (entity == null) {
                _state.value = StudioDetailState.Error("Failed to load details")
                return@launch
            }

            val railConfigs = buildList {
                val primaryType = if (sourceType == "series") "tv" else "movie"
                val secondaryType = if (primaryType == "movie") "tv" else "movie"
                // Networks don't have movies
                val types = if (entityKind == "network") listOf("tv") else listOf(primaryType, secondaryType)
                for (type in types) {
                    add(Triple(type, "popular", "popularity.desc"))
                    add(Triple(type, "top_rated", if (type == "movie") "vote_average.desc" else "vote_average.desc"))
                    add(Triple(type, "recent", if (type == "movie") "primary_release_date.desc" else "first_air_date.desc"))
                }
            }

            val results = railConfigs.map { (mediaType, railType, sortBy) ->
                viewModelScope.async {
                    val (items, totalPages) = tmdbMetadataService.fetchDiscover(
                        entityId = entityId, kind = entityKind, mediaType = mediaType,
                        sortBy = sortBy, page = 1,
                        voteCountGte = if (railType == "top_rated") 200 else null,
                        dateLte = if (railType == "recent") LocalDate.now().toString() else null
                    )
                    TmdbDiscoverRail(
                        mediaType = mediaType, railType = railType,
                        items = items, currentPage = 1,
                        hasMore = totalPages > 1
                    )
                }
            }.awaitAll()

            val rails = results.filter { it.items.isNotEmpty() }
            _state.value = StudioDetailState.Success(entity = entity, rails = rails)
        }
    }
}
