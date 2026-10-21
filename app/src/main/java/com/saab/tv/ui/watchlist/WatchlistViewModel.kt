package com.saab.tv.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.account.AccountSyncManager
import com.saab.tv.data.account.UserStateCommandRepository
import com.saab.tv.domain.schema.addonInstallationKey
import com.saab.tv.domain.schema.canonicalPresentationKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: AddonDao,
    private val repository: AddonRepository,
    private val accountSync: AccountSyncManager,
    private val userStateCommands: UserStateCommandRepository
) : ViewModel() {

    private val resolveInFlight = ConcurrentHashMap.newKeySet<String>()
    private val resolveAttempted = ConcurrentHashMap.newKeySet<String>()
    private val profileId = MutableStateFlow<Int?>(null)
    val activeProfileId: StateFlow<Int?> = profileId.asStateFlow()
    private val metadataRevision = MutableStateFlow(0L)
    private val mutableLibraryItems = MutableStateFlow(MyLibraryItems())
    val libraryItems: StateFlow<MyLibraryItems> = mutableLibraryItems.asStateFlow()

    var lastFocusedKey: String? = null
    var lastFocusedSectionTitle: String? = null
    var lastFocusedItemIndex: Int? = null

    fun rememberFocusedCard(sectionTitle: String, itemIdentity: String, itemIndex: Int) {
        lastFocusedSectionTitle = sectionTitle
        lastFocusedKey = itemIdentity
        lastFocusedItemIndex = itemIndex.coerceAtLeast(0)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val libraryProjection = profileId
        .flatMapLatest { id ->
            if (id == null) flowOf(MyLibraryItems()) else combine(
                dao.getWatchHistoryForProfile(id),
                dao.getWatchlist(id),
                dao.getSeriesNextUpForProfileFlow(id),
                metadataRevision
            ) { history, watchlist, nextUp, _ -> buildMyLibraryItems(history, watchlist, nextUp) }
                .mapLatest { items ->
                    MyLibraryItems(
                        inProgress = canonicalize(items.inProgress),
                        watchlist = canonicalize(items.watchlist),
                        watched = canonicalize(items.watched)
                    )
                }
        }

    init {
        viewModelScope.launch {
            libraryProjection.collect { mutableLibraryItems.value = it }
        }
    }

    private suspend fun canonicalize(items: List<LibraryCardProjection>): List<LibraryCardProjection> {
        val resolved = repository.canonicalizeCatalogItems(items.map(LibraryCardProjection::asActionItem))
        val sourceByIdentity = items.associateBy { it.asActionItem().canonicalPresentationKey() }
        return resolved.map { item ->
            // Canonicalization may drop duplicate aliases. Match by identity so a
            // removed duplicate cannot shift resume time onto a different title.
            val source = sourceByIdentity[item.canonicalPresentationKey()]
            LibraryCardProjection.from(item, source?.positionMs ?: 0L, source?.durationMs ?: 0L)
        }
    }

    fun setProfileId(value: Int?) {
        if (profileId.value == value) return
        profileId.value = value
        // Never leave the prior profile's Library visible while the next Room flow attaches.
        mutableLibraryItems.value = MyLibraryItems()
        resolveInFlight.clear()
        resolveAttempted.clear()
        lastFocusedSectionTitle = null
        lastFocusedKey = null
        lastFocusedItemIndex = null
    }

    /** Reload immediately after a committed UI mutation; Room remains the live source afterward. */
    suspend fun refreshLibrary() {
        val id = profileId.value ?: return
        val resolved = withContext(Dispatchers.IO) {
            val current = buildMyLibraryItems(
                dao.getWatchHistoryForProfileOnce(id),
                dao.getWatchlistOnce(id),
                dao.getSeriesNextUpForProfile(id)
            )
            MyLibraryItems(
                inProgress = canonicalize(current.inProgress),
                watchlist = canonicalize(current.watchlist),
                watched = canonicalize(current.watched)
            )
        }
        if (profileId.value == id) mutableLibraryItems.value = resolved
    }

    fun remove(item: MetaItem) {
        val activeProfileId = profileId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val originKey = item.addonBaseUrl?.let(::addonInstallationKey)
            userStateCommands.removeFromLibrary(activeProfileId, item.id, item.type, originKey) {
                if (originKey == null) dao.removeFromWatchlist(activeProfileId, item.id)
                else dao.removeWatchlistItemFromAddon(activeProfileId, item.id, originKey)
            }
            accountSync.historyChanged(urgent = true)
        }
    }

    /** Warm canonical metadata for a library card; content facts are not re-synced as user state. */
    fun resolvePosterIfNeeded(item: MetaItem) {
        val activeProfileId = profileId.value ?: return
        if (!item.poster.isNullOrBlank() && !item.background.isNullOrBlank() && !item.logo.isNullOrBlank()) return
        val resolveKey = "$activeProfileId:${item.canonicalPresentationKey()}"
        if (!resolveAttempted.add(resolveKey) || !resolveInFlight.add(resolveKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meta = repository.resolveMetaDetails(item.type, item.id, item.addonBaseUrl) ?: return@launch
                if (meta.poster != null || meta.background != null || meta.logo != null) {
                    metadataRevision.value += 1
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
