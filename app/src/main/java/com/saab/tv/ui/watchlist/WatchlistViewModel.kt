package com.saab.tv.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: AddonDao,
    private val repository: AddonRepository
) : ViewModel() {

    private val resolveInFlight = ConcurrentHashMap.newKeySet<String>()
    private val profileId = MutableStateFlow<Int?>(null)

    var lastFocusedKey: String? = null

    val movieRowState = androidx.compose.foundation.lazy.LazyListState()
    val seriesRowState = androidx.compose.foundation.lazy.LazyListState()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val movieItems: StateFlow<List<MetaItem>> = profileId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else dao.getWatchlistByType(id, "movie")
        }
        .map { list -> list.map { it.toMetaItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val seriesItems: StateFlow<List<MetaItem>> = profileId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else dao.getWatchlistByType(id, "series")
        }
        .map { list -> list.map { it.toMetaItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setProfileId(value: Int?) {
        if (profileId.value == value) return
        profileId.value = value
        resolveInFlight.clear()
        lastFocusedKey = null
    }

    /**
     * Resolve poster from addons for items missing one (e.g., pulled from Trakt).
     * Updates the DB so the poster persists — the Flow will re-emit automatically.
     */
    fun resolvePosterIfNeeded(item: MetaItem) {
        val activeProfileId = profileId.value ?: return
        if (!item.poster.isNullOrBlank()) return
        val resolveKey = "$activeProfileId:${item.id}"
        if (!resolveInFlight.add(resolveKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meta = repository.resolveMetaDetails(item.type, item.id) ?: return@launch
                if (!meta.poster.isNullOrBlank()) {
                    val existing = dao.getWatchlistItem(activeProfileId, item.id) ?: return@launch
                    dao.addToWatchlist(existing.copy(poster = meta.poster))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                resolveInFlight.remove(resolveKey)
            }
        }
    }
}

private fun WatchlistEntity.toMetaItem() = MetaItem(
    id = id,
    type = type,
    name = title,
    poster = poster
)
