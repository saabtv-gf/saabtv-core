package com.saab.tv.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.domain.schema.addonInstallationKey
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.domain.schema.metadataSource
import com.saab.tv.domain.schema.MetadataSource
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import com.saab.tv.domain.HomeRowItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.DashboardTab
import com.saab.tv.domain.heroFor
import com.saab.tv.domain.TmdbHomeRails

internal fun preferExistingArtwork(existing: String?, enriched: String?): String? =
    existing?.takeIf(String::isNotBlank) ?: enriched?.takeIf(String::isNotBlank)

internal fun needsTmdbEnrichment(item: MetaItem, includeLogo: Boolean): Boolean =
    item.name.isBlank() || (if (includeLogo) {
        item.background.isNullOrBlank() || item.logo.isNullOrBlank()
    } else {
        item.poster.isNullOrBlank()
    })

internal fun initialDashboardBatchSizeFor(screenName: String, defaultSize: Int): Int =
    if (screenName == "movies" || screenName == "series") minOf(defaultSize, 3) else defaultSize

private fun preferExistingText(existing: String?, enriched: String?): String? =
    existing?.takeIf(String::isNotBlank) ?: enriched?.takeIf(String::isNotBlank)

private fun preferExistingGenres(existing: List<String>?, enriched: List<String>?): List<String>? =
    existing?.takeIf(List<String>::isNotEmpty) ?: enriched?.takeIf(List<String>::isNotEmpty)

internal fun personalizedRailOrder(offset: Int): Int = Int.MAX_VALUE / 2 + offset

internal data class TmdbHomeRailLoadPlan(
    val topSuggestions: Boolean,
    val favoriteGenres: Boolean,
    val newForYou: Boolean,
    val popularInTaste: Boolean
) {
    val needsTasteSeeds: Boolean get() = topSuggestions || favoriteGenres || popularInTaste
    val needsInterestDetails: Boolean get() = favoriteGenres || popularInTaste
    val needsWatchlist: Boolean get() = needsInterestDetails
    val hasVisibleRails: Boolean get() = topSuggestions || favoriteGenres || newForYou || popularInTaste
}

internal fun tmdbHomeRailLoadPlan(visibleRailIds: Collection<String>) = TmdbHomeRailLoadPlan(
    topSuggestions = "tmdb-top-suggestions" in visibleRailIds,
    favoriteGenres = "tmdb-favorite-genres" in visibleRailIds,
    newForYou = "tmdb-new-for-you" in visibleRailIds,
    popularInTaste = "tmdb-popular-in-taste" in visibleRailIds
)

private const val MAX_HOME_TASTE_SEEDS = 2

internal fun rankWatchHistorySuggestions(
    recommendationsByHistory: List<List<TitleCardProjection>>,
    limit: Int = 30
): List<TitleCardProjection> {
    data class Candidate(val item: TitleCardProjection, var matchScore: Double)

    val candidates = linkedMapOf<String, Candidate>()
    recommendationsByHistory.forEachIndexed { historyIndex, recommendations ->
        val historyWeight = 1.0 / (historyIndex + 1)
        recommendations.forEachIndexed { recommendationIndex, item ->
            val candidate = candidates.getOrPut(item.presentationKey) { Candidate(item, 0.0) }
            candidate.matchScore += historyWeight / (recommendationIndex + 1)
        }
    }

    return candidates.values
        .sortedWith(
            compareByDescending<Candidate> { it.matchScore }
                .thenByDescending { it.item.rating?.value ?: 0.0 }
                .thenBy { it.item.presentationKey }
        )
        .take(limit.coerceAtLeast(0))
        .map { it.item }
}

/**
 * Keeps direct watch-history matches in reciprocal-rank order and appends a small,
 * taste-filtered cross-type tail when the history recommendations contain only one
 * media kind. Generic discovery results must never create a Top Suggestions rail by
 * themselves, and a cross-type result must not jump ahead of a direct history match.
 */
internal fun composeTopSuggestions(
    recommendationsByHistory: List<List<TitleCardProjection>>,
    crossTypeCandidates: List<TitleCardProjection>,
    excludedPresentationKeys: Set<String> = emptySet(),
    limit: Int = 30
): List<TitleCardProjection> {
    val safeLimit = limit.coerceAtLeast(0)
    if (safeLimit == 0) return emptyList()

    val rankedMatches = rankWatchHistorySuggestions(
        recommendationsByHistory = recommendationsByHistory,
        limit = Int.MAX_VALUE
    ).filterNot { it.presentationKey in excludedPresentationKeys }
    if (rankedMatches.isEmpty()) return emptyList()

    val historyKinds = rankedMatches.mapTo(mutableSetOf()) { it.kind }
    if (historyKinds.size != 1) return rankedMatches.take(safeLimit)

    val missingKind = when (historyKinds.single()) {
        ContentKind.MOVIE -> ContentKind.SERIES
        ContentKind.SERIES -> ContentKind.MOVIE
        else -> return rankedMatches.take(safeLimit)
    }
    val matchedKeys = rankedMatches.mapTo(mutableSetOf()) { it.presentationKey }
    val crossTypeTail = crossTypeCandidates.asSequence()
        .filter { it.kind == missingKind }
        .filterNot { it.presentationKey in excludedPresentationKeys || it.presentationKey in matchedKeys }
        .distinctBy { it.presentationKey }
        .toList()
    if (crossTypeTail.isEmpty()) return rankedMatches.take(safeLimit)

    val directSuggestions = rankedMatches.take(safeLimit)
    val remainingCapacity = safeLimit - directSuggestions.size
    if (remainingCapacity <= 0) return directSuggestions

    val tailLimit = minOf(crossTypeTail.size, MAX_TOP_SUGGESTION_CROSS_TYPE_TAIL, remainingCapacity)
    return directSuggestions + crossTypeTail.take(tailLimit)
}

