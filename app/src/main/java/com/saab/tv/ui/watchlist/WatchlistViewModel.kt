package com.saab.tv.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.account.AccountSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: AddonDao,
    private val repository: AddonRepository,
    private val accountSync: AccountSyncManager
) : ViewModel() {

    private val resolveInFlight = ConcurrentHashMap.newKeySet<String>()
    private val resolveAttempted = ConcurrentHashMap.newKeySet<String>()
    private val profileId = MutableStateFlow<Int?>(null)

    var lastFocusedKey: String? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val libraryItems: StateFlow<MyLibraryItems> = profileId
        .flatMapLatest { id ->
            if (id == null) flowOf(MyLibraryItems()) else combine(
                dao.getWatchHistoryForProfile(id),
                dao.getWatchlist(id),
                dao.getSeriesNextUpForProfileFlow(id),
                ::buildMyLibraryItems
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MyLibraryItems())

    fun setProfileId(value: Int?) {
        if (profileId.value == value) return
        profileId.value = value
        resolveInFlight.clear()
        resolveAttempted.clear()
        lastFocusedKey = null
    }

    fun remove(item: MetaItem) {
        val activeProfileId = profileId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dao.removeFromWatchlist(activeProfileId, item.id)
            accountSync.historyChanged(urgent = true)
        }
    }

    /** Resolve missing poster/backdrop/logo metadata for imported library entries. */
    fun resolvePosterIfNeeded(item: MetaItem) {
        val activeProfileId = profileId.value ?: return
        if (!item.poster.isNullOrBlank() && !item.background.isNullOrBlank() && !item.logo.isNullOrBlank()) return
        val resolveKey = "$activeProfileId:${item.type}:${item.id}"
        if (!resolveAttempted.add(resolveKey) || !resolveInFlight.add(resolveKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meta = repository.resolveMetaDetails(item.type, item.id) ?: return@launch
                val existing = dao.getWatchlistItem(activeProfileId, item.id) ?: return@launch
                val updated = existing.copy(
                    poster = existing.poster?.takeIf(String::isNotBlank) ?: meta.poster,
                    background = existing.background?.takeIf(String::isNotBlank) ?: meta.background,
                    logo = existing.logo?.takeIf(String::isNotBlank) ?: meta.logo
                )
                if (updated != existing) dao.addToWatchlist(updated)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                resolveInFlight.remove(resolveKey)
            }
        }
    }
}
