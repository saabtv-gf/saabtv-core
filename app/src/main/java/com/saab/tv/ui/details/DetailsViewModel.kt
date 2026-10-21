package com.saab.tv.ui.details

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.player.PlaybackTrackSelectionStore
import com.saab.tv.data.player.SourceSelectionStore
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.profile.AudioLanguagePreferencePolicy
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.repository.SubtitleRepository
import com.saab.tv.data.stream.StreamSortingService
import com.saab.tv.data.stream.StreamScoreCalculator
import com.saab.tv.data.tmdb.TmdbEnrichment
import com.saab.tv.data.tmdb.TmdbEpisodeEnrichment
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.data.tmdb.TmdbVideoInfo
import com.saab.tv.domain.AddonSubtitle
import com.saab.tv.domain.episodeStreamId
import com.saab.tv.domain.normalizeEpisodeList
import com.saab.tv.domain.schema.addonInstallationKey
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.data.trakt.TraktSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Extract season/episode from IMDb- and TMDB-prefixed playback IDs, ignoring file suffixes. */
internal fun playbackEpisodeCoordinates(playbackId: String): Pair<Int, Int>? {
    val parts = playbackId.split(':')
    val offset = if (parts.firstOrNull().equals("tmdb", ignoreCase = true)) 2 else 1
    if (parts.size <= offset + 1) return null
    val season = parts[offset].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val episode = parts[offset + 1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return season to episode
}

/** TMDB augments the canonical Cinemeta payload without replacing populated fields. */
internal fun mergeTmdbDetailsFallback(current: MetaItem, enrichment: TmdbEnrichment): MetaItem = current.copy(
    name = current.name.takeIf(String::isNotBlank) ?: enrichment.localizedTitle?.takeIf(String::isNotBlank) ?: current.name,
    description = current.description?.takeIf(String::isNotBlank)
        ?: enrichment.description?.takeIf(String::isNotBlank),
    logo = current.logo?.takeIf(String::isNotBlank) ?: enrichment.logo?.takeIf(String::isNotBlank),
    background = current.background?.takeIf(String::isNotBlank) ?: enrichment.backdrop?.takeIf(String::isNotBlank),
    poster = current.poster?.takeIf(String::isNotBlank) ?: enrichment.poster?.takeIf(String::isNotBlank),
    genres = current.genres?.takeIf(List<String>::isNotEmpty)
        ?: enrichment.genres.takeIf(List<String>::isNotEmpty),
    releaseInfo = current.releaseInfo?.takeIf(String::isNotBlank)
        ?: enrichment.releaseInfo?.takeIf(String::isNotBlank),
    runtime = current.runtime?.takeIf(String::isNotBlank)
        ?: enrichment.runtimeMinutes?.takeIf { it > 0 }?.let { "${it}m" }
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val dao: AddonDao,
    private val sourceSelectionStore: SourceSelectionStore,
    private val playbackTrackSelectionStore: PlaybackTrackSelectionStore,
    private val repository: AddonRepository,
    private val subtitleRepository: SubtitleRepository,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val streamSortingService: StreamSortingService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val traktSyncManager: TraktSyncManager,
    private val seekThumbnailCache: SeekThumbnailCache,
    private val deviceDisplay: com.saab.tv.data.profile.DeviceDisplayPreferences,
    private val accountSync: com.saab.tv.data.account.AccountSyncManager,
    private val userStateCommands: com.saab.tv.data.account.UserStateCommandRepository
) : ViewModel() {

    /** Per-episode watch progress for the episodes sidebar. */
    data class EpisodeProgress(
        val progress: Float,  // 0.0–1.0
        val watched: Boolean
    )

    data class DetailsState(
        /** Canonical, provider-independent fields used to render the title surface. */
        val contentProjection: TitleCardProjection? = null,
        /** Provider payload retained only for episode/trailer descriptors and exact addon actions. */
        val providerMetadata: MetaItem? = null,
        val canonicalIdentityKey: String? = null,
        val resolvedId: String? = null, // IMDb ID resolved from tmdb: prefixes, used for stream/subtitle fetching
        val contentKey: String? = null, // Tracks which item this state belongs to
        val isLoading: Boolean = true,
        /** Metadata lookup failed; local state and the current route remain intact for retry. */
        val detailsLoadFailed: Boolean = false,
        val isLoadingStreams: Boolean = false,
        val canSelectPartialSources: Boolean = false,
        val resumePlaybackId: String? = null,
        val resumeStateReady: Boolean = false,
        val resumeIsNextEpisode: Boolean = false,
        val lastPlayedEpisodeId: String? = null,
        val isMovieWatched: Boolean = false,
        val autoPlayStream: Stream? = null,
        val addonSubtitles: List<AddonSubtitle> = emptyList(),
        val availableStreams: List<Stream> = emptyList(),
        val sourceLanguagePreferences: List<String> = emptyList(),
        val sidebarState: SidebarState = SidebarState.Closed,
        val episodeProgressMap: Map<String, EpisodeProgress> = emptyMap(), // "S1:E3" → progress
        val episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(), // "S1:E3" → TMDB data
        // TMDB enrichment
        val tmdbEnabled: Boolean = false,
        val tmdbLoading: Boolean = false,
        val tmdbEnrichment: TmdbEnrichment? = null,
        val tmdbRecommendations: List<TitleCardProjection> = emptyList(),
        val trailer: TmdbVideoInfo? = null,
        val tmdbCollection: List<TitleCardProjection> = emptyList(),
        val tmdbCollectionName: String? = null
    ) {
        /** Compatibility adapter for existing playback/actions until their APIs accept typed content. */
        val meta: MetaItem?
            get() {
                val provider = providerMetadata ?: return contentProjection?.asMetaItem()
                val canonical = contentProjection?.asMetaItem() ?: provider
                return canonical.copy(
                    videos = provider.videos,
                    trailers = provider.trailers,
                    trailerStreams = provider.trailerStreams,
                    progress = provider.progress,
                    resumePlaybackId = provider.resumePlaybackId,
                    hasNewEpisode = provider.hasNewEpisode
                )
            }
    }

    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state
    private val activeProfileId = MutableStateFlow(profileConfigurationManager.getLastActiveProfileId())

    /** Reactive watchlist status — emits true/false as the current item's watchlist state changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isInWatchlist: StateFlow<Boolean> = combine(
        _state.map { current ->
            buildSet {
                current.meta?.id?.let(::add)
                current.meta?.toCanonicalProjection()?.identity?.stableKey?.let(::add)
                current.canonicalIdentityKey?.let(::add)
            }
        },
        activeProfileId
    ) { identityKeys, profileId -> profileId to identityKeys }
        .flatMapLatest { (profileId, identityKeys) ->
            if (profileId != null && identityKeys.isNotEmpty()) {
                dao.getWatchlistBadgeKeysForProfile(profileId).map { keys -> identityKeys.any(keys::contains) }
            } else flowOf(false)
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private var loadDetailsJob: Job? = null
    private var loadStreamsJob: Job? = null
    private var streamRequestGeneration = 0L

    /** Keeps a best-language choice in the source picker in sync with player audio preferences. */
    fun promoteAudioLanguagePreference(language: String) {
        val profileId = activeProfileId.value ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            val updated = AudioLanguagePreferencePolicy.promote(profile, language)
            if (updated == profile) return@launch
            userStateCommands.saveProfile(updated)
            _state.update {
                it.copy(sourceLanguagePreferences = AudioLanguagePreferencePolicy.forProfile(updated))
            }
        }
    }
    private var tmdbEnrichmentJob: Job? = null
    private var cinemetaTrailerJob: Job? = null
    private var loadRequestVersion: Long = 0L
    private var loadedContentKey: String? = null
    private var resumeRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            activeProfileId.flatMapLatest { profileId ->
                profileId?.let(dao::getWatchHistoryForProfile) ?: flowOf(emptyList())
            }.collect { refreshResumeStateIfNeeded(_state.value.meta) }
        }
    }

    // Prefetched streams cache
    private var prefetchStreamsJob: Job? = null
    private var prefetchedStreamKey: String? = null
    private var prefetchedStreams: List<Stream>? = null
    private var prefetchedSubtitles: List<AddonSubtitle>? = null
    private var prefetchSubtitlesJob: Job? = null


    fun loadDetails(type: String, id: String, addonBaseUrl: String? = null, playbackOnly: Boolean = false) {
        activeProfileId.value = profileConfigurationManager.getLastActiveProfileId()
        val requestKey = "$type:$id"
        accountSync.preparePlaybackResume()

        // Keep current details when reopening the same item (e.g., returning from player).
        if (
            loadedContentKey == requestKey &&
            _state.value.meta != null &&
            !_state.value.isLoading
        ) {
            refreshResumeStateIfNeeded(_state.value.meta)
            if (!playbackOnly) warmTrailer(_state.value.trailer)
            if (_state.value.sidebarState !is SidebarState.Closed) {
                closeSidebar()
            }
            return
        }

        loadDetailsJob?.cancel()
        tmdbEnrichmentJob?.cancel()
        tmdbEnrichmentJob = null
        cinemetaTrailerJob?.cancel()
        cinemetaTrailerJob = null
        trailerWarmupJob?.cancel()
        trailerWarmupJob = null
        resumeRefreshJob?.cancel()
        resumeRefreshJob = null
        streamRequestGeneration++
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        prefetchStreamsJob?.cancel()
        prefetchStreamsJob = null
        prefetchSubtitlesJob?.cancel()
        prefetchSubtitlesJob = null
        prefetchedStreamKey = null
        prefetchedStreams = null
        prefetchedSubtitles = null
        loadRequestVersion += 1
        val requestVersion = loadRequestVersion

        // Reset immediately so previous movie details never flash for a new item.
        _state.value = DetailsState(
            isLoading = true,
            detailsLoadFailed = false,
            resumePlaybackId = null,
            autoPlayStream = null,
            addonSubtitles = emptyList(),
            availableStreams = emptyList(),
            sidebarState = SidebarState.Closed
        )

        loadDetailsJob = viewModelScope.launch {
            try {
                // Resolve tmdb: IDs to IMDb IDs via TMDB API so all addons work consistently
                val isTmdbEnabled = profileConfigurationManager.getLastActiveProfileId()
                    ?.let { dao.getProfileById(it) }?.tmdbEnabled == true
                val resolvedId = if (id.startsWith("tmdb:", ignoreCase = true)) {
                    val tmdbNumericId = id.substringAfter(':').substringBefore(':').toIntOrNull()
                    val mediaType = tmdbService.normalizeMediaType(type)
                    tmdbNumericId?.let { tmdbService.tmdbToImdb(it, mediaType) } ?: id
                } else id

                // Movie source lookup does not depend on decorative metadata.
                if (type == "movie" && resolvedId.startsWith("tt")) prefetchStreams(type, resolvedId)
                val fetchedDetails = repository.resolveMetaDetails(type, resolvedId, addonBaseUrl)
                    ?: throw Exception("No meta found")
                // Reuse the same verified canonical title/artwork projection as cards and search;
                // this is a local-cache read and does not delay the lazy TMDB details request.
                val details = repository.canonicalizeCatalogItems(listOf(fetchedDetails)).firstOrNull()
                    ?: fetchedDetails
                if (requestVersion != loadRequestVersion) return@launch
                loadedContentKey = requestKey
                // Use resolved ID for streams — guarantees IMDb format for stream addons
                val streamFetchId = if (details.id.startsWith("tt")) details.id else resolvedId

                // Publish the Cinemeta/add-on payload immediately. Resume bookkeeping
                // and TMDB enrichment continue independently, so neither delays the
                // first usable details screen.
                _state.value = _state.value.copy(
                    contentProjection = details.toCanonicalProjection(),
                    providerMetadata = details,
                    canonicalIdentityKey = repository.canonicalIdentityKey(details),
                    resolvedId = streamFetchId,
                    contentKey = requestKey,
                    isLoading = false,
                    resumeStateReady = false,
                    trailer = details.bestAvailableTrailer(),
                    tmdbEnabled = isTmdbEnabled,
                    tmdbLoading = isTmdbEnabled && !playbackOnly
                )
                if (!playbackOnly) {
                    warmTrailer(_state.value.trailer)
                    loadTmdbEnrichment(details.type, streamFetchId, requestKey)
                    loadCinemetaTrailer(details.type, streamFetchId, requestKey)
                }

                if (details.type == "series") computeAndStoreNextUp(streamFetchId, details.name, details.poster, details.videos)
                val episodeProgressMap = if (details.type == "series") {
                    buildEpisodeProgressMap(streamFetchId, details.addonBaseUrl)
                } else emptyMap()
                val lastPlayedEpisodeId = if (details.type == "series")
                    freshSeriesHistory(streamFetchId, details.addonBaseUrl).maxByOrNull { it.lastWatched }?.id else null
                val resumePlaybackId = if (details.type == "series") {
                    val latest = freshSeriesHistory(streamFetchId, details.addonBaseUrl).maxByOrNull { it.lastWatched }
                    if (latest != null && !latest.watched && !episodeHistoryIsWatched(latest.id, episodeProgressMap)) {
                        latest.id // In-progress episode — resume it
                    } else {
                        // All episodes watched or no history — use next-up if aired
                        val profileId = activeProfileId.value
                        val nextUp = profileId?.let { nextUpForSeries(it, streamFetchId, details.addonBaseUrl) }
                        val today = java.time.LocalDate.now().toString()
                        val hasAired = nextUp != null && !nextUp.isComplete &&
                            (nextUp.nextReleased == null || nextUp.nextReleased <= today)
                        if (hasAired) {
                            "${streamFetchId}:${nextUp.nextSeason}:${nextUp.nextEpisode}"
                        } else null
                    }
                } else {
                    val movieHistory = activeProfileId.value?.let {
                        historyForPlayback(it, streamFetchId, details.type, details.addonBaseUrl)
                    }
                    if (movieHistory?.watched == true) null else movieHistory?.id
                }
                val isMovieWatched = if (details.type != "series") {
                    activeProfileId.value?.let {
                        historyForPlayback(it, streamFetchId, details.type, details.addonBaseUrl)?.watched
                    } == true
                } else false

                _state.value = _state.value.copy(
                    resumePlaybackId = resumePlaybackId,
                    resumeStateReady = true,
                    resumeIsNextEpisode = details.type == "series" && resumePlaybackId != null &&
                        activeProfileId.value?.let {
                            historyForPlayback(it, resumePlaybackId, details.type, details.addonBaseUrl)
                        }
                            ?.let { !it.watched && it.position > 0 } != true,
                    lastPlayedEpisodeId = lastPlayedEpisodeId,
                    isMovieWatched = isMovieWatched,
                    episodeProgressMap = episodeProgressMap,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList()
                )

                // Prefetch streams so they're ready when the user hits Play
                val prefetchId = if (resumePlaybackId != null) {
                    resumePlaybackId
                } else if (details.type == "series") {
                    val firstEpisode = details.videos
                        ?.filter { it.season > 0 && it.episode > 0 }
                        ?.minWithOrNull(compareBy<com.saab.tv.data.model.stremio.MetaVideo> { it.season }.thenBy { it.episode })
                    firstEpisode?.let { episodeStreamId(streamFetchId, it) } ?: streamFetchId
                } else {
                    streamFetchId
                }
                prefetchStreams(details.type, prefetchId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (requestVersion != loadRequestVersion) return@launch
                loadedContentKey = null
                _state.value = _state.value.copy(
                    contentProjection = null,
                    providerMetadata = null,
                    isLoading = false,
                    detailsLoadFailed = true,
                    resumePlaybackId = null,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList()
                )
            }
        }
    }

    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
        if (meta == null) {
            if (_state.value.resumePlaybackId != null) {
                _state.value = _state.value.copy(resumePlaybackId = null)
            }
            return
        }

        resumeRefreshJob?.cancel()
        _state.value = _state.value.copy(resumeStateReady = false)
        resumeRefreshJob = viewModelScope.launch {
            val seriesId = _state.value.resolvedId ?: meta.id
            if (meta.type == "series") computeAndStoreNextUp(seriesId, meta.name, meta.poster, meta.videos)
            val episodeProgressMap = if (meta.type == "series") buildEpisodeProgressMap(seriesId, meta.addonBaseUrl) else emptyMap()
            val lastPlayedEpisodeId = if (meta.type == "series")
                freshSeriesHistory(seriesId, meta.addonBaseUrl).maxByOrNull { it.lastWatched }?.id else null
            val resumePlaybackId = if (meta.type == "series") {
                val latest = freshSeriesHistory(seriesId, meta.addonBaseUrl).maxByOrNull { it.lastWatched }
                if (latest != null && !latest.watched && !episodeHistoryIsWatched(latest.id, episodeProgressMap)) {
                    latest.id
                } else {
                    val profileId = activeProfileId.value
                    val nextUp = profileId?.let { nextUpForSeries(it, seriesId, meta.addonBaseUrl) }
                    val today = java.time.LocalDate.now().toString()
                    val hasAired = nextUp != null && !nextUp.isComplete &&
                        (nextUp.nextReleased == null || nextUp.nextReleased <= today)
                    if (hasAired) {
                        "$seriesId:${nextUp.nextSeason}:${nextUp.nextEpisode}"
                    } else null
                }
            } else {
                val movieHistory = activeProfileId.value?.let {
                    historyForPlayback(it, seriesId, meta.type, meta.addonBaseUrl)
                }
                if (movieHistory?.watched == true) null else movieHistory?.id
            }
            val isMovieWatched = if (meta.type != "series") {
                activeProfileId.value?.let {
                    historyForPlayback(it, seriesId, meta.type, meta.addonBaseUrl)?.watched
                } == true
            } else false
            if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                _state.value = _state.value.copy(
                    resumePlaybackId = resumePlaybackId,
                    resumeStateReady = true,
                    resumeIsNextEpisode = meta.type == "series" && resumePlaybackId != null &&
                        activeProfileId.value?.let {
                            historyForPlayback(it, resumePlaybackId, meta.type, meta.addonBaseUrl)
                        }
                            ?.let { !it.watched && it.position > 0 } != true,
                    lastPlayedEpisodeId = lastPlayedEpisodeId,
                    isMovieWatched = isMovieWatched,
                    autoPlayStream = null,
                    episodeProgressMap = episodeProgressMap
                )
            }
        }
    }

    fun refreshResumeState() {
        refreshResumeStateIfNeeded(_state.value.meta)
    }

    private suspend fun historyForPlayback(
        profileId: Int,
        playbackId: String,
        type: String,
        addonBaseUrl: String?
    ): WatchHistoryEntity? = dao.getHistoryItemForProfile(profileId, playbackId)
        ?: repository.canonicalPlayableIdentityKey(playbackId, type, addonBaseUrl)
            ?.let { dao.getHistoryItemByCanonicalIdentity(profileId, it) }

    private suspend fun nextUpForSeries(
        profileId: Int,
        seriesId: String,
        addonBaseUrl: String?
    ): SeriesNextUpEntity? = dao.getSeriesNextUpForProfile(profileId, seriesId)
        ?: repository.canonicalIdentityKey(seriesId, "series", addonBaseUrl)
            ?.let { dao.getSeriesNextUpByCanonicalIdentity(profileId, it) }

    private fun episodeHistoryIsWatched(playbackId: String, progress: Map<String, EpisodeProgress>): Boolean {
        val (season, episode) = playbackEpisodeCoordinates(playbackId) ?: return false
        return progress["S$season:E$episode"]?.watched == true
    }

    /**
     * Build a map of "S{season}:E{episode}" → EpisodeProgress from watch history.
     * Checks both with and without stream index suffix.
     */
    private suspend fun freshSeriesHistory(seriesId: String, addonBaseUrl: String? = _state.value.meta?.addonBaseUrl): List<WatchHistoryEntity> {
        val profileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId() ?: return emptyList()
        val matchingIdRows = dao.getSeriesEpisodeHistoryForProfile(profileId, "$seriesId:%")
        val canonicalRoot = repository.canonicalIdentityKey(seriesId, "series", addonBaseUrl)
        val canonicalRows = canonicalRoot?.let {
            dao.getSeriesEpisodeHistoryByCanonicalPrefix(profileId, "episode/$it/")
        }.orEmpty()
        val local = (matchingIdRows + canonicalRows).distinctBy { it.id }
        return accountSync.freshestPlaybackHistory(local, profileId)
            .filter { it.type == "series" && playbackEpisodeCoordinates(it.id) != null }
    }

    private suspend fun buildEpisodeProgressMap(
        seriesId: String,
        addonBaseUrl: String? = _state.value.meta?.addonBaseUrl
    ): Map<String, EpisodeProgress> {
        val historyItems = freshSeriesHistory(seriesId, addonBaseUrl)
        if (historyItems.isEmpty()) return emptyMap()

        val map = mutableMapOf<String, EpisodeProgress>()
        for (item in historyItems) {
            val (season, episode) = playbackEpisodeCoordinates(item.id) ?: continue
            val key = "S${season}:E${episode}"

            // Watched wins across stream variants; otherwise retain the highest progress.
            val existing = map[key]
            if (existing == null || (!existing.watched && item.watched) ||
                (existing.watched == item.watched && item.progress() > existing.progress)) {
                map[key] = EpisodeProgress(
                    progress = item.progress(),
                    watched = item.watched
                )
            }
        }
        return map
    }

    /**
     * Compute and store the next unwatched episode for a series.
     * Called when an episode is watched (auto or manual) and when details load.
     */
    suspend fun computeAndStoreNextUp(
        seriesId: String,
        title: String,
        poster: String?,
        videos: List<MetaVideo>?
    ): SeriesNextUpEntity? {
        if (videos.isNullOrEmpty()) return null

        // Get all watched episodes for this series
        val addonBaseUrl = _state.value.meta?.addonBaseUrl
        val progressMap = buildEpisodeProgressMap(seriesId, addonBaseUrl)
        val profileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId() ?: return null
        val existing = nextUpForSeries(profileId, seriesId, addonBaseUrl)

        // If no episodes have been watched, remove the next-up entry entirely
        val hasAnyWatched = progressMap.values.any { it.watched }
        if (!hasAnyWatched) {
            if (existing != null) {
                userStateCommands.saveSeriesNextUp(profileId, seriesId, null)
            }
            return null
        }

        // Sort episodes by season then episode number
        val sortedEpisodes = normalizeEpisodeList(videos)

        if (sortedEpisodes.isEmpty()) return null

        // Find the first unwatched episode
        val nextEpisode = sortedEpisodes.firstOrNull { ep ->
            val key = "S${ep.season}:E${ep.episode}"
            progressMap[key]?.watched != true
        }

        if (nextEpisode != null) {
            val epTitle = nextEpisode.title.takeIf { it.isNotBlank() && it != "Episode" }
            // Only update timestamp if the next episode actually changed
            val unchanged = existing != null &&
                !existing.isComplete &&
                existing.nextSeason == nextEpisode.season &&
                existing.nextEpisode == nextEpisode.episode
            // Badge: set when show was complete and now has a new episode
            // Keep if unchanged (user hasn't watched the new ep yet)
            // Clear once user watches the episode (next computeAndStoreNextUp will have a different next ep)
            val revived = existing?.isComplete == true
            val badgeState = when {
                revived -> true
                unchanged -> existing?.isNewEpisode ?: false
                else -> false
            }
            val nextUp = SeriesNextUpEntity(
                    seriesId = seriesId,
                    title = title,
                    poster = poster ?: existing?.poster,
                    nextSeason = nextEpisode.season,
                    nextEpisode = nextEpisode.episode,
                    nextEpisodeTitle = epTitle,
                    nextReleased = nextEpisode.released?.take(10),
                    isComplete = false,
                    isNewEpisode = badgeState,
                    updatedAt = if (unchanged) existing.updatedAt else System.currentTimeMillis()
                )
            val scoped = nextUp.copy(profileId = profileId)
            if (scoped != existing) userStateCommands.saveSeriesNextUp(profileId, seriesId, scoped)
            return scoped
        } else {
            // All local episodes watched — mark as complete locally.
            // If Trakt knows about a future episode, syncSeriesNextUp will correct this.
            val alreadyComplete = existing?.isComplete == true
            val nextUp = SeriesNextUpEntity(
                    seriesId = seriesId,
                    title = title,
                    poster = poster ?: existing?.poster,
                    nextSeason = 0,
                    nextEpisode = 0,
                    nextEpisodeTitle = null,
                    nextReleased = null,
                    isComplete = true,
                    updatedAt = if (alreadyComplete) existing.updatedAt else System.currentTimeMillis()
                )
            val scoped = nextUp.copy(profileId = profileId)
            if (scoped != existing) userStateCommands.saveSeriesNextUp(profileId, seriesId, scoped)
            return scoped
        }
    }

    private fun loadTmdbEnrichment(type: String, videoId: String, contentKey: String) {
        tmdbEnrichmentJob?.cancel()
        tmdbEnrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_state.value.contentKey != contentKey) return@launch
                // Check if TMDB is enabled for the active profile
                val profileId = profileConfigurationManager.getLastActiveProfileId()
                val profile = profileId?.let { dao.getProfileById(it) }
                if (profile?.tmdbEnabled != true) {
                    updateForContent(contentKey) { it.copy(tmdbEnabled = false, tmdbLoading = false) }
                    return@launch
                }

                updateForContent(contentKey) { it.copy(tmdbEnabled = true, tmdbLoading = true) }
                if (_state.value.contentKey != contentKey) return@launch

                val language = profile.tmdbLanguage.ifBlank { null } ?: "en"
                val mediaType = tmdbService.normalizeMediaType(type)

                // Resolve TMDB ID — if unresolvable (e.g. Kitsu IDs), stop loading and show addon data
                val tmdbId = tmdbService.ensureTmdbId(videoId, mediaType)
                if (tmdbId == null) {
                    updateForContent(contentKey) { it.copy(tmdbLoading = false) }
                    return@launch
                }
                if (_state.value.contentKey != contentKey) return@launch

                // Fetch enrichment, recommendations, and videos in parallel
                val enrichmentDeferred = async { tmdbMetadataService.fetchEnrichment(tmdbId, mediaType, language) }
                val recommendationsDeferred = async { tmdbMetadataService.fetchRecommendations(tmdbId, mediaType, language) }
                val trailerDeferred = async { tmdbMetadataService.fetchBestTrailerKey(tmdbId, mediaType, language) }

                val enrichment = enrichmentDeferred.await()
                if (_state.value.contentKey != contentKey) return@launch

                // Publish the useful detail payload as soon as it is ready. A slow
                // recommendations/trailer request must not hold back title/cast data.
                val currentMeta = _state.value.takeIf { it.contentKey == contentKey }?.meta
                    ?: return@launch
                val enrichedMeta = enrichment?.let { mergeTmdbDetailsFallback(currentMeta, it) } ?: currentMeta
                updateForContent(contentKey) {
                    it.copy(
                        contentProjection = enrichedMeta.toCanonicalProjection(),
                        providerMetadata = enrichedMeta,
                        tmdbEnrichment = enrichment
                    )
                }
                if (_state.value.contentKey != contentKey) return@launch

                val collectionDeferred = async {
                    enrichment?.collectionId?.let { tmdbMetadataService.fetchCollection(it, language) }.orEmpty()
                }
                val episodeEnrichmentDeferred = async {
                    if (mediaType != "tv") return@async emptyMap()
                    val seasons = enrichedMeta.videos
                        ?.filter { it.season > 0 }
                        ?.map { it.season }
                        ?.distinct().orEmpty()
                    if (seasons.isEmpty()) emptyMap()
                    else tmdbMetadataService.fetchEpisodeEnrichment(tmdbId, seasons, language)
                        .mapKeys { (key, _) -> "S${key.first}:E${key.second}" }
                }

                val recommendations = recommendationsDeferred.await()
                val trailer = trailerDeferred.await()
                val collection = collectionDeferred.await()
                val episodeEnrichmentMap = episodeEnrichmentDeferred.await()
                if (_state.value.contentKey != contentKey) return@launch

                updateForContent(contentKey) {
                    it.copy(
                        tmdbLoading = false,
                        tmdbRecommendations = recommendations,
                        trailer = it.trailer ?: trailer,
                        tmdbCollection = collection,
                        tmdbCollectionName = enrichment?.collectionName,
                        episodeEnrichmentMap = episodeEnrichmentMap
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DetailsViewModel", "TMDB enrichment failed: ${e.message}")
                updateForContent(contentKey) { it.copy(tmdbLoading = false) }
            }
        }
    }

    private fun updateForContent(contentKey: String, transform: (DetailsState) -> DetailsState) {
        _state.update { current -> if (current.contentKey == contentKey) transform(current) else current }
    }

    private var trailerWarmupJob: Job? = null

    private fun warmTrailer(trailer: TmdbVideoInfo?) {
        if (trailer == null) return
        trailerWarmupJob?.cancel()
        trailerWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            // Resolve while the user reads details, never create a decoder or
            // start playback. Manual and automatic previews share this cache.
            com.saab.tv.data.trailer.YouTubeExtractor().extractPlaybackSource(trailer.key)
        }
    }

    private fun loadCinemetaTrailer(type: String, videoId: String, contentKey: String) {
        if (!videoId.startsWith("tt")) return

        cinemetaTrailerJob = viewModelScope.launch(Dispatchers.IO) {
            val canonicalType = if (type.equals("series", ignoreCase = true) ||
                type.equals("tv", ignoreCase = true)) "series" else "movie"
            val meta = runCatching {
                repository.getMetaDetails(
                    "https://v3-cinemeta.strem.io/meta/$canonicalType/$videoId.json"
                )
            }.getOrNull()
            val trailer = meta?.bestAvailableTrailer() ?: return@launch

            if (_state.value.contentKey == contentKey) {
                updateForContent(contentKey) { it.copy(trailer = trailer) }
                if (_state.value.contentKey == contentKey) warmTrailer(trailer)
            }
        }
    }

    private fun MetaItem.bestAvailableTrailer(): TmdbVideoInfo? {
        val (key, trailerName) = com.saab.tv.data.trailer.TrailerPolicy.metadataTrailer(this)
            ?: return null

        return TmdbVideoInfo(
            name = trailerName,
            key = key,
            type = "Trailer",
            thumbnail = ""
        )
    }

    // ── Mark episode watched/unwatched ──

    fun toggleMovieWatched() {
        val meta = _state.value.meta ?: return
        if (meta.type == "series") return
        val itemId = _state.value.resolvedId ?: meta.id
        val isCurrentlyWatched = _state.value.isMovieWatched

        viewModelScope.launch(Dispatchers.IO) {
            if (isCurrentlyWatched) {
                val profileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId()
                    ?: return@launch
                val existing = profileId?.let { dao.getHistoryItemForProfile(it, itemId) }
                userStateCommands.unmarkWatched(
                    profileId, itemId, "movie",
                    listOfNotNull(existing?.copy(watched = false, lastWatched = System.currentTimeMillis()))
                )
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushMovieUnwatched(itemId)
            } else {
                val profileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId()
                    ?: return@launch
                val entry = WatchHistoryEntity(
                        profileId = profileId,
                        id = itemId,
                        title = meta.name,
                        poster = meta.poster,
                        position = 0L,
                        duration = 0L,
                        lastWatched = System.currentTimeMillis(),
                        type = "movie",
                        watched = true,
                        scrobbled = true
                    )
                userStateCommands.markWatched(profileId, itemId, "movie", listOf(entry),
                    addonInstallationId = meta.addonBaseUrl?.let(::addonInstallationKey))
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushMovieWatched(itemId)
            }
            activeProfileId.value?.let { profileId ->
                seekThumbnailCache.clearContent(profileId, itemId)
            }
            _state.value = _state.value.copy(
                isMovieWatched = !isCurrentlyWatched,
                resumePlaybackId = null
            )
        }
    }

    fun toggleEpisodeWatched(episode: MetaVideo) {
        val meta = _state.value.meta ?: return
        val streamId = _state.value.resolvedId ?: meta.id
        val key = "S${episode.season}:E${episode.episode}"
        val currentProgress = _state.value.episodeProgressMap[key]
        val isCurrentlyWatched = currentProgress?.watched ?: false

        viewModelScope.launch(Dispatchers.IO) {
            val playbackId = "$streamId:${episode.season}:${episode.episode}"
            val profileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId()
                ?: return@launch
            var syncHistory = emptyList<WatchHistoryEntity>()
            if (isCurrentlyWatched) {
                // Preserve an explicit false state so a delayed watch event cannot revive it.
                val previous = dao.getHistoryItemForProfile(profileId, playbackId)
                val variants = dao.getSeriesEpisodeHistoryForProfile(profileId, "$playbackId:%")
                val changed = (listOfNotNull(previous) + variants).distinctBy { it.id }
                    .map { it.copy(watched = false, lastWatched = System.currentTimeMillis()) }
                syncHistory = changed.ifEmpty { listOf(WatchHistoryEntity(
                    profileId = profileId,
                    id = playbackId,
                    title = episode.title.takeIf { it.isNotBlank() && it != "Episode" }
                        ?: "S${episode.season}:E${episode.episode} - ${meta.name}",
                    poster = meta.poster,
                    position = 0L,
                    duration = 0L,
                    lastWatched = System.currentTimeMillis(),
                    type = "series",
                    watched = false,
                    scrobbled = false
                )) }
                userStateCommands.setEpisodeWatched(profileId, playbackId, false, syncHistory)
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushEpisodeUnwatched(streamId, episode.season, episode.episode)
            } else {
                // Mark as watched: create a watched history entry
                val entry = WatchHistoryEntity(
                        profileId = profileId,
                        id = playbackId,
                        title = episode.title.takeIf { it.isNotBlank() && it != "Episode" }
                            ?: "S${episode.season}:E${episode.episode} - ${meta.name}",
                        poster = meta.poster,
                        position = 0L,
                        duration = 0L,
                        lastWatched = System.currentTimeMillis(),
                        type = "series",
                        watched = true,
                        scrobbled = true
                    )
                syncHistory = listOf(entry)
                userStateCommands.setEpisodeWatched(profileId, playbackId, true, syncHistory)
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushEpisodeWatched(streamId, episode.season, episode.episode)
            }
            activeProfileId.value?.let { profileId ->
                seekThumbnailCache.clearContentPrefix(profileId, playbackId)
            }

            // Refresh the progress map and next-up entry
            val updatedMap = buildEpisodeProgressMap(streamId)
            _state.value = _state.value.copy(episodeProgressMap = updatedMap)
            val nextUp = computeAndStoreNextUp(streamId, meta.name, meta.poster, meta.videos)
            if (syncHistory.isNotEmpty()) {
                userStateCommands.updateEpisodeSyncState(
                    profileId = profileId,
                    seriesId = streamId,
                    playbackId = playbackId,
                    watched = !isCurrentlyWatched,
                    history = syncHistory,
                    nextUp = nextUp,
                    clearNextUp = nextUp == null && updatedMap.values.none { it.watched }
                )
            }
        }
    }

    // 1. Open Episodes (Series)
    fun openEpisodes() {
        streamRequestGeneration++
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        val videos = _state.value.meta?.videos
            .orEmpty()
            .let(::normalizeEpisodeList)
        _state.value = _state.value.copy(
            autoPlayStream = null,
            addonSubtitles = emptyList(),
            availableStreams = emptyList(),
            sidebarState = SidebarState.Episodes(videos)
        )
    }

    private fun prefetchStreams(type: String, id: String) {
        val key = "$type:$id"
        if (prefetchedStreamKey == key && (prefetchedStreams != null || prefetchStreamsJob?.isActive == true)) return
        prefetchStreamsJob?.cancel()
        prefetchedStreamKey = key
        prefetchedStreams = null
        prefetchedSubtitles = null
        prefetchSubtitlesJob?.cancel()
        prefetchSubtitlesJob = viewModelScope.launch {
            try {
                val subtitles = subtitleRepository.getSubtitles(type, id)
                if (prefetchedStreamKey == key) prefetchedSubtitles = subtitles
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { }
        }
        prefetchStreamsJob = viewModelScope.launch {
            try {
                val streams = repository.getStreams(type, id)
                if (prefetchedStreamKey == key) prefetchedStreams = streams
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Prefetch failed silently — loadStreams will fetch fresh
            }
        }
    }

    // 2. Open Sources (Movie OR Specific Episode)
    fun loadStreams(
        type: String,
        id: String,
        displayTitle: String,
        sourceSelectionId: String = id,
        fallbackStreamId: String? = null,
        forceSourcePicker: Boolean = false,
        autoSelectSource: Boolean = false,
        rememberSourceSelection: Boolean = true
    ) {
        loadStreamsJob?.cancel()
        val requestGeneration = ++streamRequestGeneration
        loadStreamsJob = viewModelScope.launch {
            // Show immediate loading feedback:
            // - Show sources sidebar when user needs to pick manually
            // - Centered spinner when auto-resolve is expected (auto-select or remembered source)
            val hasRemembered = rememberSourceSelection &&
                sourceSelectionStore.hasRememberedSelection(sourceSelectionId, type)
            val showSidebar = forceSourcePicker || (!autoSelectSource && !hasRemembered)

            _state.value = _state.value.copy(
                autoPlayStream = null,
                addonSubtitles = emptyList(),
                availableStreams = emptyList(),
                isLoadingStreams = true,
                canSelectPartialSources = false,
                sourceLanguagePreferences = emptyList(),
                sidebarState = if (showSidebar) {
                    SidebarState.Sources(displayTitle, null, showBestLanguageOptions = true)
                }
                               else SidebarState.Closed
            )

            try {
                // Snapshot the profile policy once for this request. Progressive rows and the
                // final list must use identical quality/format/language/seeder filters and order.
                val activeProfileId = profileConfigurationManager.getLastActiveProfileId()
                val profile = activeProfileId?.let { dao.getProfileById(it) }?.let(deviceDisplay::effective)
                val sourceLanguagePreferences = profile
                    ?.let(AudioLanguagePreferencePolicy::forProfile)
                    .orEmpty()
                val addonSortOrders = dao.getAllAddons().firstOrNull()
                    ?.associate { it.transportUrl to it.sortOrder } ?: emptyMap()
                _state.value = _state.value.copy(sourceLanguagePreferences = sourceLanguagePreferences)

                val rawStreams: List<Stream>
                val addonSubtitles: List<AddonSubtitle>
                val prefetchKey = "$type:$id"
                var streamOrigin = "fresh"

                if (showSidebar && prefetchedStreamKey == prefetchKey && prefetchedStreams == null &&
                    prefetchStreamsJob?.isActive == true) {
                    // A background warmup can continue until every provider times out. The
                    // foreground picker cancels that all-or-nothing path and starts progressive
                    // discovery instead of inheriting its slowest-provider barrier.
                    prefetchStreamsJob?.cancel()
                    prefetchStreamsJob = null
                }
                val completedPrefetch = prefetchedStreams.takeIf { prefetchedStreamKey == prefetchKey }
                if (completedPrefetch != null) {
                    streamOrigin = "prefetch"
                    rawStreams = completedPrefetch
                    addonSubtitles = prefetchedSubtitles ?: emptyList()
                } else {
                    rawStreams = if (showSidebar) {
                        repository.getStreamsProgressively(type, id) { partial ->
                            val current = _state.value
                            if (requestGeneration == streamRequestGeneration &&
                                current.sidebarState is SidebarState.Sources) {
                                val visiblePartial = filterAndRankStreams(
                                    partial, type, profile, addonSortOrders, sourceLanguagePreferences
                                )
                                _state.value = current.copy(
                                    availableStreams = visiblePartial,
                                    canSelectPartialSources = visiblePartial.any(::isPlayableSource),
                                    sourceLanguagePreferences = sourceLanguagePreferences,
                                    sidebarState = SidebarState.Sources(
                                        displayTitle,
                                        visiblePartial,
                                        showBestLanguageOptions = true
                                    )
                                )
                            }
                        }
                    } else repository.getStreams(type, id)
                    // The player resolves release-matched subtitles after the first frame.
                    addonSubtitles = emptyList()
                }

                val episodeStreams = if (rawStreams.isEmpty() && !fallbackStreamId.isNullOrBlank() && fallbackStreamId != id) {
                    streamOrigin = "fallback"
                    if (showSidebar) {
                        repository.getStreamsProgressively(type, fallbackStreamId) { partial ->
                            val current = _state.value
                            if (requestGeneration == streamRequestGeneration &&
                                current.sidebarState is SidebarState.Sources) {
                                val visiblePartial = filterAndRankStreams(
                                    partial, type, profile, addonSortOrders, sourceLanguagePreferences
                                )
                                _state.value = current.copy(
                                    availableStreams = visiblePartial,
                                    canSelectPartialSources = visiblePartial.any(::isPlayableSource),
                                    sourceLanguagePreferences = sourceLanguagePreferences,
                                    sidebarState = SidebarState.Sources(
                                        displayTitle,
                                        visiblePartial,
                                        showBestLanguageOptions = true
                                    )
                                )
                            }
                        }
                    } else repository.getStreams(type, fallbackStreamId)
                } else rawStreams

                val streams = withContext(Dispatchers.Default) {
                    filterAndRankStreams(episodeStreams, type, profile, addonSortOrders, sourceLanguagePreferences)
                }
                if (requestGeneration != streamRequestGeneration) return@launch
                com.saab.tv.AppDiagnostics.torBoxEvent("Details Source Picker",
                    "origin=$streamOrigin ${com.saab.tv.data.stream.TorBoxDiagnosticSummary.sources(streams)}")

                val preferredStream = if (forceSourcePicker || !rememberSourceSelection) {
                    null
                } else {
                    sourceSelectionStore.findPreferredStream(sourceSelectionId, type, streams)
                }

                if (preferredStream != null) {
                    _state.value = _state.value.copy(
                        isLoadingStreams = false,
                        canSelectPartialSources = false,
                        sidebarState = SidebarState.Closed,
                        autoPlayStream = preferredStream,
                        addonSubtitles = addonSubtitles,
                        availableStreams = streams,
                        sourceLanguagePreferences = sourceLanguagePreferences
                    )
                    return@launch
                }

                // Auto-select first playable source when enabled
                if (autoSelectSource && !forceSourcePicker) {
                    val firstPlayable = streams.firstOrNull {
                        !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank()
                    }
                    if (firstPlayable != null) {
                        _state.value = _state.value.copy(
                            isLoadingStreams = false,
                            canSelectPartialSources = false,
                            sidebarState = SidebarState.Closed,
                            autoPlayStream = firstPlayable,
                            addonSubtitles = addonSubtitles,
                            availableStreams = streams,
                            sourceLanguagePreferences = sourceLanguagePreferences
                        )
                        return@launch
                    }
                }

                // Update sidebar with results
                _state.value = _state.value.copy(
                    isLoadingStreams = false,
                    canSelectPartialSources = false,
                    autoPlayStream = null,
                    addonSubtitles = addonSubtitles,
                    availableStreams = streams,
                    sourceLanguagePreferences = sourceLanguagePreferences,
                    sidebarState = SidebarState.Sources(
                        displayTitle,
                        streams,
                        showBestLanguageOptions = true
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (requestGeneration != streamRequestGeneration) return@launch
                _state.value = _state.value.copy(
                    isLoadingStreams = false,
                    canSelectPartialSources = false,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList(),
                    sidebarState = SidebarState.Sources(
                        displayTitle,
                        emptyList(),
                        showBestLanguageOptions = true
                    )
                )
            }
        }
    }

    private fun filterAndRankStreams(
        streams: List<Stream>,
        type: String,
        profile: com.saab.tv.data.model.ProfileEntity?,
        addonSortOrders: Map<String, Int>,
        sourceLanguagePreferences: List<String>
    ): List<Stream> {
        val filtered = if (profile?.sourceSortingEnabled != false) {
            streamSortingService.sortAndFilter(
                streams = streams,
                enabledQualities = StreamSortingService.parseEnabledQualities(
                    profile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown"
                ),
                excludePhrases = StreamSortingService.parseExcludePhrases(profile?.sourceExcludePhrases ?: ""),
                addonSortOrders = addonSortOrders,
                sortBy = profile?.sourceSortPrimary ?: "quality",
                maxSizeGb = profile?.sourceMaxSizeGb ?: 0,
                excludedFormats = StreamSortingService.parseExcludedFormats(profile?.sourceExcludedFormats ?: ""),
                seasonPackTorrentsOnly = type == "series" && profile?.sourceSeasonPacksOnly == true,
                hideZeroSeeders = profile?.sourceHideZeroSeeders == true,
                preferredAudioLanguages = sourceLanguagePreferences
            )
        } else streams.filter { stream ->
            profile?.sourceHideZeroSeeders != true || stream.torBoxCached == true ||
                com.saab.tv.data.stream.StreamParser.parse(stream).seeds != 0
        }
        return StreamScoreCalculator.sortDescending(filtered, sourceLanguagePreferences)
    }

    private fun isPlayableSource(stream: Stream): Boolean =
        !stream.url.isNullOrBlank() || !stream.infoHash.isNullOrBlank()

    fun consumeAutoPlayStream() {
        if (_state.value.autoPlayStream == null) return
        _state.value = _state.value.copy(autoPlayStream = null)
    }

    // --- Clear Progress (with confirmation dialog) ---

    fun confirmClearProgress() {
        val meta = _state.value.meta ?: return
        val progressId = _state.value.resolvedId ?: meta.id

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            // Collect items to clear
            val ownerProfileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId()
                ?: return@launch
            val historyItems = if (meta.type == "series") {
                (dao.getSeriesEpisodeHistoryForProfile(ownerProfileId, "$progressId:%") +
                    listOfNotNull(dao.getHistoryItemForProfile(ownerProfileId, progressId))).distinctBy { it.id }
            } else listOfNotNull(dao.getHistoryItemForProfile(ownerProfileId, progressId))

            // Delete local progress and emit the corresponding v3 tombstones atomically.
            userStateCommands.clearTitleProgress(ownerProfileId, progressId, meta.type, historyItems)

            if (meta.type == "series") {
                sourceSelectionStore.clearSelectionsForPrefix(progressId)
                playbackTrackSelectionStore.clearSelectionsForPrefix(progressId)
                activeProfileId.value?.let { profileId ->
                    seekThumbnailCache.clearContentPrefix(profileId, progressId)
                }
            } else {
                sourceSelectionStore.clearSelection(progressId)
                playbackTrackSelectionStore.clearSelection(progressId)
                activeProfileId.value?.let { profileId ->
                    seekThumbnailCache.clearContent(profileId, progressId)
                }
            }

            // Delete from Trakt (playback progress + watched history)
            for (item in historyItems) {
                if (item.scrobbled) {
                    traktSyncManager.deletePlaybackFromTrakt(item.id)
                }
            }
            // Remove watched episodes from Trakt history for series
            if (meta.type == "series") {
                val streamId = _state.value.resolvedId ?: meta.id
                val watchedEpisodes = historyItems.filter { it.watched }
                for (ep in watchedEpisodes) {
                    val parts = ep.id.split(":")
                    if (parts.size >= 3) {
                        val hasStreamIdx = parts.size >= 4 && parts.last().toIntOrNull() != null
                        val season = parts[parts.size - if (hasStreamIdx) 3 else 2].toIntOrNull() ?: continue
                        val episode = parts[parts.size - if (hasStreamIdx) 2 else 1].toIntOrNull() ?: continue
                        traktSyncManager.pushEpisodeUnwatched(streamId, season, episode)
                    }
                }
            }

            profileConfigurationManager.saveActiveRuntimeState()

            // Refresh episode progress map
            val streamId = _state.value.resolvedId ?: meta.id
            val updatedMap = if (meta.type == "series") buildEpisodeProgressMap(streamId) else emptyMap()

            _state.value = _state.value.copy(
                resumePlaybackId = null,
                episodeProgressMap = updatedMap
            )
        }
    }

    // --- Watchlist toggle ---

    fun toggleWatchlist() {
        val meta = _state.value.meta ?: return
        val itemId = _state.value.resolvedId ?: meta.id
        val profileId = activeProfileId.value ?: profileConfigurationManager.getLastActiveProfileId() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val added = userStateCommands.toggleLibrary(profileId, WatchlistEntity(
                    profileId = profileId,
                    id = itemId,
                    type = meta.type,
                    title = meta.name,
                    poster = meta.poster,
                    addedAt = System.currentTimeMillis(),
                    background = meta.background,
                    logo = meta.logo,
                    sourceAddonBaseUrl = meta.addonBaseUrl
                ), meta.addonBaseUrl?.let(::addonInstallationKey))
            if (!added) {
                traktSyncManager.pushRemove(itemId, meta.type)
            } else {
                val entity = WatchlistEntity(
                    profileId = profileId,
                    id = itemId,
                    type = meta.type,
                    title = meta.name,
                    poster = meta.poster,
                    addedAt = System.currentTimeMillis(),
                    background = meta.background,
                    logo = meta.logo
                )
                traktSyncManager.pushAdd(entity)
            }
        }
    }

    // 3. Close Logic
    fun closeSidebar() {
        streamRequestGeneration++
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        _state.value = _state.value.copy(
            isLoadingStreams = false,
            canSelectPartialSources = false,
            autoPlayStream = null,
            availableStreams = emptyList(),
            sidebarState = SidebarState.Closed
        )
    }

    // 4. Back Button Logic (Drill Up)
    fun goBackInSidebar() {
        val currentState = _state.value.sidebarState

        // If viewing Sources for a Series, go back to Episode List
        if (currentState is SidebarState.Sources && _state.value.meta?.type == "series") {
            openEpisodes()
        } else {
            closeSidebar()
        }
    }
}