private const val MAX_TOP_SUGGESTION_CROSS_TYPE_TAIL = 5

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
    private val traktSyncManager: TraktSyncManager,
    private val userStateCommands: com.saab.tv.data.account.UserStateCommandRepository
) : ViewModel() {

    private val optimisticWatchedIds = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())
    private val watchedAliasChecks = ConcurrentHashMap.newKeySet<String>()

    suspend fun isWatchlisted(profileId: Int, id: String): Boolean = dao.isInWatchlist(profileId, id)
    suspend fun activeProfileId(): Int? = dao.getActiveProfileId()
    fun configureTmdbProfile(profile: com.saab.tv.data.model.ProfileEntity?) {
        tmdbProfileCache = profile
    }
    fun watchedIdsForProfile(profileId: Int): kotlinx.coroutines.flow.Flow<Set<String>> = combine(
        dao.getWatchedBadgeKeysForProfile(profileId), optimisticWatchedIds
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
            val history = accountSync.freshestPlaybackHistory(
                dao.getSeriesEpisodeHistoryForProfile(profileId, "${meta.id}:%"), profileId
            )
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

    fun toggleWatchlist(
        profileId: Int,
        item: MetaItem,
        onLocalMutationCommitted: () -> Unit = {}
    ): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val entry = WatchlistEntity(
                    profileId = profileId,
                    id = item.id,
                    type = item.type,
                    title = item.name,
                    poster = item.poster,
                    addedAt = System.currentTimeMillis(),
                    background = item.background,
                    logo = item.logo,
                    sourceAddonBaseUrl = item.addonBaseUrl
                )
            userStateCommands.toggleLibrary(profileId, entry, item.addonBaseUrl?.let(::addonInstallationKey))
            runCatching(onLocalMutationCommitted)
            accountSync.historyChanged(urgent = true)
        }

    suspend fun isTitleWatched(profileId: Int, item: MetaItem): Boolean {
        if (item.id in optimisticWatchedIds.value[profileId].orEmpty()) return true
        val id = resolveTitleId(item) ?: item.id
        if (dao.getHistoryItemForProfile(profileId, id)?.watched == true) return true
        return item.isSeriesTitle() && dao.getSeriesNextUpForProfile(profileId)
            .any { it.seriesId == id && it.isComplete }
    }

    fun markTitleWatched(
        profileId: Int,
        item: MetaItem,
        onLocalMutationCommitted: () -> Unit = {}
    ): Job {
        // Emit the card ID before the database write/resolution begins so every
        // visible card can draw its watched badge without waiting for disk/network IO.
        optimisticWatchedIds.update { current ->
            current + (profileId to (current[profileId].orEmpty() + item.id))
        }
        return viewModelScope.launch(Dispatchers.IO) {
            val id = resolveTitleId(item) ?: item.id
            if (dao.getHistoryItemForProfile(profileId, id)?.watched == true) {
                runCatching(onLocalMutationCommitted)
                return@launch
            }
            val now = System.currentTimeMillis()
            if (item.isSeriesTitle()) {
                val meta = if (item.id == id) item else repository.resolveMetaDetails("series", id, item.addonBaseUrl)
                val episodes = com.saab.tv.domain.normalizeEpisodeList(meta?.videos.orEmpty())
                val prior = dao.getSeriesEpisodeHistoryForProfile(profileId, "$id:%")
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
                val aggregate = WatchHistoryEntity(profileId, id, item.name, item.poster, item.background, item.logo,
                    0L, 0L, now, "series", watched = true)
                val nextUp = SeriesNextUpEntity(profileId, id, item.name, item.poster, 0, 0, null,
                    isComplete = true, updatedAt = now)
                val completeHistory = watchedEntries + aggregate
                userStateCommands.markWatched(profileId, id, "series", completeHistory, nextUp,
                    setOf(id, item.id), item.addonBaseUrl?.let(::addonInstallationKey))
                seekThumbnailCache.clearContentPrefix(profileId, id)
            } else {
                val previous = dao.getHistoryItemForProfile(profileId, id)
                val entry = previous?.copy(watched = true, lastWatched = now) ?: WatchHistoryEntity(
                    profileId = profileId, id = id, title = item.name, poster = item.poster,
                    background = item.background, logo = item.logo, position = 0L, duration = 0L,
                    lastWatched = now, type = "movie", watched = true
                )
                userStateCommands.markWatched(profileId, id, "movie", listOf(entry),
                    libraryAliases = setOf(id, item.id),
                    addonInstallationId = item.addonBaseUrl?.let(::addonInstallationKey))
                seekThumbnailCache.clearContent(profileId, id)
            }
            runCatching(onLocalMutationCommitted)
            accountSync.historyChanged(urgent = true)
        }
    }

    fun unmarkTitleWatched(
        profileId: Int,
        item: MetaItem,
        onLocalMutationCommitted: () -> Unit = {}
    ): Job =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = if (item.id.startsWith("tmdb:", ignoreCase = true)) {
                    resolveTitleId(item) ?: run {
                        Log.w("HomeViewModel", "Could not unmark ${item.id}: TMDB identity is unavailable")
                        return@launch
                    }
                } else item.id
                val now = System.currentTimeMillis()
                val watchedItems = if (item.isSeriesTitle()) {
                    dao.getSeriesEpisodeHistoryForProfile(profileId, "$id:%")
                        .filter { it.watched }
                } else {
                    listOfNotNull(dao.getHistoryItemForProfile(profileId, id)?.takeIf { it.watched })
                }

                val changedHistory = buildList {
                    addAll(watchedItems.map { it.copy(watched = false, lastWatched = now) })
                    dao.getHistoryItemForProfile(profileId, id)?.takeIf { it.watched }
                        ?.let { add(it.copy(watched = false, lastWatched = now)) }
                }.distinctBy { it.id }
                val changedNextUp = if (item.isSeriesTitle()) {
                    dao.getSeriesNextUpForProfile(profileId).firstOrNull { it.seriesId == id }
                        ?.copy(isComplete = false, updatedAt = now)
                } else null
                userStateCommands.unmarkWatched(profileId, id, item.type, changedHistory, changedNextUp)

                // Keep the local unwatch authoritative even if Trakt is offline.
                watchedItems.filter { it.scrobbled }.forEach { entry ->
                    try {
                        if (item.isSeriesTitle()) {
                            val parts = entry.id.split(':')
                            val offset = if (parts.size >= 4 && parts.lastOrNull()?.toIntOrNull() != null) 3 else 2
                            val season = parts.getOrNull(parts.size - offset)?.toIntOrNull()
                            val episode = parts.getOrNull(parts.size - offset + 1)?.toIntOrNull()
                            if (season != null && episode != null) {
                                traktSyncManager.pushEpisodeUnwatched(id, season, episode)
                            }
                        } else {
                            traktSyncManager.pushMovieUnwatched(id)
                        }
                    } catch (failure: Exception) {
                        Log.w("HomeViewModel", "Could not sync unwatch for ${entry.id}: ${failure.message}")
                    }
                }

                optimisticWatchedIds.update { current ->
                    current + (profileId to (current[profileId].orEmpty() - id - item.id))
                }
                runCatching(onLocalMutationCommitted)
                accountSync.historyChanged(urgent = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeViewModel", "Could not unmark ${item.id}: ${failure.message}")
            }
        }

    /** Resolve watched aliases for visible TMDB recommendations so badges match IMDb-keyed history. */
    fun ensureWatchedAlias(profileId: Int, item: MetaItem) {
        if (!item.id.startsWith("tmdb:", ignoreCase = true)) return
        if (item.id in optimisticWatchedIds.value[profileId].orEmpty()) return
        val key = "$profileId:${item.canonicalPresentationKey()}"
        if (!watchedAliasChecks.add(key)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hasWatchedHistory = dao.getWatchedIdsForProfile(profileId).first().isNotEmpty()
                val hasCompletedSeries = item.isSeriesTitle() && dao.getSeriesNextUpForProfile(profileId)
                    .any { it.isComplete }
                if (!hasWatchedHistory && !hasCompletedSeries) return@launch
                val id = resolveTitleId(item) ?: return@launch
                val watched = dao.getHistoryItemForProfile(profileId, id)?.watched == true ||
                    (item.isSeriesTitle() && dao.getSeriesNextUpForProfile(profileId)
                        .any { it.seriesId == id && it.isComplete })
                if (watched) {
                    optimisticWatchedIds.update { current ->
                        current + (profileId to (current[profileId].orEmpty() + item.id))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeViewModel", "Could not resolve watched badge for ${item.id}: ${failure.message}")
            } finally {
                watchedAliasChecks.remove(key)
            }
        }
    }

    private suspend fun resolveTitleId(item: MetaItem): String? {
        if (!item.id.startsWith("tmdb:", ignoreCase = true)) return item.id
        val tmdbId = item.id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return null
        return tmdbService.tmdbToImdb(tmdbId, tmdbService.normalizeMediaType(item.type))
    }

    private fun MetaItem.isSeriesTitle(): Boolean =
        type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)

    fun clearContinueProgress(
        profileId: Int,
        item: MetaItem,
        onLocalMutationCommitted: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = if (item.type == "series") dao.getSeriesEpisodeHistoryForProfile(profileId, "${item.id}:%")
                else listOfNotNull(dao.getHistoryItemForProfile(profileId, item.id))
            userStateCommands.clearTitleProgress(profileId, item.id, item.type, existing)
            runCatching(onLocalMutationCommitted)
            if (item.type == "series") {
                sourceSelectionStore.clearSelectionsForPrefix(item.id)
                playbackTrackSelectionStore.clearSelectionsForPrefix(item.id)
                seekThumbnailCache.clearContentPrefix(profileId, item.id)
            } else {
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
    @Volatile
    private var screenGeneration = 0L
    private val tmdbEnrichmentLock = Any()
    private val tmdbEnrichmentJobs = mutableMapOf<String, Job>()
    private val loadMoreJobs = mutableMapOf<String, Job>()
    private data class TmdbRailPageSource(
        val language: String,
        val discoverQueries: List<TmdbNaturalQuery> = emptyList(),
        val recommendationSeeds: List<Pair<String, String>> = emptyList()
    )
    private data class TmdbRailPagination(
        val source: TmdbRailPageSource,
        var page: Int,
        var hasMore: Boolean
    )
    private val tmdbRailPagination = mutableMapOf<String, TmdbRailPagination>()
    private var lastFocusedKeyMemory: String? = null
    private val rowScrollPositionsMemory = mutableMapOf<String, Pair<Int, Int>>()
    private var verticalScrollPositionMemory: Pair<Int, Int> = Pair(0, 0)
    private var hadHistoryWhenPositionSaved: Boolean = false
    private var isRestoringPosition: Boolean = false

    private fun advanceScreenGeneration(): Long = synchronized(tmdbEnrichmentLock) {
        screenGeneration += 1
        tmdbEnrichmentJobs.values.forEach { it.cancel() }
        tmdbEnrichmentJobs.clear()
        screenGeneration
    }

    data class HomeState(
        val mixedRows: List<HomeRowItem> = emptyList(),
        /** Canonicalized UI rows; provider page DTOs remain behind the repository adapter. */
        val rows: List<CategoryRow> = emptyList(),
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
        /** Typed card state for the configured hero row; provider HomeRow stays at the repository edge. */
        val heroRow: CategoryRow? = null,
        val loadedProfileId: Int? = null,
        val watchedIds: Set<String> = emptySet(), // IMDb IDs of watched items (movies + series)
        val enrichedMeta: Map<String, MetaItem> = emptyMap(),
        val tmdbEnabled: Boolean = false,
        val tmdbEnrichedIds: Set<String> = emptySet(),
        val errorMessage: String? = null,
        val loadableRowIds: Set<String> = emptySet(),
        val loadingRowIds: Set<String> = emptySet(),
        val loadMoreErrorRowIds: Set<String> = emptySet(),
        val exhaustedRowIds: Set<String> = emptySet(),
        val loadingHubIds: Set<String> = emptySet(),
        val hubErrorIds: Set<String> = emptySet()
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
    private val tmdbAfterMetadataResolution = mutableMapOf<String, Pair<MetaItem, Long>>()
    private val hubInitialLoadCount = 100
    private val initialDashboardBatchSize = 6
    private val initialDashboardTimeoutMs = 2_500L

    // UI batching: per-row pending buffers and dedup tracking
    companion object {
        private const val ROW_BATCH_SIZE = 50
    }
    private val pendingRowItems = mutableMapOf<String, MutableList<MetaItem>>()
    private val allFetchedRowIds = mutableMapOf<String, MutableSet<String>>()
    private val catalogNextSkip = mutableMapOf<String, Int>()

    private data class MetadataFallback(
        val name: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: String?,
        val runtime: String?,
        val genres: List<String>?,
        val source: MetadataSource,
        val sourceBaseUrl: String?
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
        val currentState = _state.value
        val currentRows = currentState.rows + currentState.personalizedRows
        val row = currentRows.find { it.id == configId } ?: return
        if (configId in currentState.exhaustedRowIds) return
        if (row.catalogUrl.isEmpty()) {
            val paging = tmdbRailPagination[configId] ?: return
            if (!paging.hasMore) {
                _state.update { it.copy(loadableRowIds = it.loadableRowIds - configId, exhaustedRowIds = it.exhaustedRowIds + configId) }
                return
            }
            loadMoreTmdbItems(row, paging)
            return
        }
        if (!row.supportsSkip) {
            _state.update { it.copy(loadableRowIds = it.loadableRowIds - configId, exhaustedRowIds = it.exhaustedRowIds + configId) }
            return
        }

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
        _state.update {
            it.copy(
                loadingRowIds = it.loadingRowIds + configId,
                loadMoreErrorRowIds = it.loadMoreErrorRowIds - configId
            )
        }
        val generation = screenGeneration

        loadMoreJobs[configId]?.cancel()
        loadMoreJobs[configId] = viewModelScope.launch {
            try {
                // Initialize dedup set from current row items if not yet tracked
                val fetchedIds = allFetchedRowIds.getOrPut(configId) {
                    row.items.map { it.presentationKey }.toMutableSet()
                }

                var nextSkip = catalogNextSkip.getOrPut(configId) { row.items.size }
                var page: AddonRepository.CatalogPageResult
                var newUniqueItems = emptyList<MetaItem>()
                var attempts = 0
                do {
                    page = repository.fetchNextCatalogPageWithStatus(row.catalogUrl, nextSkip)
                    if (generation != screenGeneration) return@launch
                    val latestRow = _state.value.rows.find { it.id == configId }
                    if (latestRow?.catalogUrl != row.catalogUrl) return@launch
                    if (page.failed) {
                        _state.update { it.copy(loadMoreErrorRowIds = it.loadMoreErrorRowIds + configId) }
                        return@launch
                    }
                    if (page.items.isEmpty()) {
                        _state.update {
                            it.copy(
                                loadableRowIds = it.loadableRowIds - configId,
                                exhaustedRowIds = it.exhaustedRowIds + configId
                            )
                        }
                        return@launch
                    }
                    nextSkip += page.items.size
                    catalogNextSkip[configId] = nextSkip
                    newUniqueItems = page.items.filter { item ->
                        fetchedIds.add(item.canonicalPresentationKey())
                    }
                    attempts++
                } while (newUniqueItems.isEmpty() && attempts < 3)

                if (newUniqueItems.isNotEmpty()) {
                    // Show first batch immediately, buffer the rest
                    val batch = newUniqueItems.take(ROW_BATCH_SIZE)
                    if (newUniqueItems.size > ROW_BATCH_SIZE) {
                        pendingRowItems.getOrPut(configId) { mutableListOf() }
                            .addAll(newUniqueItems.drop(ROW_BATCH_SIZE))
                    }
                    appendItemsToRow(configId, batch)
                } else {
                    // Keep an explicit Load More action available if a provider returns
                    // repeated aliases; the next click advances from the raw page offset.
                    _state.update { it.copy(loadMoreErrorRowIds = it.loadMoreErrorRowIds - configId) }
                }
                _state.update { it.copy(loadMoreErrorRowIds = it.loadMoreErrorRowIds - configId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadMoreErrorRowIds = it.loadMoreErrorRowIds + configId) }
            } finally {
                if (generation == screenGeneration) {
                    loadingMoreRows.remove(configId)
                    loadMoreJobs.remove(configId)
                    _state.update { it.copy(loadingRowIds = it.loadingRowIds - configId) }
                }
            }
        }
    }

    private fun loadMoreTmdbItems(row: CategoryRow, paging: TmdbRailPagination) {
        if (!paging.hasMore) return
        loadingMoreRows.add(row.id)
        _state.update {
            it.copy(
                loadingRowIds = it.loadingRowIds + row.id,
                loadMoreErrorRowIds = it.loadMoreErrorRowIds - row.id
            )
        }
        val generation = screenGeneration
        loadMoreJobs[row.id]?.cancel()
        loadMoreJobs[row.id] = viewModelScope.launch {
            try {
                val current = _state.value
                val latestRow = (current.rows + current.personalizedRows).firstOrNull { it.id == row.id }
                if (latestRow == null || latestRow.title != row.title) return@launch
                val knownKeys = (current.rows + current.personalizedRows)
                    .flatMap { it.items }
                    .asSequence()
                    .map { it.presentationKey }
                    .toHashSet()
                var nextPage: com.saab.tv.data.tmdb.TmdbDiscoveryPage
                var additions = emptyList<MetaItem>()
                var attempts = 0
                do {
                    nextPage = loadTmdbRailPage(paging.source, paging.page + 1)
                    if (generation != screenGeneration) return@launch
                    additions = nextPage.items.mapNotNull { projection ->
                        projection.asMetaItem().takeIf { it.canonicalPresentationKey() !in knownKeys }
                            ?.also { knownKeys += it.canonicalPresentationKey() }
                    }
                    paging.page = nextPage.page
                    paging.hasMore = nextPage.hasMore
                    attempts++
                    // TMDB can return a page made entirely of items already visible in other rails.
                    // Skip a few such pages in one request so the grid doesn't stall at the same end.
                } while (additions.isEmpty() && paging.hasMore && attempts < 3)

                if (additions.isNotEmpty()) appendItemsToRow(row.id, additions)
                _state.update { current ->
                    current.copy(
                        loadableRowIds = if (paging.hasMore) current.loadableRowIds + row.id else current.loadableRowIds - row.id,
                        exhaustedRowIds = if (paging.hasMore) current.exhaustedRowIds - row.id else current.exhaustedRowIds + row.id,
                        loadMoreErrorRowIds = current.loadMoreErrorRowIds - row.id
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeViewModel", "TMDB rail page failed for ${row.id}: ${failure.message}")
                _state.update { it.copy(loadMoreErrorRowIds = it.loadMoreErrorRowIds + row.id) }
            } finally {
                if (generation == screenGeneration) {
                    loadingMoreRows.remove(row.id)
                    loadMoreJobs.remove(row.id)
                    _state.update { it.copy(loadingRowIds = it.loadingRowIds - row.id) }
                }
            }
        }
    }

    private suspend fun loadTmdbRailPage(source: TmdbRailPageSource, page: Int): com.saab.tv.data.tmdb.TmdbDiscoveryPage =
        kotlinx.coroutines.coroutineScope {
            val recommendationPages = source.recommendationSeeds.map { (id, type) ->
                async(Dispatchers.IO) {
                    tmdbMetadataService.fetchRecommendationsPage(id, type, source.language, page, maxItems = 30)
                }
            }.awaitAll()
            val discoverPages = source.discoverQueries.map { query ->
                async(Dispatchers.IO) {
                    tmdbMetadataService.discoverByIntentPage(query, source.language, page, maxItems = 30)
                }
            }.awaitAll()
            val recommendationItems = recommendationPages.map { it.items }
            val discoveryItems = discoverPages.flatMap { it.items }
            val candidates = if (source.recommendationSeeds.isNotEmpty()) {
                composeTopSuggestions(recommendationItems, discoveryItems)
            } else {
                mixTmdbMediaTypes(discoveryItems, 30)
            }
            com.saab.tv.data.tmdb.TmdbDiscoveryPage(
                items = candidates.distinctBy { it.presentationKey }.take(30),
                page = page,
                hasMore = recommendationPages.any { it.hasMore } || discoverPages.any { it.hasMore }
            )
        }

    private fun appendItemsToRow(configId: String, newItems: List<MetaItem>) {
        _state.update { current ->
            val updatedRows = current.rows.map { r ->
                if (r.id == configId) r.copy(items = r.items + newItems.map(CatalogCardProjection::from))
                else r
            }
            val updatedPersonalizedRows = current.personalizedRows.map { r ->
                if (r.id == configId) r.copy(items = r.items + newItems.map(CatalogCardProjection::from))
                else r
            }

            val updatedMixed = current.mixedRows.map { item ->
                if (item is CategoryRow && item.id == configId) {
                    updatedRows.find { it.id == configId }
                        ?: updatedPersonalizedRows.find { it.id == configId }
                        ?: item
                } else item
            }

            current.copy(
                rows = updatedRows,
                personalizedRows = updatedPersonalizedRows,
                mixedRows = updatedMixed
            )
        }
    }

    /**
     * Invalidates the cached screen data, forcing a reload on next loadScreen() call.
     * Call this after making changes in the Dashboard Editor.
     */
    fun invalidate() {
        advanceScreenGeneration()
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
        loadingMoreRows.clear()
        pendingRowItems.clear()
        allFetchedRowIds.clear()
        catalogNextSkip.clear()
        _state.value = _state.value.copy(
            loadedScreen = null,
            loadedProfileId = null,
            loadableRowIds = emptySet(),
            loadingRowIds = emptySet(),
            loadMoreErrorRowIds = emptySet(),
            exhaustedRowIds = emptySet(),
            loadingHubIds = emptySet(),
            hubErrorIds = emptySet()
        )
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
            .distinctBy { it.canonicalPresentationKey() }
            .take(6)

        candidates.forEach { ensureMetadataFallback(it) }
        if (tmdbProfileCache?.titleCardShape == "landscape") {
            candidates.forEach(::ensureTmdbEnrichment)
        }
    }

    private fun needsMetadataFallback(item: MetaItem): Boolean {
        val lacksCinemetaOrigin = item.id.matches(Regex("tt[0-9]+")) &&
            item.metadataSource() != MetadataSource.CINEMETA
        return lacksCinemetaOrigin || item.poster.isNullOrBlank() ||
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
        val key = item.canonicalPresentationKey()

        if (metadataFallbackCache.containsKey(key)) {
            val cachedFallback = metadataFallbackCache[key]
            if (cachedFallback != null) {
                applyMetadataFallbackToState(key, cachedFallback, sourceItem = item)
            }
            return
        }

        if (!metadataRequestsInFlight.add(key)) return

        viewModelScope.launch {
            try {
                val meta = repository.resolveMetaDetails(item.type, item.id)
                    ?: throw Exception("No meta found")
                val fallback = MetadataFallback(
                    name = meta.name,
                    poster = meta.poster,
                    background = meta.background,
                    logo = meta.logo,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo,
                    imdbRating = meta.imdbRating,
                    runtime = meta.runtime,
                    genres = meta.genres,
                    source = meta.metadataSource(),
                    sourceBaseUrl = meta.addonBaseUrl
                )
                metadataFallbackCache[key] = fallback
                applyMetadataFallbackToState(key, fallback, sourceItem = item)

                // Canonical metadata is stored by AddonRepository. Do not copy resolved artwork
                // back into playback rows: that duplicates catalogue facts in user-state and
                // creates an unjournaled second artwork source.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                metadataFallbackCache[key] = null
            } finally {
                metadataRequestsInFlight.remove(key)
                val pending = tmdbAfterMetadataResolution.remove(key)
                if (pending != null && pending.second == screenGeneration) {
                    startTmdbEnrichment(pending.first)
                }
            }
        }
    }

    private fun applyFallbackToMeta(meta: MetaItem, fallback: MetadataFallback): MetaItem {
        fun preferFallback(existing: String?, incoming: String?): String? = when {
            incoming.isNullOrBlank() -> existing
            existing.isNullOrBlank() || fallback.source == MetadataSource.CINEMETA -> incoming
            else -> existing
        }
        val patchedName = preferFallback(meta.name, fallback.name) ?: meta.name
        val patchedPoster = preferFallback(meta.poster, fallback.poster)
        val patchedBackground = preferFallback(meta.background, fallback.background)
        val patchedLogo = preferFallback(meta.logo, fallback.logo)
        val patchedDescription = preferFallback(meta.description, fallback.description)
        val patchedReleaseInfo = preferFallback(meta.releaseInfo, fallback.releaseInfo)
        val patchedImdbRating = preferFallback(meta.imdbRating, fallback.imdbRating)
        val patchedRuntime = preferFallback(meta.runtime, fallback.runtime)
        val patchedGenres = if (fallback.genres.isNullOrEmpty()) meta.genres else
            if (meta.genres.isNullOrEmpty() || fallback.source == MetadataSource.CINEMETA) fallback.genres else meta.genres

        return meta.copy(
            name = patchedName ?: meta.name,
            poster = patchedPoster,
            background = patchedBackground,
            logo = patchedLogo,
            description = patchedDescription,
            releaseInfo = patchedReleaseInfo,
            imdbRating = patchedImdbRating,
            runtime = patchedRuntime,
            genres = patchedGenres,
            addonBaseUrl = if (fallback.source == MetadataSource.CINEMETA) fallback.sourceBaseUrl else meta.addonBaseUrl
        )
    }

    private fun patchMetaListWithFallback(
        items: List<CatalogCardProjection>,
        presentationKey: String,
        fallback: MetadataFallback
    ): Pair<List<CatalogCardProjection>, Boolean> {
        if (items.none { it.presentationKey == presentationKey }) return items to false
        var changed = false
        val patched = items.map { card ->
            if (card.presentationKey == presentationKey) {
                val meta = card.asActionItem()
                val merged = applyFallbackToMeta(meta, fallback)
                if (merged != meta) {
                    changed = true
                    CatalogCardProjection.from(merged)
                } else card
            } else {
                card
            }
        }
        return patched to changed
    }

    private fun applyMetadataFallbackToState(
        presentationKey: String,
        fallback: MetadataFallback,
        sourceItem: MetaItem? = null
    ) {
        _state.update { current ->
            var stateChanged = false

            val updatedRows = current.rows.map { row ->
                val (patchedItems, changed) = patchMetaListWithFallback(
                    row.items,
                    presentationKey,
                    fallback
                )
                if (changed) {
                    stateChanged = true
                    row.copy(items = patchedItems)
                } else {
                    row
                }
            }

            val updatedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val (patchedItems, changed) = patchMetaListWithFallback(
                        rowItem.items,
                        presentationKey,
                        fallback
                    )
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
                val (patchedItems, changed) = patchMetaListWithFallback(
                    hero.items,
                    presentationKey,
                    fallback
                )
                if (changed) {
                    stateChanged = true
                    hero.copy(items = patchedItems)
                } else {
                    hero
                }
            }

            val enrichedKey = presentationKey
            val enrichedBase = current.enrichedMeta[enrichedKey] ?: sourceItem
            val enrichedCandidate = enrichedBase?.let { applyFallbackToMeta(it, fallback) }
            val updatedEnrichedMeta = if (enrichedCandidate != null &&
                (enrichedCandidate != enrichedBase || enrichedKey !in current.enrichedMeta)
            ) {
                stateChanged = true
                current.enrichedMeta + (enrichedKey to enrichedCandidate)
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

    @Volatile
    private var tmdbProfileCache: com.saab.tv.data.model.ProfileEntity? = null

    /**
     * Enriches a single item with TMDB metadata. Called for every item as it becomes visible,
     * piggybacking on the existing ensureMetadataFallback flow.
     */
    fun ensureTmdbEnrichment(item: MetaItem?) {
        if (item == null) return
        // Resolve Cinemeta first. TMDB is a gap-filler only, never a competing artwork source.
        ensureMetadataFallback(item)
        val profile = tmdbProfileCache ?: return
        ensureWatchedAlias(profile.id, item)
        if (!profile.tmdbEnabled) return

        val marker = item.canonicalPresentationKey()
        if (marker in metadataRequestsInFlight) {
            tmdbAfterMetadataResolution[marker] = item to screenGeneration
            return
        }
        startTmdbEnrichment(item)
    }

    private fun startTmdbEnrichment(item: MetaItem) {
        val profile = tmdbProfileCache ?: return
        if (!profile.tmdbEnabled) return
        val marker = item.canonicalPresentationKey()
        val includeLogo = profile.titleCardShape == "landscape"
        val artworkMarker = if (includeLogo) "$marker:logo" else "$marker:poster"
        if (artworkMarker in _state.value.tmdbEnrichedIds) return
        val resolvedItem = _state.value.enrichedMeta[marker] ?: item
        if (!needsTmdbEnrichment(resolvedItem, includeLogo)) {
            markTmdbEnriched(item, includeLogo, screenGeneration)
            return
        }
        val key = "tmdb:$marker${if (includeLogo) ":logo" else ":poster"}"
        val language = profile.tmdbLanguage.ifBlank { null } ?: "en"
        synchronized(tmdbEnrichmentLock) {
            if (tmdbEnrichmentJobs.containsKey(key)) return
            val generation = screenGeneration
            lateinit var enrichmentJob: Job
            enrichmentJob = viewModelScope.launch(
                Dispatchers.IO,
                start = kotlinx.coroutines.CoroutineStart.LAZY
            ) {
                try {
                    if (generation != screenGeneration) return@launch
                    val mediaType = tmdbService.normalizeMediaType(resolvedItem.type)
                    val tmdbId = tmdbService.ensureTmdbId(resolvedItem.id, mediaType)
                    if (generation != screenGeneration) return@launch
                    if (tmdbId == null) {
                        // Can't resolve (e.g. Kitsu IDs) — mark as done so UI doesn't stay hidden
                        markTmdbEnriched(item, includeLogo, generation)
                        return@launch
                    }
                    val enrichment = tmdbMetadataService.fetchHomeEnrichment(
                        tmdbId, mediaType, language, includeLogo = includeLogo
                    )
                    if (generation != screenGeneration) return@launch
                    if (enrichment == null) {
                        markTmdbEnriched(item, includeLogo, generation)
                        return@launch
                    }

                    val fallback = MetadataFallback(
                        name = enrichment.localizedTitle,
                        poster = enrichment.poster,
                        background = enrichment.backdrop,
                        logo = enrichment.logo,
                        description = enrichment.description,
                        releaseInfo = enrichment.releaseInfo,
                        imdbRating = null,
                        runtime = enrichment.runtimeMinutes?.let { "${it}m" },
                        genres = enrichment.genres.ifEmpty { null },
                        source = MetadataSource.TMDB,
                        sourceBaseUrl = null
                    )

                    applyTmdbEnrichmentToState(resolvedItem, fallback, includeLogo, generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "TMDB enrichment failed for ${item.id}: ${e.message}")
                    markTmdbEnriched(item, includeLogo, generation)
                } finally {
                    synchronized(tmdbEnrichmentLock) {
                        if (tmdbEnrichmentJobs[key] === enrichmentJob) tmdbEnrichmentJobs.remove(key)
                    }
                }
            }
            tmdbEnrichmentJobs[key] = enrichmentJob
            enrichmentJob.start()
        }
    }

    private fun markTmdbEnriched(item: MetaItem, includeLogo: Boolean = false, expectedGeneration: Long) {
        if (expectedGeneration != screenGeneration) return
        val marker = item.canonicalPresentationKey()
        val artworkMarker = if (includeLogo) "$marker:logo" else "$marker:poster"
        val markers = setOf(marker, artworkMarker)
        _state.update {
            if (expectedGeneration == screenGeneration) {
                it.copy(tmdbEnrichedIds = it.tmdbEnrichedIds + markers)
            } else it
        }
    }

    /**
     * Applies TMDB enrichment only to gaps in the card's current canonical metadata.
     */
    private fun applyTmdbEnrichmentToState(
        sourceItem: MetaItem,
        fallback: MetadataFallback,
        includeLogo: Boolean = false,
        expectedGeneration: Long
    ) {
        if (expectedGeneration != screenGeneration) return
        val marker = sourceItem.canonicalPresentationKey()
        val artworkMarker = if (includeLogo) "$marker:logo" else "$marker:poster"
        val markers = setOf(marker, artworkMarker)
        _state.update { current ->
            if (expectedGeneration != screenGeneration) return@update current
            fun overwriteMeta(meta: MetaItem): MetaItem {
                if (meta.canonicalPresentationKey() != marker) return meta
                return meta.copy(
                    name = preferExistingText(meta.name, fallback.name) ?: meta.name,
                    poster = preferExistingArtwork(meta.poster, fallback.poster),
                    background = preferExistingArtwork(meta.background, fallback.background),
                    logo = preferExistingArtwork(meta.logo, fallback.logo),
                    description = preferExistingText(meta.description, fallback.description),
                    releaseInfo = preferExistingText(meta.releaseInfo, fallback.releaseInfo),
                    imdbRating = preferExistingText(meta.imdbRating, fallback.imdbRating),
                    runtime = preferExistingText(meta.runtime, fallback.runtime),
                    genres = preferExistingGenres(meta.genres, fallback.genres)
                )
            }

            fun patchCards(cards: List<CatalogCardProjection>): Pair<List<CatalogCardProjection>, Boolean> {
                if (cards.none { it.presentationKey == marker }) return cards to false
                var changed = false
                val patched = cards.map { card ->
                    if (card.presentationKey != marker) return@map card
                    val meta = card.asActionItem()
                    CatalogCardProjection.from(overwriteMeta(meta)).also { if (it != card) changed = true }
                }
                return patched to changed
            }

            var rowsChanged = false
            val mappedRows = current.rows.map { row ->
                val (patched, changed) = patchCards(row.items)
                if (changed) {
                    rowsChanged = true
                    row.copy(items = patched)
                } else row
            }
            val updatedRows = if (rowsChanged) mappedRows else current.rows

            var mixedRowsChanged = false
            val mappedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val (patched, changed) = patchCards(rowItem.items)
                    if (changed) {
                        mixedRowsChanged = true
                        rowItem.copy(items = patched)
                    } else rowItem
                } else rowItem
            }
            val updatedMixedRows = if (mixedRowsChanged) mappedMixedRows else current.mixedRows

            var heroChanged = false
            val mappedHeroRow = current.heroRow?.let { hero ->
                val (patched, changed) = patchCards(hero.items)
                if (changed) {
                    heroChanged = true
                    hero.copy(items = patched)
                } else hero
            }
            val updatedHeroRow = mappedHeroRow.takeIf { heroChanged } ?: current.heroRow

            // Always store enriched preview in enrichedMeta so continue watching
            // items (which aren't in category rows) can pick up TMDB metadata.
            val enrichedKey = marker
            val base = current.enrichedMeta[enrichedKey] ?: sourceItem
            val enriched = base.copy(
                name = preferExistingText(base.name, fallback.name) ?: base.name,
                poster = preferExistingArtwork(base.poster, fallback.poster),
                background = preferExistingArtwork(base.background, fallback.background),
                logo = preferExistingArtwork(base.logo, fallback.logo),
                description = preferExistingText(base.description, fallback.description),
                releaseInfo = preferExistingText(base.releaseInfo, fallback.releaseInfo),
                imdbRating = preferExistingText(base.imdbRating, fallback.imdbRating),
                runtime = preferExistingText(base.runtime, fallback.runtime),
                genres = preferExistingGenres(base.genres, fallback.genres)
            )
            val updatedEnrichedMeta = if (enriched != base || !current.enrichedMeta.containsKey(enrichedKey)) {
                current.enrichedMeta + (enrichedKey to enriched)
            } else current.enrichedMeta

            current.copy(
                rows = updatedRows,
                mixedRows = updatedMixedRows,
                heroRow = updatedHeroRow,
                enrichedMeta = updatedEnrichedMeta,
                tmdbEnrichedIds = current.tmdbEnrichedIds + markers
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
        tmdbProfileCache = currentProfile
        val initialBatchSize = initialDashboardBatchSizeFor(screenName, initialDashboardBatchSize)
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
        val generation = advanceScreenGeneration()
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
        loadingMoreRows.clear()
        tmdbRailPagination.clear()
        loadJob = viewModelScope.launch {
            // Clear 'rows' immediately so the UI doesn't show "Ghost Data" from the previous tab
            pendingRowItems.clear()
            allFetchedRowIds.clear()
            catalogNextSkip.clear()
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
                loadableRowIds = emptySet(),
                loadingRowIds = emptySet(),
                loadMoreErrorRowIds = emptySet(),
                exhaustedRowIds = emptySet(),
                loadingHubIds = emptySet(),
                hubErrorIds = emptySet(),
                errorMessage = null
            )
            lastFocusedKeyMemory = null
            rowScrollPositionsMemory.clear()
            verticalScrollPositionMemory = Pair(0, 0)
            hadHistoryWhenPositionSaved = false
            isRestoringPosition = false

            // Load History + Series Next Up only for Home
            if (screenName == "home") {
                launch {
                    val historyFlow = currentProfileId?.let(dao::getWatchHistoryForProfile) ?: flowOf(emptyList())
                    historyFlow.collect { history ->
                        _state.update { it.copy(history = history) }
                    }
                }
                launch {
                    val nextUpFlow = currentProfileId?.let(dao::getSeriesNextUpForProfileFlow) ?: flowOf(emptyList())
                    nextUpFlow.collect { nextUp ->
                        _state.update { it.copy(seriesNextUp = nextUp) }
                    }
                }
            } else {
                _state.value = _state.value.copy(history = emptyList(), seriesNextUp = emptyList())
            }

            // Load watched IDs for all tabs (watched indicator on posters)
            launch {
                val watchedIdsFlow = currentProfileId?.let(dao::getWatchedIdsForProfile) ?: flowOf(emptyList())
                watchedIdsFlow.collect { ids ->
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
                    tmdbProfileCache = null

                    _state.update {
                        it.copy(
                            mixedRows = ottRows.map { row -> CategoryRow.fromHomeRow(row) },
                            rows = ottRows.map(CategoryRow::fromHomeRow),
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
                        maxConfigs = initialBatchSize,
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

                val initialMixedList = (hubRows + initialRows.map { CategoryRow.fromHomeRow(it) })
                    .sortedBy { it.order }

                // Cache profile for TMDB enrichment (called per-item as they become visible)
                tmdbProfileCache = currentProfile

                _state.update {
                    it.copy(
                        mixedRows = initialMixedList,
                        rows = initialRows.map(CategoryRow::fromHomeRow),
                        loadableRowIds = initialRows.filter { it.supportsSkip }.map { it.configId }.toSet(),
                        hubRows = hubRows,
                            heroRow = heroRow?.let(CategoryRow::fromHomeRow),
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
                                loadableRowIds = current.loadableRowIds + personalized.filter {
                                    tmdbRailPagination[it.id]?.hasMore == true
                                }.map { it.id },
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
                        skipConfigs = initialBatchSize
                    )
                    if (generation != screenGeneration) return@launch
                    if (remainingRows.isEmpty()) return@launch

                    _state.update { currentState ->
                        if (currentState.loadedScreen != screenName || currentState.loadedProfileId != currentProfileId) {
                            return@update currentState
                        }

                        val allRows = (currentState.rows + remainingRows.map(CategoryRow::fromHomeRow))
                            .distinctBy { it.id }
                            .sortedBy { it.order }

                        val resolvedHeroRow = when {
                            currentState.heroRow != null -> currentState.heroRow
                            heroConfig?.categoryId != null -> allRows.find { it.id == heroConfig.categoryId }
                            else -> null
                        }

                        val mixedList = (hubRows + allRows + currentState.personalizedRows)
                            .distinctBy { it.id }.sortedBy { it.order }

                        currentState.copy(
                            mixedRows = mixedList,
                            rows = allRows,
                            loadableRowIds = currentState.loadableRowIds + remainingRows.filter { it.supportsSkip }.map { it.configId },
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
                    else -> "catalog_load_failed"
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
            val visibleTmdbRails = TmdbHomeRails.parse(profile.homeTmdbRails)
            val loadPlan = tmdbHomeRailLoadPlan(visibleTmdbRails)
            if (!loadPlan.hasVisibleRails) return emptyList()
            val history = if (loadPlan.needsTasteSeeds) dao.getWatchHistoryForProfileOnce(profile.id) else emptyList()
            val watchlist = if (loadPlan.needsWatchlist) dao.getWatchlistOnce(profile.id) else emptyList()
            val watchedTitles = history.asSequence()
                .filter { it.watched || it.position > 0L }
                .groupBy { if (it.type == "series") com.saab.tv.domain.seriesIdFromPlaybackId(it.id) else it.id }
                .mapNotNull { (id, entries) ->
                    entries.maxByOrNull { it.lastWatched }?.let { latest -> id to latest }
                }
                .sortedByDescending { it.second.lastWatched }
                .take(MAX_HOME_TASTE_SEEDS)
                .map { (id, latest) ->
                    Triple(id, latest.type, latest.title)
                }
                .toList()
            val interestSeeds = if (loadPlan.needsInterestDetails) {
                (watchedTitles + watchlist.take(2).map { Triple(it.id, it.type, it.title) })
                    .distinctBy { "${it.second}:${it.first}" }.take(MAX_HOME_TASTE_SEEDS)
            } else emptyList()
            val language = profile.tmdbLanguage.ifBlank { "en" }
            val visibleIds = homeRows.flatMap { row -> row.items.map { it.canonicalPresentationKey() } }.toSet()
            val usedTmdbKeys = mutableSetOf<String>()
            val output = mutableListOf<CategoryRow>()

            fun previewsToItems(previews: List<TitleCardProjection>, limit: Int = 30): List<MetaItem> =
                previews.asSequence().mapNotNull { result ->
                    val item = result.asMetaItem()
                    val tmdbKey = item.canonicalPresentationKey()
                    if (tmdbKey in usedTmdbKeys || tmdbKey in visibleIds) return@mapNotNull null
                    usedTmdbKeys += tmdbKey
                    item
                }.take(limit).toList()

            fun addRow(
                id: String,
                title: String,
                order: Int,
                previews: List<TitleCardProjection>,
                pageSource: TmdbRailPageSource? = null,
                firstPage: com.saab.tv.data.tmdb.TmdbDiscoveryPage? = null
            ) {
                val railIndex = visibleTmdbRails.indexOf(id)
                if (railIndex < 0) return
                val items = previewsToItems(previews)
                if (items.isNotEmpty()) {
                    output += CategoryRow(
                        id = id,
                        title = title,
                        // Keep asynchronous recommendations after existing catalog rows. Inserting
                        // ahead of the current row changed index-based focus keys and dropped focus.
                        order = personalizedRailOrder(10 + railIndex),
                        items = items.map(CatalogCardProjection::from),
                        isInfiniteLoopEnabled = true,
                        visibleItemCount = 15,
                        isInfiniteScrollingEnabled = false
                    )
                    if (pageSource != null && firstPage != null) {
                        tmdbRailPagination[id] = TmdbRailPagination(pageSource, firstPage.page, firstPage.hasMore)
                    }
                }
            }

            // Aggregate every recent history seed into one rail, ranking titles by
            // overlap and position across each seed's TMDB recommendations.
            val historyRecommendationPages = if (loadPlan.topSuggestions) {
                kotlinx.coroutines.coroutineScope {
                    watchedTitles.map { (seedId, type, _) -> async(Dispatchers.IO) {
                        val tmdbId = tmdbService.ensureTmdbId(seedId, type) ?: return@async null
                        Triple(
                            tmdbId,
                            type,
                            tmdbMetadataService.fetchRecommendationsPage(tmdbId, type, language, page = 1, maxItems = 20)
                        )
                    } }.awaitAll()
                }.filterNotNull()
            } else emptyList()
            val historyRankedSuggestions = rankWatchHistorySuggestions(
                historyRecommendationPages.map { it.third.items },
                limit = Int.MAX_VALUE
            )

            // A small, bounded enrichment sample gives genre rails and cross-type
            // suggestions real signals without slowing the initial catalog load.
            val interestDetails = if (loadPlan.needsInterestDetails) {
                kotlinx.coroutines.coroutineScope {
                    interestSeeds.map { (seedId, type, _) -> async(Dispatchers.IO) {
                        val tmdbId = tmdbService.ensureTmdbId(seedId, type) ?: return@async null
                        val enrichment = tmdbMetadataService.fetchHomeEnrichment(tmdbId, type, language) ?: return@async null
                        Triple(type, tmdbId, enrichment)
                    } }.awaitAll().filterNotNull()
                }
            } else emptyList()

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
                val historyTypes = historyRankedSuggestions.map { it.kind.keyPart }.toSet()
                val crossTypeQueries = listOf("movie", "tv").filterNot { requestedType ->
                    val normalizedType = if (requestedType == "tv") "series" else requestedType
                    normalizedType in historyTypes
                }.map { requestedType ->
                    val isTv = requestedType == "tv"
                    TmdbNaturalQuery(
                        mediaType = requestedType,
                        genreIds = if (isTv) genreFilterTv else genreFilterMovie,
                        originalLanguage = favoriteLanguage,
                        sortBy = "popularity.desc"
                    )
                }.filter { query ->
                    !query.genreIds.isNullOrBlank() || !query.originalLanguage.isNullOrBlank()
                }
                val crossTypePages = kotlinx.coroutines.coroutineScope {
                    crossTypeQueries.map { query -> async(Dispatchers.IO) {
                        tmdbMetadataService.discoverByIntentPage(query, language, page = 1, maxItems = 10)
                    } }.awaitAll()
                }
                val alreadyDisplayedKeys = visibleIds + usedTmdbKeys
                val topSuggestionPage = com.saab.tv.data.tmdb.TmdbDiscoveryPage(
                    items = composeTopSuggestions(
                        recommendationsByHistory = historyRecommendationPages.map { it.third.items },
                        crossTypeCandidates = crossTypePages.flatMap { it.items },
                        excludedPresentationKeys = alreadyDisplayedKeys,
                        limit = 30
                    ),
                    page = 1,
                    hasMore = historyRecommendationPages.any { it.third.hasMore } || crossTypePages.any { it.hasMore }
                )
                addRow(
                    "tmdb-top-suggestions",
                    "Top Suggestions",
                    10,
                    topSuggestionPage.items,
                    TmdbRailPageSource(
                        language = language,
                        discoverQueries = crossTypeQueries,
                        recommendationSeeds = historyRecommendationPages.map { it.first to it.second }
                    ),
                    topSuggestionPage
                )
            }

            if (loadPlan.favoriteGenres && allFavoriteGenres) {
                val query = TmdbNaturalQuery(null, genreFilterMovie, null, "vote_average.desc", tvGenreIds = genreFilterTv)
                val page = tmdbMetadataService.discoverByIntentPage(
                    query, language, page = 1, maxItems = 30
                )
                addRow(
                    "tmdb-favorite-genres", "More in Your Favorite Genres", 13, page.items,
                    TmdbRailPageSource(language, discoverQueries = listOf(query)), page
                )
            }

            if (loadPlan.newForYou) {
                val recentDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                    java.util.Date(System.currentTimeMillis() - 365L * 24L * 60L * 60L * 1000L)
                )
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
                val newForYouQuery = TmdbNaturalQuery(
                    null, null, null, "release_date.desc",
                    releaseDateGte = recentDate, releaseDateLte = today
                )
                val newForYou = tmdbMetadataService.discoverByIntentPage(
                    newForYouQuery, language, page = 1, maxItems = 30
                )
                addRow(
                    "tmdb-new-for-you", "New for You", 15, newForYou.items,
                    TmdbRailPageSource(language, discoverQueries = listOf(newForYouQuery)), newForYou
                )
            }

            if (loadPlan.popularInTaste) {
                val popularInTasteQuery = TmdbNaturalQuery(
                    null, genreFilterMovie, favoriteLanguage ?: profile.tmdbLanguage.takeIf(String::isNotBlank),
                    "popularity.desc", tvGenreIds = genreFilterTv
                )
                val popularInTaste = tmdbMetadataService.discoverByIntentPage(
                    popularInTasteQuery, language, page = 1, maxItems = 30
                )
                addRow(
                    "tmdb-popular-in-taste", "Popular in Your Taste", 16, popularInTaste.items,
                    TmdbRailPageSource(language, discoverQueries = listOf(popularInTasteQuery)), popularInTaste
                )
            }

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
        val legacyRow = _state.value.rows.find { it.id == hubItem.categoryId }
        if (legacyRow != null) {
            _state.update { it.copy(hubErrorIds = it.hubErrorIds - hubItem.categoryId) }
            onResult(legacyRow.title, legacyRow.items.map(CatalogCardProjection::asActionItem))
            return
        }

        // 2. Try to find in mixed rows (New Architecture)
        val mixedRow = _state.value.mixedRows.find { it.id == hubItem.categoryId } as? CategoryRow
        if (mixedRow != null) {
            _state.update { it.copy(hubErrorIds = it.hubErrorIds - hubItem.categoryId) }
            onResult(mixedRow.title, mixedRow.items.map(CatalogCardProjection::asActionItem))
            return
        }

        if (hubItem.categoryId in _state.value.loadingHubIds) return
        val generation = screenGeneration
        _state.update {
            it.copy(
                loadingHubIds = it.loadingHubIds + hubItem.categoryId,
                hubErrorIds = it.hubErrorIds - hubItem.categoryId
            )
        }
        // 3. If not found, fetch from repository
        viewModelScope.launch {
            try {
                val preview = repository.getCategoryRowPreviewWithStatus(
                    configId = hubItem.categoryId,
                    maxItems = hubInitialLoadCount
                )
                if (generation != screenGeneration) return@launch
                val fetchedRow = preview.row
                if (fetchedRow != null) {
                    // Cache fetched row so GridView can lazy-load additional pages via loadMoreItems().
                    _state.update { current ->
                        val row = CategoryRow.fromHomeRow(fetchedRow)
                        val updatedRows = (current.rows.filterNot { it.id == fetchedRow.configId } + row)
                            .sortedBy { it.order }
                        val updatedPersonalized = current.personalizedRows.filterNot { it.id == fetchedRow.configId }
                        val updatedMixed = (current.mixedRows.filterNot { it.id == fetchedRow.configId } + row)
                            .sortedBy { it.order }
                        current.copy(
                            rows = updatedRows,
                            personalizedRows = updatedPersonalized,
                            mixedRows = updatedMixed,
                            loadableRowIds = if (fetchedRow.supportsSkip) current.loadableRowIds + fetchedRow.configId else current.loadableRowIds,
                            hubErrorIds = current.hubErrorIds - hubItem.categoryId
                        )
                    }
                    onResult(fetchedRow.title, fetchedRow.items)
                } else if (preview.failed) {
                    _state.update { it.copy(hubErrorIds = it.hubErrorIds + hubItem.categoryId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == screenGeneration) {
                    _state.update { it.copy(hubErrorIds = it.hubErrorIds + hubItem.categoryId) }
                }
            } finally {
                if (generation == screenGeneration) {
                    _state.update { it.copy(loadingHubIds = it.loadingHubIds - hubItem.categoryId) }
                }
            }
        }
    }
}
