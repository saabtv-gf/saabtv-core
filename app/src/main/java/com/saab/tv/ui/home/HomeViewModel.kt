package com.saab.tv.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.account.AccountSyncManager
import com.saab.tv.data.player.SourceSelectionStore
import com.saab.tv.data.player.PlaybackTrackSelectionStore
import com.saab.tv.data.trakt.TraktSyncManager
import com.saab.tv.data.ott.OttCatalogException
import com.saab.tv.data.ott.OttCatalogRepository
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbNaturalQuery
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.data.tmdb.mixTmdbMediaTypes
import com.saab.tv.domain.HomeRow
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.ui.utils.ImagePrefetcher
import com.saab.tv.ui.components.mergeProfileWatchedIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import com.saab.tv.domain.HomeRowItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.DashboardTab
import com.saab.tv.domain.heroFor

internal fun rankWatchHistorySuggestions(
    recommendationsByHistory: List<List<com.saab.tv.data.tmdb.TmdbMetaPreview>>,
    limit: Int = 30
): List<com.saab.tv.data.tmdb.TmdbMetaPreview> {
    data class Candidate(val item: com.saab.tv.data.tmdb.TmdbMetaPreview, var matchScore: Double)

    val candidates = linkedMapOf<Pair<String, Int>, Candidate>()
    recommendationsByHistory.forEachIndexed { historyIndex, recommendations ->
        val historyWeight = 1.0 / (historyIndex + 1)
        recommendations.forEachIndexed { recommendationIndex, item ->
            val type = if (item.type == "tv") "series" else item.type
            val key = type to item.tmdbId
            val candidate = candidates.getOrPut(key) {
                Candidate(if (item.type == type) item else item.copy(type = type), 0.0)
            }
            candidate.matchScore += historyWeight / (recommendationIndex + 1)
        }
    }

    return candidates.values
        .sortedWith(
            compareByDescending<Candidate> { it.matchScore }
                .thenByDescending { it.item.popularity ?: 0.0 }
                .thenByDescending { it.item.rating ?: 0.0 }
        )
        .take(limit.coerceAtLeast(0))
        .map { it.item }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val dao: AddonDao,
    @ApplicationContext private val context: Context,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val ottCatalogRepository: OttCatalogRepository,
    private val seekThumbnailCache: SeekThumbnailCache,
    private val accountSync: AccountSyncManager,
    private val sourceSelectionStore: SourceSelectionStore,
    private val playbackTrackSelectionStore: PlaybackTrackSelectionStore,
    private val traktSyncManager: TraktSyncManager
) : ViewModel() {

    private val optimisticWatchedIds = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())

    suspend fun isWatchlisted(profileId: Int, id: String): Boolean = dao.isInWatchlist(profileId, id)
    suspend fun activeProfileId(): Int? = dao.getActiveProfileId()
    fun watchedIdsForProfile(profileId: Int): kotlinx.coroutines.flow.Flow<Set<String>> = combine(
        dao.getWatchedIdsForProfile(profileId), optimisticWatchedIds
    ) { persisted, optimistic -> mergeProfileWatchedIds(profileId, persisted, optimistic) }
        .distinctUntilChanged()

    // Owned by the ViewModel, not the trailer composition: Start Watching may
    // dismiss the overlay while the same source lookup is still in flight.
    fun startTrailerSourcePrefetch(item: MetaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.saab.tv.AppDiagnostics.event("Source Warmup", "Started", "type=${item.type} id=${item.id}")
                prefetchTrailerSources(item)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.event("Source Warmup", "Failed", "type=${failure.javaClass.simpleName}")
            }
        }
    }

    /** Resolve only the episode actually targeted by Start Watching; never start a torrent. */
    suspend fun prefetchTrailerSources(item: MetaItem) {
        val profileId = dao.getActiveProfileId() ?: return
        accountSync.preparePlaybackResume()
        val resolvedId = if (item.id.startsWith("tmdb:")) {
            val numericId = item.id.substringAfter(':').substringBefore(':').toIntOrNull()
            numericId?.let { tmdbService.tmdbToImdb(it, tmdbService.normalizeMediaType(item.type)) } ?: item.id
        } else item.id
        val streamId = if (item.type == "series") {
            val meta = repository.prefetchTrailerMetadata("series", resolvedId, item.addonBaseUrl) ?: return
            val episodes = com.saab.tv.domain.normalizeEpisodeList(meta.videos.orEmpty())
            val history = accountSync.freshestPlaybackHistory(dao.getSeriesEpisodeHistory("${meta.id}:%"), profileId)
                .filter { it.id.startsWith("${meta.id}:") }
            val latest = history.maxByOrNull { it.lastWatched }
            fun watched(episode: com.saab.tv.data.model.stremio.MetaVideo) = history.any {
                it.watched && com.saab.tv.domain.episodeMatchesPlaybackId(meta.id, it.id, episode)
            }
            val resumed = episodes.firstOrNull {
                latest != null && !latest.watched && !watched(it) &&
                    com.saab.tv.domain.episodeMatchesPlaybackId(meta.id, latest.id, it)
            }
            val target = resumed ?: episodes.firstOrNull { !watched(it) } ?: episodes.firstOrNull() ?: return
            com.saab.tv.domain.episodeStreamId(meta.id, target)
        } else resolvedId
        // A profile change while metadata resolves must not warm the new account's sources.
        if (dao.getActiveProfileId() != profileId) return
        repository.prefetchTrailerStreams(item.type, streamId)
        if (item.type != "series") repository.prefetchTrailerMetadata(item.type, resolvedId, item.addonBaseUrl)
    }

    fun toggleWatchlist(profileId: Int, item: MetaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.isInWatchlist(profileId, item.id)) dao.removeFromWatchlist(profileId, item.id)
            else dao.addToWatchlist(WatchlistEntity(profileId, item.id, item.type, item.name, item.poster, System.currentTimeMillis()))
            accountSync.historyChanged(urgent = true)
        }
    }

    suspend fun isTitleWatched(profileId: Int, item: MetaItem): Boolean {
        val id = resolveTitleId(item) ?: item.id
        if (dao.getHistoryItemForProfile(profileId, id)?.watched == true) return true
        return item.isSeriesTitle() && dao.getSeriesNextUpForProfile(profileId)
            .any { it.seriesId == id && it.isComplete }
    }

    fun markTitleWatched(profileId: Int, item: MetaItem) {
        // Emit the card ID before the database write/resolution begins so every
        // visible card can draw its watched badge without waiting for disk/network IO.
        optimisticWatchedIds.update { current ->
            current + (profileId to (current[profileId].orEmpty() + item.id))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val id = resolveTitleId(item) ?: item.id
            if (dao.getHistoryItemForProfile(profileId, id)?.watched == true) return@launch
            val now = System.currentTimeMillis()
            if (item.isSeriesTitle()) {
                val meta = if (item.id == id) item else repository.resolveMetaDetails("series", id, item.addonBaseUrl)
                val episodes = com.saab.tv.domain.normalizeEpisodeList(meta?.videos.orEmpty())
                val prior = dao.getSeriesEpisodeHistoryForProfile(profileId, "$id:%")
                if (episodes.isNotEmpty()) {
                    val watchedEntries = episodes.map { episode ->
                        val previous = prior.firstOrNull {
                            com.saab.tv.domain.episodeMatchesPlaybackId(id, it.id, episode)
                        }
                        previous?.copy(watched = true, lastWatched = now)
                            ?: WatchHistoryEntity(
                                profileId = profileId,
                                id = com.saab.tv.domain.episodePlaybackId(id, episode),
                                title = episode.title.takeIf { it.isNotBlank() } ?: "${item.name} S${episode.season}E${episode.episode}",
                                poster = item.poster,
                                position = 0L,
                                duration = 0L,
                                lastWatched = now,
                                type = "series",
                                watched = true
                            )
                    }
                    dao.insertHistoryItems(watchedEntries)
                }
                dao.insertHistory(
                    WatchHistoryEntity(profileId, id, item.name, item.poster, item.background, item.logo,
                        0L, 0L, now, "series", watched = true)
                )
                dao.insertSeriesNextUp(
                    SeriesNextUpEntity(profileId, id, item.name, item.poster, 0, 0, null,
                        isComplete = true, updatedAt = now)
                )
                seekThumbnailCache.clearContentPrefix(profileId, id)
            } else {
                val previous = dao.getHistoryItemForProfile(profileId, id)
                dao.insertHistory(previous?.copy(watched = true, lastWatched = now) ?: WatchHistoryEntity(
                    profileId = profileId, id = id, title = item.name, poster = item.poster,
                    background = item.background, logo = item.logo, position = 0L, duration = 0L,
                    lastWatched = now, type = "movie", watched = true
                ))
                seekThumbnailCache.clearContent(profileId, id)
            }
            dao.removeFromWatchlist(profileId, id)
            if (id != item.id) dao.removeFromWatchlist(profileId, item.id)
            accountSync.historyChanged(urgent = true)
        }
    }

    private suspend fun resolveTitleId(item: MetaItem): String? {
        if (!item.id.startsWith("tmdb:", ignoreCase = true)) return item.id
        val tmdbId = item.id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return null
        return tmdbService.tmdbToImdb(tmdbId, tmdbService.normalizeMediaType(item.type))
    }

    private fun MetaItem.isSeriesTitle(): Boolean =
        type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)

    fun clearContinueProgress(profileId: Int, item: MetaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = if (item.type == "series") dao.getSeriesEpisodeHistory("${item.id}:%")
                else listOfNotNull(dao.getHistoryItemForProfile(profileId, item.id))
            if (item.type == "series") {
                dao.deleteSeriesHistory("${item.id}:%")
                dao.deleteHistoryItem(item.id)
                dao.deleteSeriesNextUp(item.id)
                sourceSelectionStore.clearSelectionsForPrefix(item.id)
                playbackTrackSelectionStore.clearSelectionsForPrefix(item.id)
                seekThumbnailCache.clearContentPrefix(profileId, item.id)
            } else {
                dao.deleteHistoryItem(item.id)
                sourceSelectionStore.clearSelection(item.id)
                playbackTrackSelectionStore.clearSelection(item.id)
                seekThumbnailCache.clearContent(profileId, item.id)
            }
            existing.filter { it.scrobbled }.forEach { traktSyncManager.deletePlaybackFromTrakt(it.id) }
            existing.filter { it.watched && it.type == "series" }.forEach { entry ->
                val parts = entry.id.split(":")
                val hasStreamIndex = parts.size >= 4 && parts.lastOrNull()?.toIntOrNull() != null
                val season = parts.getOrNull(parts.size - if (hasStreamIndex) 3 else 2)?.toIntOrNull()
                val episode = parts.getOrNull(parts.size - if (hasStreamIndex) 2 else 1)?.toIntOrNull()
                if (season != null && episode != null) traktSyncManager.pushEpisodeUnwatched(item.id, season, episode)
            }
            accountSync.historyChanged(urgent = true)
        }
    }

    suspend fun cachedPauseFrame(profileId: Int, contentId: String, positionMs: Long, intervalSeconds: Int) =
        seekThumbnailCache.loadNearest(profileId, contentId, positionMs, intervalSeconds)

    suspend fun trailerFor(item: MetaItem): Pair<String, String>? {
        com.saab.tv.data.trailer.TrailerPolicy.metadataTrailer(item)?.let { return it }
        if (item.id.startsWith("tt")) {
            val type = if (item.type == "series" || item.type == "tv") "series" else "movie"
            val cinemeta = repository.prefetchTrailerMetadata(type, item.id, "https://v3-cinemeta.strem.io")
            cinemeta?.let(com.saab.tv.data.trailer.TrailerPolicy::metadataTrailer)?.let { return it }
        }
        val details = repository.resolveMetaDetails(item.type, item.id, item.addonBaseUrl)
        val streamTrailer = details?.trailerStreams.orEmpty().firstOrNull {
            !it.ytId.isNullOrBlank() || !it.externalUrl.isNullOrBlank() || !it.url.isNullOrBlank()
        }
        val streamKey = streamTrailer?.ytId ?: streamTrailer?.externalUrl ?: streamTrailer?.url
        if (!streamKey.isNullOrBlank()) return streamKey to (streamTrailer?.title ?: "${item.name} Trailer")
        val legacyTrailer = details?.trailers.orEmpty().firstOrNull {
            it.type.equals("Trailer", ignoreCase = true) && !it.source.isNullOrBlank()
        }
        legacyTrailer?.source?.takeIf { it.isNotBlank() }?.let { return it to "${item.name} Trailer" }
        val tmdbId = tmdbService.ensureTmdbId(item.id, item.type) ?: return null
        val trailer = tmdbMetadataService.fetchBestTrailerKey(tmdbId, item.type) ?: return null
        return trailer.key to trailer.name
    }

    private var loadJob: Job? = null
    private var screenGeneration = 0L
    private val loadMoreJobs = mutableMapOf<String, Job>()
    private var lastFocusedKeyMemory: String? = null
    private val rowScrollPositionsMemory = mutableMapOf<String, Pair<Int, Int>>()
    private var verticalScrollPositionMemory: Pair<Int, Int> = Pair(0, 0)
    private var hadHistoryWhenPositionSaved: Boolean = false
    private var isRestoringPosition: Boolean = false

    data class HomeState(
        val mixedRows: List<HomeRowItem> = emptyList(),
        val rows: List<HomeRow> = emptyList(),
        val hubRows: List<HubGroupRow> = emptyList(),
        val personalizedRows: List<CategoryRow> = emptyList(),
        val history: List<WatchHistoryEntity> = emptyList(),
        val seriesNextUp: List<com.saab.tv.data.model.SeriesNextUpEntity> = emptyList(),
        val isLoading: Boolean = true,
        val lastFocusedKey: String? = null,
        val loadedScreen: String? = null,
        // Row scroll positions: rowKey -> Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        val rowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap(),
        // Vertical list scroll position: Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        val verticalScrollPosition: Pair<Int, Int> = Pair(0, 0),
        val heroRow: HomeRow? = null,
        val loadedProfileId: Int? = null,
        val watchedIds: Set<String> = emptySet(), // IMDb IDs of watched items (movies + series)
        val enrichedMeta: Map<String, MetaItem> = emptyMap(),
        val tmdbEnabled: Boolean = false,
        val tmdbEnrichedIds: Set<String> = emptySet(),
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    fun getRowScrollPositions(): Map<String, Pair<Int, Int>> = rowScrollPositionsMemory

    fun getVerticalScrollPosition(): Pair<Int, Int> = verticalScrollPositionMemory

    fun setLastFocusedKey(key: String?) {
        if (lastFocusedKeyMemory == key) return
        lastFocusedKeyMemory = key
        _state.value = _state.value.copy(lastFocusedKey = key)
    }
    
    fun setRowScrollPosition(rowKey: String, position: Pair<Int, Int>) {
        if (rowScrollPositionsMemory[rowKey] == position) return
        rowScrollPositionsMemory[rowKey] = position
    }
    
    fun setVerticalScrollPosition(position: Pair<Int, Int>, hasHistory: Boolean = hadHistoryWhenPositionSaved) {
        if (verticalScrollPositionMemory == position && hadHistoryWhenPositionSaved == hasHistory) return
        verticalScrollPositionMemory = position
        hadHistoryWhenPositionSaved = hasHistory
    }

    fun needsHistoryScrollAdjustment(hasHistory: Boolean): Boolean {
        return hasHistory && !hadHistoryWhenPositionSaved && isRestoringPosition
    }

    // Track which rows are currently loading more items to prevent duplicate fetches
    private val loadingMoreRows = mutableSetOf<String>()
    private val metadataFallbackCache = mutableMapOf<String, MetadataFallback?>()
    private val metadataRequestsInFlight = mutableSetOf<String>()
    private val hubInitialLoadCount = 100
    private val initialDashboardBatchSize = 6
    private val initialDashboardTimeoutMs = 2_500L

    // UI batching: per-row pending buffers and dedup tracking
    companion object {
        private const val ROW_BATCH_SIZE = 50
    }
    private val pendingRowItems = mutableMapOf<String, MutableList<MetaItem>>()
    private val allFetchedRowIds = mutableMapOf<String, MutableSet<String>>()

    private data class MetadataFallback(
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: String?,
        val runtime: String?,
        val genres: List<String>?
    )

    /**
     * Loads the next page of items for a specific catalog row.
     * Called when the user scrolls near the end of the row's current items.
     * Uses UI batching: reveals ROW_BATCH_SIZE items at a time from a pending buffer,
     * only fetching from the API when the buffer is empty.
     */
    fun loadMoreItems(configId: String) {
        if (configId in loadingMoreRows) return // Already loading

        // Find the row in the current state
        val currentRows = _state.value.rows
        val row = currentRows.find { it.configId == configId } ?: return
        if (row.catalogUrl.isEmpty() || !row.supportsSkip) return

        // First: reveal items from the pending buffer (no API call needed)
        val pending = pendingRowItems[configId]
        if (pending != null && pending.isNotEmpty()) {
            val batch = pending.take(ROW_BATCH_SIZE)
            pendingRowItems[configId] = pending.drop(ROW_BATCH_SIZE).toMutableList()
            appendItemsToRow(configId, batch)
            return
        }

        // Pending buffer empty — fetch next page from API
        loadingMoreRows.add(configId)
        val generation = screenGeneration

        loadMoreJobs[configId]?.cancel()
        loadMoreJobs[configId] = viewModelScope.launch {
            try {
                // Initialize dedup set from current row items if not yet tracked
                val fetchedIds = allFetchedRowIds.getOrPut(configId) {
                    row.items.map { "${it.type}:${it.id}" }.toMutableSet()
                }

                // Skip = total fetched count for this row
                val nextSkip = fetchedIds.size
                val newItems = repository.fetchNextCatalogPage(row.catalogUrl, nextSkip)
                if (generation != screenGeneration) return@launch
                val latestRow = _state.value.rows.find { it.configId == configId }
                if (latestRow?.catalogUrl != row.catalogUrl) return@launch

                // Deduplicate against all previously fetched items
                val newUniqueItems = newItems.filter { item ->
                    fetchedIds.add("${item.type}:${item.id}")
                }

                if (newUniqueItems.isNotEmpty()) {
                    // Show first batch immediately, buffer the rest
                    val batch = newUniqueItems.take(ROW_BATCH_SIZE)
                    if (newUniqueItems.size > ROW_BATCH_SIZE) {
                        pendingRowItems.getOrPut(configId) { mutableListOf() }
                            .addAll(newUniqueItems.drop(ROW_BATCH_SIZE))
                    }
                    appendItemsToRow(configId, batch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silently fail - user can try scrolling again
            } finally {
                if (generation == screenGeneration) {
                    loadingMoreRows.remove(configId)
                    loadMoreJobs.remove(configId)
                }
            }
        }
    }

    private fun appendItemsToRow(configId: String, newItems: List<MetaItem>) {
        _state.update { current ->
            val updatedRows = current.rows.map { r ->
                if (r.configId == configId) r.copy(items = r.items + newItems)
                else r
            }

            val updatedMixed = current.mixedRows.map { item ->
                if (item is CategoryRow && item.id == configId) {
                    val updatedRow = updatedRows.find { it.configId == configId }
                    if (updatedRow != null) CategoryRow.fromHomeRow(updatedRow) else item
                } else item
            }

            current.copy(
                rows = updatedRows,
                mixedRows = updatedMixed
            )
        }
    }

    /**
     * Invalidates the cached screen data, forcing a reload on next loadScreen() call.
     * Call this after making changes in the Dashboard Editor.
     */
    fun invalidate() {
        screenGeneration++
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
        loadingMoreRows.clear()
        pendingRowItems.clear()
        allFetchedRowIds.clear()
        _state.value = _state.value.copy(
            loadedScreen = null,
            loadedProfileId = null
        )
    }
    
    /**
     * Prefetch first visible images from loaded rows.
     * Called after data loads to ensure images are in cache before scroll starts.
     */
    private fun prefetchFirstVisibleImages(rows: List<HomeRow>) {
        // Warm only the nearest two rows; a 30-poster startup burst competes with
        // TMDB, video buffers, and the thumbnail worker for memory and bandwidth.
        val imagesToPrefetch = rows
            .take(2)
            .flatMap { row -> row.items.take(6) }
            .mapNotNull { it.poster }
        
        imagesToPrefetch.forEach { url ->
            ImagePrefetcher.prefetch(context, url)
        }
    }

    /**
     * Prefetch a small set of likely-visible metadata so cinematic/hero surfaces render smoothly.
     * Keep this intentionally tiny to avoid unnecessary metadata requests.
     */
    private fun prefetchLikelyVisibleMetadata(rows: List<HomeRow>, heroRow: HomeRow?) {
        val candidates = buildList {
            addAll(heroRow?.items?.take(4) ?: emptyList())
            addAll(rows.getOrNull(0)?.items?.take(3) ?: emptyList())
            addAll(rows.getOrNull(1)?.items?.take(2) ?: emptyList())
        }
            .distinctBy { "${it.type}:${it.id}" }
            .take(6)

        candidates.forEach { ensureMetadataFallback(it) }
    }

    private fun needsMetadataFallback(item: MetaItem): Boolean {
        return item.poster.isNullOrBlank() ||
            item.background.isNullOrBlank() ||
            item.logo.isNullOrBlank() ||
            item.description.isNullOrBlank() ||
            item.releaseInfo.isNullOrBlank() ||
            item.imdbRating.isNullOrBlank() ||
            item.runtime.isNullOrBlank() ||
            item.genres.isNullOrEmpty()
    }

    fun ensureMetadataFallback(item: MetaItem?) {
        if (item == null || !needsMetadataFallback(item)) return
        val key = "${item.type}:${item.id}"

        if (metadataFallbackCache.containsKey(key)) {
            val cachedFallback = metadataFallbackCache[key]
            if (cachedFallback != null) {
                applyMetadataFallbackToState(type = item.type, id = item.id, fallback = cachedFallback, sourceItem = item)
            }
            return
        }

        if (!metadataRequestsInFlight.add(key)) return

        viewModelScope.launch {
            try {
                val meta = repository.resolveMetaDetails(item.type, item.id)
                    ?: throw Exception("No meta found")
                val fallback = MetadataFallback(
                    poster = meta.poster,
                    background = meta.background,
                    logo = meta.logo,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo,
                    imdbRating = meta.imdbRating,
                    runtime = meta.runtime,
                    genres = meta.genres
                )
                metadataFallbackCache[key] = fallback
                applyMetadataFallbackToState(type = item.type, id = item.id, fallback = fallback, sourceItem = item)

                // Persist resolved images to watch history + series next-up DB
                launch(Dispatchers.IO) {
                    // For series, the history stores episode-level IDs (e.g. tt123:1:3)
                    // but the MetaItem uses the canonical series ID (tt123).
                    // Look up both the exact ID and all episode entries by prefix.
                    val historyItems = if (item.type == "series") {
                        dao.getHistoryItemsByPrefix(item.id)
                    } else {
                        listOfNotNull(dao.getHistoryItem(item.id))
                    }
                    for (historyItem in historyItems) {
                        val needsPoster = historyItem.poster.isNullOrBlank() && !fallback.poster.isNullOrBlank()
                        val needsBackground = historyItem.background.isNullOrBlank() && !fallback.background.isNullOrBlank()
                        val needsLogo = historyItem.logo.isNullOrBlank() && !fallback.logo.isNullOrBlank()
                        if (needsPoster || needsBackground || needsLogo) {
                            dao.updateHistoryImages(
                                id = historyItem.id,
                                poster = if (needsPoster) fallback.poster else historyItem.poster,
                                background = if (needsBackground) fallback.background else historyItem.background,
                                logo = if (needsLogo) fallback.logo else historyItem.logo
                            )
                        }
                    }
                    // Also update series next-up poster if missing
                    if (!fallback.poster.isNullOrBlank()) {
                        val nextUp = dao.getSeriesNextUp(item.id)
                        if (nextUp != null && nextUp.poster.isNullOrBlank()) {
                            dao.upsertSeriesNextUp(nextUp.copy(poster = fallback.poster))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                metadataFallbackCache[key] = null
            } finally {
                metadataRequestsInFlight.remove(key)
            }
        }
    }

    private fun applyFallbackToMeta(meta: MetaItem, fallback: MetadataFallback): MetaItem {
        val patchedPoster = if (meta.poster.isNullOrBlank()) fallback.poster else meta.poster
        val patchedBackground = if (meta.background.isNullOrBlank()) fallback.background else meta.background
        val patchedLogo = if (meta.logo.isNullOrBlank()) fallback.logo else meta.logo
        val patchedDescription = if (meta.description.isNullOrBlank()) fallback.description else meta.description
        val patchedReleaseInfo = if (meta.releaseInfo.isNullOrBlank()) fallback.releaseInfo else meta.releaseInfo
        val patchedImdbRating = if (meta.imdbRating.isNullOrBlank()) fallback.imdbRating else meta.imdbRating
        val patchedRuntime = if (meta.runtime.isNullOrBlank()) fallback.runtime else meta.runtime
        val patchedGenres = if (meta.genres.isNullOrEmpty()) fallback.genres else meta.genres

        return meta.copy(
            poster = patchedPoster,
            background = patchedBackground,
            logo = patchedLogo,
            description = patchedDescription,
            releaseInfo = patchedReleaseInfo,
            imdbRating = patchedImdbRating,
            runtime = patchedRuntime,
            genres = patchedGenres
        )
    }

    private fun patchMetaListWithFallback(
        items: List<MetaItem>,
        type: String,
        id: String,
        fallback: MetadataFallback
    ): Pair<List<MetaItem>, Boolean> {
        var changed = false
        val patched = items.map { meta ->
            if (meta.type == type && meta.id == id) {
                val merged = applyFallbackToMeta(meta, fallback)
                if (merged != meta) changed = true
                merged
            } else {
                meta
            }
        }
        return patched to changed
    }

    private fun applyMetadataFallbackToState(type: String, id: String, fallback: MetadataFallback, sourceItem: MetaItem? = null) {
        _state.update { current ->
            var stateChanged = false

            val updatedRows = current.rows.map { row ->
                val (patchedItems, changed) = patchMetaListWithFallback(row.items, type, id, fallback)
                if (changed) {
                    stateChanged = true
                    row.copy(items = patchedItems)
                } else {
                    row
                }
            }

            val updatedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val (patchedItems, changed) = patchMetaListWithFallback(rowItem.items, type, id, fallback)
                    if (changed) {
                        stateChanged = true
                        rowItem.copy(items = patchedItems)
                    } else {
                        rowItem
                    }
                } else {
                    rowItem
                }
            }

            val updatedHeroRow = current.heroRow?.let { hero ->
                val (patchedItems, changed) = patchMetaListWithFallback(hero.items, type, id, fallback)
                if (changed) {
                    stateChanged = true
                    hero.copy(items = patchedItems)
                } else {
                    hero
                }
            }

            val enrichedKey = "$type:$id"
            val updatedEnrichedMeta = if (sourceItem != null && !current.enrichedMeta.containsKey(enrichedKey)) {
                val enriched = applyFallbackToMeta(sourceItem, fallback)
                if (enriched != sourceItem) {
                    stateChanged = true
                    current.enrichedMeta + (enrichedKey to enriched)
                } else current.enrichedMeta
            } else current.enrichedMeta

            if (!stateChanged) return@update current

            current.copy(
                rows = updatedRows,
                mixedRows = updatedMixedRows,
                heroRow = updatedHeroRow,
                enrichedMeta = updatedEnrichedMeta
            )
        }
    }

    private val tmdbEnrichmentInFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var tmdbProfileCache: com.saab.tv.data.model.ProfileEntity? = null

    /**
     * Enriches a single item with TMDB metadata. Called for every item as it becomes visible,
     * piggybacking on the existing ensureMetadataFallback flow.
     */
    fun ensureTmdbEnrichment(item: MetaItem?) {
        if (item == null) return
        val profile = tmdbProfileCache ?: return
        if (!profile.tmdbEnabled) return

        val key = "tmdb:${item.type}:${item.id}"
        if ("${item.type}:${item.id}" in _state.value.tmdbEnrichedIds) return
        if (!tmdbEnrichmentInFlight.add(key)) return

        val language = profile.tmdbLanguage.ifBlank { null } ?: "en"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mediaType = tmdbService.normalizeMediaType(item.type)
                val tmdbId = tmdbService.ensureTmdbId(item.id, mediaType)
                if (tmdbId == null) {
                    // Can't resolve (e.g. Kitsu IDs) — mark as done so UI doesn't stay hidden
                    markTmdbEnriched(item.type, item.id)
                    return@launch
                }
                val enrichment = tmdbMetadataService.fetchHomeEnrichment(tmdbId, mediaType, language)
                if (enrichment == null) {
                    markTmdbEnriched(item.type, item.id)
                    return@launch
                }

                val fallback = MetadataFallback(
                    poster = null, // Keep addon poster
                    background = enrichment.backdrop,
                    logo = enrichment.logo,
                    description = enrichment.description,
                    releaseInfo = enrichment.releaseInfo,
                    imdbRating = null,
                    runtime = enrichment.runtimeMinutes?.let { "${it}m" },
                    genres = enrichment.genres.ifEmpty { null }
                )

                applyTmdbEnrichmentToState(item.type, item.id, fallback, item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("HomeViewModel", "TMDB enrichment failed for ${item.id}: ${e.message}")
                markTmdbEnriched(item.type, item.id)
            } finally {
                tmdbEnrichmentInFlight.remove(key)
            }
        }
    }

    private fun markTmdbEnriched(type: String, id: String) {
        _state.update { it.copy(tmdbEnrichedIds = it.tmdbEnrichedIds + "$type:$id") }
    }

    /**
     * Applies TMDB enrichment to state — overwrites fields (unlike addon fallback which only fills blanks).
     * This ensures localized content from TMDB takes priority.
     */
    private fun applyTmdbEnrichmentToState(type: String, id: String, fallback: MetadataFallback, sourceItem: MetaItem) {
        _state.update { current ->
            var rowsChanged = false

            fun overwriteMeta(meta: MetaItem): MetaItem {
                if (meta.type != type || meta.id != id) return meta
                val updated = meta.copy(
                    background = fallback.background ?: meta.background,
                    logo = fallback.logo ?: meta.logo,
                    description = fallback.description ?: meta.description,
                    releaseInfo = fallback.releaseInfo ?: meta.releaseInfo,
                    imdbRating = fallback.imdbRating ?: meta.imdbRating,
                    runtime = fallback.runtime ?: meta.runtime,
                    genres = fallback.genres ?: meta.genres
                )
                if (updated != meta) rowsChanged = true
                return updated
            }

            val updatedRows = current.rows.map { row ->
                val patched = row.items.map { overwriteMeta(it) }
                if (rowsChanged) row.copy(items = patched) else row
            }

            val updatedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val patched = rowItem.items.map { overwriteMeta(it) }
                    if (rowsChanged) rowItem.copy(items = patched) else rowItem
                } else rowItem
            }

            val updatedHeroRow = current.heroRow?.let { hero ->
                val patched = hero.items.map { overwriteMeta(it) }
                if (rowsChanged) hero.copy(items = patched) else hero
            }

            // Always store enriched preview in enrichedMeta so continue watching
            // items (which aren't in category rows) can pick up TMDB metadata.
            val enrichedKey = "$type:$id"
            val base = current.enrichedMeta[enrichedKey] ?: sourceItem
            val enriched = base.copy(
                background = fallback.background ?: base.background,
                logo = fallback.logo ?: base.logo,
                description = fallback.description ?: base.description,
                releaseInfo = fallback.releaseInfo ?: base.releaseInfo,
                imdbRating = fallback.imdbRating ?: base.imdbRating,
                runtime = fallback.runtime ?: base.runtime,
                genres = fallback.genres ?: base.genres
            )
            val updatedEnrichedMeta = if (enriched != base || !current.enrichedMeta.containsKey(enrichedKey)) {
                current.enrichedMeta + (enrichedKey to enriched)
            } else current.enrichedMeta

            current.copy(
                rows = if (rowsChanged) updatedRows else current.rows,
                mixedRows = if (rowsChanged) updatedMixedRows else current.mixedRows,
                heroRow = if (rowsChanged) updatedHeroRow else current.heroRow,
                enrichedMeta = updatedEnrichedMeta,
                tmdbEnrichedIds = current.tmdbEnrichedIds + "$type:$id"
            )
        }
    }

    fun refreshOtt(currentProfile: com.saab.tv.data.model.ProfileEntity?) {
        loadScreen(screenName = "ott", currentProfile = currentProfile, forceReload = true)
    }

    fun loadScreen(
        screenName: String,
        currentProfile: com.saab.tv.data.model.ProfileEntity?,
        forceReload: Boolean = false
    ) {
        val currentProfileId = currentProfile?.id
        // ... (existing code)
        // Skip reload if this screen is already loaded with data
        if (
            !forceReload &&
            _state.value.loadedScreen == screenName &&
            _state.value.loadedProfileId == currentProfileId &&
            _state.value.rows.isNotEmpty()
        ) {
            isRestoringPosition = true
            return
        }

        loadJob?.cancel()
        screenGeneration++
        val generation = screenGeneration
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
        loadingMoreRows.clear()
        loadJob = viewModelScope.launch {
            // Clear 'rows' immediately so the UI doesn't show "Ghost Data" from the previous tab
            pendingRowItems.clear()
            allFetchedRowIds.clear()
            _state.value = _state.value.copy(
                isLoading = true,
                mixedRows = emptyList(),
                rows = emptyList(),
                hubRows = emptyList(),
                personalizedRows = emptyList(),
                lastFocusedKey = null,  // Reset focus when loading new screen
                rowScrollPositions = emptyMap(),  // Reset scroll positions for new screen
                verticalScrollPosition = Pair(0, 0),  // Reset vertical scroll for new screen
                loadedProfileId = null,
                enrichedMeta = emptyMap(),
                tmdbEnrichedIds = emptySet(),
                tmdbEnabled = screenName != "ott" && currentProfile?.tmdbEnabled == true,
                errorMessage = null
            )
            tmdbEnrichmentInFlight.clear()
            lastFocusedKeyMemory = null
            rowScrollPositionsMemory.clear()
            verticalScrollPositionMemory = Pair(0, 0)
            hadHistoryWhenPositionSaved = false
            isRestoringPosition = false

            // Load History + Series Next Up only for Home
            if (screenName == "home") {
                launch {
                    dao.getWatchHistory().collect { history ->
                        _state.update { it.copy(history = history) }
                    }
                }
                launch {
                    dao.getActiveSeriesNextUp().collect { nextUp ->
                        _state.update { it.copy(seriesNextUp = nextUp) }
                    }
                }
            } else {
                _state.value = _state.value.copy(history = emptyList(), seriesNextUp = emptyList())
            }

            // Load watched IDs for all tabs (watched indicator on posters)
            launch {
                dao.getWatchedIds().collect { ids ->
                    // Extract canonical series ID from episode IDs (tt123:1:3 → tt123)
                    val canonicalIds = ids.map { id ->
                        val parts = id.split(":")
                        if (parts.size >= 3) parts.first() else id
                    }.toSet()
                    _state.update { it.copy(watchedIds = canonicalIds) }
                }
            }

            try {
                if (screenName == "ott") {
                    val ottRows = ottCatalogRepository.getCatalogRows(forceRefresh = forceReload)
                    if (generation != screenGeneration) return@launch
                    prefetchFirstVisibleImages(ottRows)
                    tmdbProfileCache = null

                    _state.update {
                        it.copy(
                            mixedRows = ottRows.map { row -> CategoryRow.fromHomeRow(row) },
                            rows = ottRows,
                            hubRows = emptyList(),
                            heroRow = null,
                            isLoading = false,
                            loadedScreen = screenName,
                            loadedProfileId = currentProfileId,
                            tmdbEnabled = false,
                            errorMessage = null
                        )
                    }
                    return@launch
                }

                // Stage 1: Load only a small first batch so the screen opens quickly.
                val initialRowsDeferred = async {
                    repository.getDashboardRows(
                        screen = screenName,
                        skipConfigs = 0,
                        maxConfigs = initialDashboardBatchSize,
                        catalogTimeoutMs = initialDashboardTimeoutMs
                    )
                }
                val hubRowsDeferred = async { repository.getHubRows(screenName) }

                val initialRows = initialRowsDeferred.await()
                val hubRows = hubRowsDeferred.await()
                if (generation != screenGeneration) return@launch

                // Fetch HERO row separately (even if hidden in dashboard).
                val tabEnum = DashboardTab.fromString(screenName)
                val heroConfig = currentProfile?.heroFor(tabEnum)
                val heroRow = if (heroConfig?.categoryId != null) {
                    initialRows.find { it.configId == heroConfig.categoryId }
                        ?: repository.getCategoryRowPreview(
                            configId = heroConfig.categoryId,
                            maxItems = heroConfig.posterCount,
                            timeoutMs = initialDashboardTimeoutMs
                        )
                } else null

                // Prefetch first visible images BEFORE updating state.
                prefetchFirstVisibleImages(initialRows)

                val initialMixedList = (hubRows + initialRows.map { CategoryRow.fromHomeRow(it) })
                    .sortedBy { it.order }

                // Cache profile for TMDB enrichment (called per-item as they become visible)
                tmdbProfileCache = currentProfile

                _state.update {
                    it.copy(
                        mixedRows = initialMixedList,
                        rows = initialRows,
                        hubRows = hubRows,
                        heroRow = heroRow,
                        isLoading = false,
                        loadedScreen = screenName,
                        loadedProfileId = currentProfileId,
                        tmdbEnabled = currentProfile?.tmdbEnabled == true
                    )
                }

                if (screenName == "home" && currentProfile?.tmdbEnabled == true && tmdbService.hasApiKey()) {
                    launch {
                        val personalized = loadPersonalizedRows(currentProfile, initialRows)
                        if (generation != screenGeneration || personalized.isEmpty()) return@launch
                        _state.update { current ->
                            if (current.loadedScreen != "home" || current.loadedProfileId != currentProfileId) current
                            else current.copy(
                                personalizedRows = personalized,
                                mixedRows = (current.mixedRows + personalized).distinctBy { it.id }.sortedBy { it.order }
                            )
                        }
                    }
                }

                // Start a tiny metadata warmup pass for items likely to render first.
                prefetchLikelyVisibleMetadata(rows = initialRows, heroRow = heroRow)

                // Stage 2: Load remaining categories in the background and append.
                launch {
                    val remainingRows = repository.getDashboardRows(
                        screen = screenName,
                        skipConfigs = initialDashboardBatchSize
                    )
                    if (generation != screenGeneration) return@launch
                    if (remainingRows.isEmpty()) return@launch

                    _state.update { currentState ->
                        if (currentState.loadedScreen != screenName || currentState.loadedProfileId != currentProfileId) {
                            return@update currentState
                        }

                        val allRows = (currentState.rows + remainingRows)
                            .distinctBy { it.configId }
                            .sortedBy { it.order }

                        val resolvedHeroRow = when {
                            currentState.heroRow != null -> currentState.heroRow
                            heroConfig?.categoryId != null -> allRows.find { it.configId == heroConfig.categoryId }
                            else -> null
                        }

                        val mixedList = (hubRows + allRows.map { CategoryRow.fromHomeRow(it) } + currentState.personalizedRows)
                            .distinctBy { it.id }.sortedBy { it.order }

                        currentState.copy(
                            mixedRows = mixedList,
                            rows = allRows,
                            heroRow = resolvedHeroRow
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != screenGeneration) return@launch
                val message = when {
                    e is OttCatalogException -> e.message
                    screenName == "ott" -> "The OTT catalog is temporarily unavailable. Check your connection and try again."
                    else -> null
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        loadedScreen = screenName,
                        loadedProfileId = currentProfileId,
                        errorMessage = message
                    )
                }
            }
        }
    }

    private suspend fun loadPersonalizedRows(
        profile: com.saab.tv.data.model.ProfileEntity,
        homeRows: List<HomeRow>
    ): List<CategoryRow> {
        return try {
            val history = dao.getWatchHistoryForProfileOnce(profile.id)
            val watchlist = dao.getWatchlistOnce(profile.id)
            val watchedTitles = history.asSequence()
                .filter { it.watched || it.position > 0L }
                .groupBy { if (it.type == "series") com.saab.tv.domain.seriesIdFromPlaybackId(it.id) else it.id }
                .mapNotNull { (id, entries) ->
                    entries.maxByOrNull { it.lastWatched }?.let { latest -> id to latest }
                }
                .sortedByDescending { it.second.lastWatched }
                .take(3)
                .map { (id, latest) ->
                    Triple(id, latest.type, latest.title)
                }
                .toList()
            val interestSeeds = (watchedTitles + watchlist.take(2).map { Triple(it.id, it.type, it.title) })
                .distinctBy { "${it.second}:${it.first}" }.take(3)
            val language = profile.tmdbLanguage.ifBlank { "en" }
            val visibleIds = homeRows.flatMap { row -> row.items.map { "${it.type}:${it.id}" } }.toSet()
            val usedTmdbKeys = mutableSetOf<String>()
            val output = mutableListOf<CategoryRow>()

            fun previewsToItems(previews: List<com.saab.tv.data.tmdb.TmdbMetaPreview>, limit: Int = 10): List<MetaItem> =
                previews.asSequence().mapNotNull { result ->
                    val type = if (result.type == "tv") "series" else result.type
                    val tmdbKey = "$type:tmdb:${result.tmdbId}"
                    if (tmdbKey in usedTmdbKeys || "$type:tmdb:${result.tmdbId}" in visibleIds) return@mapNotNull null
                    usedTmdbKeys += tmdbKey
                    MetaItem(
                        id = "tmdb:${result.tmdbId}", type = type, name = result.name, poster = result.poster,
                        background = result.backdrop, description = result.description,
                        releaseInfo = result.releaseInfo
                    )
                }.take(limit).toList()

            fun addRow(id: String, title: String, order: Int, previews: List<com.saab.tv.data.tmdb.TmdbMetaPreview>) {
                val items = previewsToItems(previews)
                if (items.isNotEmpty()) output += CategoryRow(id, title, Int.MIN_VALUE + order, items)
            }

            // Aggregate every recent history seed into one rail, ranking titles by
            // overlap and position across each seed's TMDB recommendations.
            val historyRecommendations = kotlinx.coroutines.coroutineScope {
                watchedTitles.map { (seedId, type, _) -> async(Dispatchers.IO) {
                    val tmdbId = tmdbService.ensureTmdbId(seedId, type) ?: return@async emptyList()
                    tmdbMetadataService.fetchRecommendations(tmdbId, type, language, maxItems = 20)
                } }.awaitAll()
            }
            val historyRankedSuggestions = rankWatchHistorySuggestions(historyRecommendations)

            // A small, bounded enrichment sample gives genre rails and cross-type
            // suggestions real signals without slowing the initial catalog load.
            val interestDetails = kotlinx.coroutines.coroutineScope {
                interestSeeds.map { (seedId, type, _) -> async(Dispatchers.IO) {
                    val tmdbId = tmdbService.ensureTmdbId(seedId, type) ?: return@async null
                    val enrichment = tmdbMetadataService.fetchHomeEnrichment(tmdbId, type, language) ?: return@async null
                    Triple(type, tmdbId, enrichment)
                } }.awaitAll().filterNotNull()
            }

            val genreVotes = linkedMapOf<Pair<String, Int>, Int>()
            interestDetails.forEach { (type, _, enrichment) ->
                val normalizedType = if (type == "series" || type == "tv") "tv" else "movie"
                enrichment.genreIds.forEach { genreId ->
                    val key = normalizedType to genreId
                    genreVotes[key] = (genreVotes[key] ?: 0) + 1
                }
            }
            val favoriteMovieGenres = genreVotes.filterKeys { it.first == "movie" }
                .entries.sortedByDescending { it.value }.take(2).map { it.key.second }
            val favoriteTvGenres = genreVotes.filterKeys { it.first == "tv" }
                .entries.sortedByDescending { it.value }.take(2).map { it.key.second }
            val allFavoriteGenres = favoriteMovieGenres.isNotEmpty() || favoriteTvGenres.isNotEmpty()
            val genreFilterMovie = favoriteMovieGenres.joinToString(",").ifBlank { null }
            val genreFilterTv = favoriteTvGenres.joinToString(",").ifBlank { null }
            val favoriteLanguage = interestDetails.mapNotNull { it.third.language?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

            if (historyRankedSuggestions.isNotEmpty()) {
                val historyTypes = historyRankedSuggestions.map { if (it.type == "tv") "series" else it.type }.toSet()
                val crossTypeSuggestions = listOf("movie", "tv").filterNot { requestedType ->
                    val normalizedType = if (requestedType == "tv") "series" else requestedType
                    normalizedType in historyTypes
                }.flatMap { requestedType ->
                    val isTv = requestedType == "tv"
                    tmdbMetadataService.discoverByIntent(
                        TmdbNaturalQuery(
                            mediaType = requestedType,
                            genreIds = if (isTv) genreFilterTv else genreFilterMovie,
                            originalLanguage = favoriteLanguage,
                            sortBy = "popularity.desc"
                        ),
                        language,
                        10
                    )
                }
                addRow(
                    "tmdb-top-suggestions",
                    "Top Suggestions",
                    10,
                    mixTmdbMediaTypes(historyRankedSuggestions + crossTypeSuggestions, 10)
                )
            }

            if (allFavoriteGenres) {
                val favoriteGenres = tmdbMetadataService.discoverByIntent(
                    TmdbNaturalQuery(null, genreFilterMovie, null, "vote_average.desc", tvGenreIds = genreFilterTv),
                    language, 30
                )
                addRow("tmdb-favorite-genres", "More in Your Favorite Genres", 13, favoriteGenres)
            }

            val recentDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                java.util.Date(System.currentTimeMillis() - 365L * 24L * 60L * 60L * 1000L)
            )
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            val newForYou = tmdbMetadataService.discoverByIntent(
                TmdbNaturalQuery(
                    null, null, null, "release_date.desc",
                    releaseDateGte = recentDate, releaseDateLte = today
                ), language, 30
            )
            addRow("tmdb-new-for-you", "New for You", 15, newForYou)

            val popularInTaste = tmdbMetadataService.discoverByIntent(
                TmdbNaturalQuery(
                    null, genreFilterMovie, favoriteLanguage ?: profile.tmdbLanguage.takeIf(String::isNotBlank),
                    "popularity.desc", tvGenreIds = genreFilterTv
                ), language, 30
            )
            addRow("tmdb-popular-in-taste", "Popular in Your Taste", 16, popularInTaste)

            output
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeViewModel", "Personalized TMDB rows failed: ${failure.message}")
            emptyList()
        }
    }

    /**
     * Opens a hub item, fetching the category content if it's not already loaded.
     */
    fun openHub(
        hubItem: com.saab.tv.domain.HubItem,
        onResult: (String, List<MetaItem>) -> Unit
    ) {
        // 1. Try to find in currently loaded rows (Legacy)
        val legacyRow = _state.value.rows.find { it.configId == hubItem.categoryId }
        if (legacyRow != null) {
            onResult(legacyRow.title, legacyRow.items)
            return
        }

        // 2. Try to find in mixed rows (New Architecture)
        val mixedRow = _state.value.mixedRows.find { it.id == hubItem.categoryId } as? CategoryRow
        if (mixedRow != null) {
            onResult(mixedRow.title, mixedRow.items)
            return
        }

        // 3. If not found, fetch from repository
        viewModelScope.launch {
            try {
                val fetchedRow = repository.getCategoryRowPreview(
                    configId = hubItem.categoryId,
                    maxItems = hubInitialLoadCount
                )
                if (fetchedRow != null) {
                    // Cache fetched row so GridView can lazy-load additional pages via loadMoreItems().
                    _state.update { current ->
                        val updatedRows = current.rows
                            .filterNot { it.configId == fetchedRow.configId } + fetchedRow
                        current.copy(rows = updatedRows)
                    }
                    onResult(fetchedRow.title, fetchedRow.items)
                }
            } catch (_: Exception) {
                // Ignore error
            }
        }
    }
}
