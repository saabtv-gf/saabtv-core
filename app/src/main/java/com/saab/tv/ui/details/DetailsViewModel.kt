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
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.repository.SubtitleRepository
import com.saab.tv.data.stream.StreamSortingService
import com.saab.tv.data.stream.StreamScoreCalculator
import com.saab.tv.data.tmdb.TmdbEnrichment
import com.saab.tv.data.tmdb.TmdbEpisodeEnrichment
import com.saab.tv.data.tmdb.TmdbMetaPreview
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.data.tmdb.TmdbVideoInfo
import com.saab.tv.domain.AddonSubtitle
import com.saab.tv.domain.episodeStreamId
import com.saab.tv.domain.normalizeEpisodeList
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
import javax.inject.Inject
import java.util.Locale

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
    private val accountSync: com.saab.tv.data.account.AccountSyncManager
) : ViewModel() {

    /** Per-episode watch progress for the episodes sidebar. */
    data class EpisodeProgress(
        val progress: Float,  // 0.0–1.0
        val watched: Boolean
    )

    data class DetailsState(
        val meta: MetaItem? = null,
        val resolvedId: String? = null, // IMDb ID resolved from tmdb: prefixes, used for stream/subtitle fetching
        val contentKey: String? = null, // Tracks which item this state belongs to
        val isLoading: Boolean = true,
        val isLoadingStreams: Boolean = false,
        val resumePlaybackId: String? = null,
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
        val tmdbRecommendations: List<TmdbMetaPreview> = emptyList(),
        val cinemetaRecommendations: List<MetaItem> = emptyList(),
        val cinemetaRecommendationsLoading: Boolean = false,
        val trailer: TmdbVideoInfo? = null,
        val tmdbCollection: List<TmdbMetaPreview> = emptyList(),
        val tmdbCollectionName: String? = null
    )

    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state
    private val activeProfileId = MutableStateFlow(profileConfigurationManager.getLastActiveProfileId())

    /** Reactive watchlist status — emits true/false as the current item's watchlist state changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isInWatchlist: StateFlow<Boolean> = combine(
        _state.map { it.resolvedId ?: it.meta?.id },
        activeProfileId
    ) { id, profileId -> profileId to id }
        .flatMapLatest { (profileId, id) ->
            if (profileId != null && id != null) dao.isInWatchlistFlow(profileId, id) else flowOf(false)
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private var loadDetailsJob: Job? = null
    private var loadStreamsJob: Job? = null
    private var tmdbEnrichmentJob: Job? = null
    private var cinemetaRecommendationsJob: Job? = null
    private var loadRequestVersion: Long = 0L
    private var loadedContentKey: String? = null
    private var resumeRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            dao.getWatchHistory().collect { refreshResumeStateIfNeeded(_state.value.meta) }
        }
    }

    // Prefetched streams cache
    private var prefetchStreamsJob: Job? = null
    private var prefetchedStreamKey: String? = null
    private var prefetchedStreams: List<Stream>? = null
    private var prefetchedSubtitles: List<AddonSubtitle>? = null


    fun loadDetails(type: String, id: String, addonBaseUrl: String? = null) {
        activeProfileId.value = profileConfigurationManager.getLastActiveProfileId()
        val requestKey = "$type:$id"

        // Keep current details when reopening the same item (e.g., returning from player).
        if (
            loadedContentKey == requestKey &&
            _state.value.meta != null &&
            !_state.value.isLoading
        ) {
            refreshResumeStateIfNeeded(_state.value.meta)
            if (_state.value.sidebarState !is SidebarState.Closed) {
                _state.value = _state.value.copy(sidebarState = SidebarState.Closed)
            }
            return
        }

        loadDetailsJob?.cancel()
        loadRequestVersion += 1
        val requestVersion = loadRequestVersion

        // Reset immediately so previous movie details never flash for a new item.
        _state.value = DetailsState(
            isLoading = true,
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

                val details = repository.resolveMetaDetails(type, resolvedId, addonBaseUrl)
                    ?: throw Exception("No meta found")
                if (requestVersion != loadRequestVersion) return@launch
                loadedContentKey = requestKey
                // Use resolved ID for streams — guarantees IMDb format for stream addons
                val streamFetchId = if (details.id.startsWith("tt")) details.id else resolvedId
                if (details.type == "series") computeAndStoreNextUp(streamFetchId, details.name, details.poster, details.videos)
                val episodeProgressMap = if (details.type == "series") buildEpisodeProgressMap(streamFetchId) else emptyMap()
                val lastPlayedEpisodeId = if (details.type == "series")
                    dao.getLatestSeriesEpisodeHistory("${streamFetchId}:%")?.id else null
                val resumePlaybackId = if (details.type == "series") {
                    val latest = dao.getLatestSeriesEpisodeHistory("${streamFetchId}:%")
                    if (latest != null && !latest.watched && !episodeHistoryIsWatched(streamFetchId, latest.id, episodeProgressMap)) {
                        latest.id // In-progress episode — resume it
                    } else {
                        // All episodes watched or no history — use next-up if aired
                        val nextUp = dao.getSeriesNextUp(streamFetchId)
                        val today = java.time.LocalDate.now().toString()
                        val hasAired = nextUp != null && !nextUp.isComplete &&
                            (nextUp.nextReleased == null || nextUp.nextReleased <= today)
                        if (hasAired && nextUp != null) {
                            "${streamFetchId}:${nextUp.nextSeason}:${nextUp.nextEpisode}"
                        } else null
                    }
                } else {
                    val movieHistory = dao.getHistoryItem(streamFetchId)
                    if (movieHistory?.watched == true) null else movieHistory?.id
                }
                val isMovieWatched = if (details.type != "series") {
                    dao.getHistoryItem(streamFetchId)?.watched == true
                } else false

                _state.value = _state.value.copy(
                    meta = details,
                    resolvedId = streamFetchId,
                    contentKey = requestKey,
                    isLoading = false,
                    resumePlaybackId = resumePlaybackId,
                    resumeIsNextEpisode = details.type == "series" && resumePlaybackId != null && dao.getHistoryItem(resumePlaybackId)?.let { !it.watched && it.position > 0 } != true,
                    lastPlayedEpisodeId = lastPlayedEpisodeId,
                    isMovieWatched = isMovieWatched,
                    episodeProgressMap = episodeProgressMap,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList(),
                    tmdbEnrichment = null,
                    tmdbRecommendations = emptyList(),
                    cinemetaRecommendations = emptyList(),
                    cinemetaRecommendationsLoading = true,
                    trailer = details.bestAvailableTrailer(),
                    tmdbCollection = emptyList(),
                    tmdbCollectionName = null,
                    tmdbEnabled = isTmdbEnabled,
                    tmdbLoading = isTmdbEnabled
                )
                // Update next-up entry when details load
                if (details.type == "series") {
                    computeAndStoreNextUp(streamFetchId, details.name, details.poster, details.videos)
                }
                // Fire TMDB enrichment in background (non-blocking)
                loadTmdbEnrichment(details.type, streamFetchId, requestKey)
                // Some catalog addons omit trailer fields. Cinemeta can supply them
                // independently, without requiring a TMDB API key.
                loadCinemetaTrailer(details.type, streamFetchId, requestKey)
                loadCinemetaRecommendations(details, streamFetchId, requestKey)

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
                    meta = null,
                    isLoading = false,
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
        resumeRefreshJob = viewModelScope.launch {
            val seriesId = _state.value.resolvedId ?: meta.id
            if (meta.type == "series") computeAndStoreNextUp(seriesId, meta.name, meta.poster, meta.videos)
            val episodeProgressMap = if (meta.type == "series") buildEpisodeProgressMap(seriesId) else emptyMap()
            val lastPlayedEpisodeId = if (meta.type == "series")
                dao.getLatestSeriesEpisodeHistory("$seriesId:%")?.id else null
            val resumePlaybackId = if (meta.type == "series") {
                val latest = dao.getLatestSeriesEpisodeHistory("$seriesId:%")
                if (latest != null && !latest.watched && !episodeHistoryIsWatched(seriesId, latest.id, episodeProgressMap)) {
                    latest.id
                } else {
                    val nextUp = dao.getSeriesNextUp(seriesId)
                    val today = java.time.LocalDate.now().toString()
                    val hasAired = nextUp != null && !nextUp.isComplete &&
                        (nextUp.nextReleased == null || nextUp.nextReleased <= today)
                    if (hasAired && nextUp != null) {
                        "$seriesId:${nextUp.nextSeason}:${nextUp.nextEpisode}"
                    } else null
                }
            } else {
                val movieHistory = dao.getHistoryItem(seriesId)
                if (movieHistory?.watched == true) null else movieHistory?.id
            }
            val isMovieWatched = if (meta.type != "series") {
                dao.getHistoryItem(seriesId)?.watched == true
            } else false
            if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                _state.value = _state.value.copy(
                    resumePlaybackId = resumePlaybackId,
                    resumeIsNextEpisode = meta.type == "series" && resumePlaybackId != null && dao.getHistoryItem(resumePlaybackId)?.let { !it.watched && it.position > 0 } != true,
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

    private fun episodeHistoryIsWatched(seriesId: String, playbackId: String, progress: Map<String, EpisodeProgress>): Boolean {
        val parts = playbackId.removePrefix("$seriesId:").split(":")
        if (parts.size < 2) return false
        return progress["S${parts[0]}:E${parts[1]}"]?.watched == true
    }

    /**
     * Build a map of "S{season}:E{episode}" → EpisodeProgress from watch history.
     * Checks both with and without stream index suffix.
     */
    private suspend fun buildEpisodeProgressMap(seriesId: String): Map<String, EpisodeProgress> {
        val historyItems = dao.getSeriesEpisodeHistory("$seriesId:%")
        if (historyItems.isEmpty()) return emptyMap()

        val map = mutableMapOf<String, EpisodeProgress>()
        for (item in historyItems) {
            // Strip the known series prefix first. This remains correct for IDs such as
            // "tmdb:123:1:2" where counting numeric components from the end is ambiguous.
            val episodeParts = item.id
                .removePrefix("$seriesId:")
                .split(":")
            if (episodeParts.size < 2) continue
            val season = episodeParts[0].toIntOrNull() ?: continue
            val episode = episodeParts[1].toIntOrNull() ?: continue
            if (season <= 0 || episode <= 0) continue
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
    ) {
        if (videos.isNullOrEmpty()) return

        // Get all watched episodes for this series
        val progressMap = buildEpisodeProgressMap(seriesId)

        // If no episodes have been watched, remove the next-up entry entirely
        val hasAnyWatched = progressMap.values.any { it.watched }
        if (!hasAnyWatched) {
            dao.deleteSeriesNextUp(seriesId)
            return
        }

        // Sort episodes by season then episode number
        val sortedEpisodes = normalizeEpisodeList(videos)

        if (sortedEpisodes.isEmpty()) return

        // Find the first unwatched episode
        val nextEpisode = sortedEpisodes.firstOrNull { ep ->
            val key = "S${ep.season}:E${ep.episode}"
            progressMap[key]?.watched != true
        }

        val existing = dao.getSeriesNextUp(seriesId)

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
            dao.upsertSeriesNextUp(
                SeriesNextUpEntity(
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
            )
        } else {
            // All local episodes watched — mark as complete locally.
            // If Trakt knows about a future episode, syncSeriesNextUp will correct this.
            val alreadyComplete = existing?.isComplete == true
            dao.upsertSeriesNextUp(
                SeriesNextUpEntity(
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
            )
        }
    }

    private fun loadTmdbEnrichment(type: String, videoId: String, contentKey: String) {
        tmdbEnrichmentJob?.cancel()
        tmdbEnrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Check if TMDB is enabled for the active profile
                val profileId = profileConfigurationManager.getLastActiveProfileId()
                val profile = profileId?.let { dao.getProfileById(it) }
                if (profile?.tmdbEnabled != true) {
                    _state.value = _state.value.copy(tmdbEnabled = false, tmdbLoading = false)
                    return@launch
                }

                _state.value = _state.value.copy(tmdbEnabled = true, tmdbLoading = true)

                val language = profile.tmdbLanguage.ifBlank { null } ?: "en"
                val mediaType = tmdbService.normalizeMediaType(type)

                // Resolve TMDB ID — if unresolvable (e.g. Kitsu IDs), stop loading and show addon data
                val tmdbId = tmdbService.ensureTmdbId(videoId, mediaType)
                if (tmdbId == null) {
                    _state.value = _state.value.copy(tmdbLoading = false)
                    return@launch
                }

                // Fetch enrichment, recommendations, and videos in parallel
                val enrichmentDeferred = async { tmdbMetadataService.fetchEnrichment(tmdbId, mediaType, language) }
                val recommendationsDeferred = async { tmdbMetadataService.fetchRecommendations(tmdbId, mediaType, language) }
                val trailerDeferred = async { tmdbMetadataService.fetchBestTrailerKey(tmdbId, mediaType, language) }

                val enrichment = enrichmentDeferred.await()
                val recommendations = recommendationsDeferred.await()
                val trailer = trailerDeferred.await()

                // Fetch collection if available (movies only)
                val collection = if (enrichment?.collectionId != null) {
                    tmdbMetadataService.fetchCollection(enrichment.collectionId, language)
                } else emptyList()

                // Only update if we're still showing the same content
                if (_state.value.contentKey != contentKey) return@launch

                // Apply enrichment — overlay TMDB data onto existing metadata where it adds value
                val currentMeta = _state.value.meta
                val enrichedMeta = if (currentMeta != null && enrichment != null) {
                    currentMeta.copy(
                        // Localized title
                        name = enrichment.localizedTitle ?: currentMeta.name,
                        // Localized description
                        description = enrichment.description ?: currentMeta.description,
                        // Better images
                        logo = enrichment.logo ?: currentMeta.logo,
                        background = enrichment.backdrop ?: currentMeta.background,
                        poster = enrichment.poster ?: currentMeta.poster,
                        // Localized genres
                        genres = enrichment.genres.ifEmpty { currentMeta.genres },
                        // Release info
                        releaseInfo = enrichment.releaseInfo ?: currentMeta.releaseInfo,
                        // Rating from TMDB
                        imdbRating = enrichment.rating?.let {
                            String.format(Locale.ROOT, "%.1f", it)
                        } ?: currentMeta.imdbRating,
                        // Runtime
                        runtime = enrichment.runtimeMinutes?.let { "${it}m" } ?: currentMeta.runtime
                    )
                } else currentMeta

                // Fetch per-episode enrichment for series (synopsis, runtime, thumbnails)
                val episodeEnrichmentMap = if (mediaType == "tv" && tmdbId != null) {
                    val seasons = enrichedMeta?.videos
                        ?.filter { it.season > 0 }
                        ?.map { it.season }
                        ?.distinct() ?: emptyList()
                    if (seasons.isNotEmpty()) {
                        val raw = tmdbMetadataService.fetchEpisodeEnrichment(tmdbId, seasons, language)
                        raw.mapKeys { (key, _) -> "S${key.first}:E${key.second}" }
                    } else emptyMap()
                } else emptyMap()

                _state.value = _state.value.copy(
                    meta = enrichedMeta,
                    tmdbLoading = false,
                    tmdbEnrichment = enrichment,
                    tmdbRecommendations = recommendations,
                    trailer = trailer ?: _state.value.trailer,
                    tmdbCollection = collection,
                    tmdbCollectionName = enrichment?.collectionName,
                    episodeEnrichmentMap = episodeEnrichmentMap
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DetailsViewModel", "TMDB enrichment failed: ${e.message}")
                _state.value = _state.value.copy(tmdbLoading = false)
            }
        }
    }

    private fun loadCinemetaTrailer(type: String, videoId: String, contentKey: String) {
        if (_state.value.trailer != null || !videoId.startsWith("tt")) return

        viewModelScope.launch(Dispatchers.IO) {
            val canonicalType = if (type.equals("series", ignoreCase = true) ||
                type.equals("tv", ignoreCase = true)) "series" else "movie"
            val meta = runCatching {
                repository.getMetaDetails(
                    "https://v3-cinemeta.strem.io/meta/$canonicalType/$videoId.json"
                )
            }.getOrNull()
            val trailer = meta?.bestAvailableTrailer() ?: return@launch

            if (_state.value.contentKey == contentKey && _state.value.trailer == null) {
                _state.value = _state.value.copy(trailer = trailer)
            }
        }
    }

    private fun loadCinemetaRecommendations(
        details: MetaItem,
        videoId: String,
        contentKey: String
    ) {
        cinemetaRecommendationsJob?.cancel()
        if (!videoId.startsWith("tt", ignoreCase = true)) {
            _state.value = _state.value.copy(cinemetaRecommendationsLoading = false)
            return
        }

        cinemetaRecommendationsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val canonicalType = if (details.type.equals("series", ignoreCase = true) ||
                    details.type.equals("tv", ignoreCase = true)) "series" else "movie"
                val cinemetaMeta = runCatching {
                    repository.getMetaDetails(
                        "https://v3-cinemeta.strem.io/meta/$canonicalType/$videoId.json"
                    )
                }.getOrNull()
                val genres = details.genres.orEmpty().ifEmpty { cinemetaMeta?.genres.orEmpty() }
                val recommendations = repository.fetchCinemetaRecommendations(
                    type = canonicalType,
                    currentId = videoId,
                    genres = genres
                )
                if (_state.value.contentKey == contentKey) {
                    _state.value = _state.value.copy(
                        cinemetaRecommendations = recommendations,
                        cinemetaRecommendationsLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DetailsViewModel", "Cinemeta recommendations failed: ${e.message}")
                if (_state.value.contentKey == contentKey) {
                    _state.value = _state.value.copy(cinemetaRecommendationsLoading = false)
                }
            }
        }
    }

    private fun MetaItem.bestAvailableTrailer(): TmdbVideoInfo? {
        val stream = trailerStreams.orEmpty().firstOrNull { trailer ->
            !trailer.ytId.isNullOrBlank() ||
                !trailer.externalUrl.isNullOrBlank() ||
                !trailer.url.isNullOrBlank()
        }
        val streamSource = stream?.ytId
            ?: stream?.externalUrl
            ?: stream?.url
        val legacy = trailers.orEmpty().firstOrNull { trailer ->
            trailer.type.equals("Trailer", ignoreCase = true) && !trailer.source.isNullOrBlank()
        } ?: trailers.orEmpty().firstOrNull { !it.source.isNullOrBlank() }
        val key = streamSource?.trim()?.takeIf { it.isNotEmpty() }
            ?: legacy?.source?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val trailerName = stream?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$name Trailer"

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
                dao.deleteHistoryItem(itemId)
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushMovieUnwatched(itemId)
            } else {
                dao.upsertHistory(
                    WatchHistoryEntity(
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
                )
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
            if (isCurrentlyWatched) {
                // Unmark: remove watched entry from history
                dao.deleteHistoryItem(playbackId)
                // Also try with stream index variants
                dao.getSeriesEpisodeHistory("$playbackId:%").forEach {
                    dao.deleteHistoryItem(it.id)
                }
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushEpisodeUnwatched(streamId, episode.season, episode.episode)
            } else {
                // Mark as watched: create a watched history entry
                dao.upsertHistory(
                    WatchHistoryEntity(
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
                )
                accountSync.historyChanged(urgent = true)
                traktSyncManager.pushEpisodeWatched(streamId, episode.season, episode.episode)
            }
            activeProfileId.value?.let { profileId ->
                seekThumbnailCache.clearContentPrefix(profileId, playbackId)
            }

            // Refresh the progress map and next-up entry
            val updatedMap = buildEpisodeProgressMap(streamId)
            _state.value = _state.value.copy(episodeProgressMap = updatedMap)
            computeAndStoreNextUp(streamId, meta.name, meta.poster, meta.videos)
        }
    }

    // 1. Open Episodes (Series)
    fun openEpisodes() {
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
        prefetchStreamsJob?.cancel()
        val key = "$type:$id"
        prefetchedStreamKey = key
        prefetchedStreams = null
        prefetchedSubtitles = null
        prefetchStreamsJob = viewModelScope.launch {
            try {
                val streamsDeferred = async { repository.getStreams(type, id) }
                val subtitlesDeferred = async { subtitleRepository.getSubtitles(type, id) }
                prefetchedStreams = streamsDeferred.await()
                prefetchedSubtitles = subtitlesDeferred.await()
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
        loadStreamsJob = viewModelScope.launch {
            // Show immediate loading feedback:
            // - Show sources sidebar when user needs to pick manually
            // - Centered spinner when auto-resolve is expected (auto-select or remembered source)
            val hasRemembered = rememberSourceSelection && sourceSelectionStore.hasRememberedSelection(sourceSelectionId)
            val showSidebar = forceSourcePicker || (!autoSelectSource && !hasRemembered)

            _state.value = _state.value.copy(
                autoPlayStream = null,
                addonSubtitles = emptyList(),
                availableStreams = emptyList(),
                isLoadingStreams = true,
                sidebarState = if (showSidebar) {
                    SidebarState.Sources(displayTitle, null, showBestLanguageOptions = true)
                }
                               else SidebarState.Closed
            )

            try {
                val rawStreams: List<Stream>
                val addonSubtitles: List<AddonSubtitle>
                val prefetchKey = "$type:$id"
                var streamOrigin = "fresh"

                if (prefetchedStreamKey == prefetchKey) {
                    prefetchStreamsJob?.join()
                    val completedPrefetch = prefetchedStreams
                    if (completedPrefetch != null) {
                        streamOrigin = "prefetch"
                        rawStreams = completedPrefetch
                        addonSubtitles = prefetchedSubtitles ?: emptyList()
                    } else {
                        val streamsDeferred = async { repository.getStreams(type, id) }
                        val subtitlesDeferred = async { subtitleRepository.getSubtitles(type, id) }
                        rawStreams = streamsDeferred.await()
                        addonSubtitles = subtitlesDeferred.await()
                    }
                } else {
                    val streamsDeferred = async { repository.getStreams(type, id) }
                    val subtitlesDeferred = async { subtitleRepository.getSubtitles(type, id) }
                    rawStreams = streamsDeferred.await()
                    addonSubtitles = subtitlesDeferred.await()
                }

                val episodeStreams = if (rawStreams.isEmpty() && !fallbackStreamId.isNullOrBlank() && fallbackStreamId != id) {
                    streamOrigin = "fallback"
                    repository.getStreams(type, fallbackStreamId)
                } else rawStreams

                // Read sorting preferences from the active profile
                val activeProfileId = profileConfigurationManager.getLastActiveProfileId()
                val profile = activeProfileId?.let { dao.getProfileById(it) }?.let(deviceDisplay::effective)
                val sourceLanguagePreferences = StreamSortingService.smartLanguagePreferences(
                    profile?.sourceLanguagePriority1,
                    profile?.sourceLanguagePriority2,
                    profile?.sourceLanguagePriority3
                )

                val filteredStreams = if (profile?.sourceSortingEnabled != false) {
                    val enabledQualities = StreamSortingService.parseEnabledQualities(profile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown")
                    val excludePhrases = StreamSortingService.parseExcludePhrases(profile?.sourceExcludePhrases ?: "")
                    val addonSortOrders = dao.getAllAddons().firstOrNull()
                        ?.associate { it.transportUrl to it.sortOrder } ?: emptyMap()
                    val excludedFormats = StreamSortingService.parseExcludedFormats(profile?.sourceExcludedFormats ?: "")
                    streamSortingService.sortAndFilter(
                        streams = episodeStreams,
                        enabledQualities = enabledQualities,
                        excludePhrases = excludePhrases,
                        addonSortOrders = addonSortOrders,
                        sortBy = profile?.sourceSortPrimary ?: "quality",
                        maxSizeGb = profile?.sourceMaxSizeGb ?: 0,
                        excludedFormats = excludedFormats,
                        seasonPackTorrentsOnly = type == "series" && profile?.sourceSeasonPacksOnly == true,
                        hideZeroSeeders = profile?.sourceHideZeroSeeders == true,
                        preferredAudioLanguages = sourceLanguagePreferences
                    )
                } else episodeStreams.filter { stream ->
                    !com.saab.tv.data.stream.TorBoxAvailabilityPolicy.remove(stream) &&
                        (profile?.sourceHideZeroSeeders != true || stream.torBoxCached == true ||
                            com.saab.tv.data.stream.StreamParser.parse(stream).seeds != 0)
                }

                // Selection and auto-play must use the same exact ranking shown
                // on source cards, independent of legacy primary-sort settings.
                val streams = StreamScoreCalculator.sortDescending(filteredStreams, sourceLanguagePreferences)
                com.saab.tv.AppDiagnostics.torBoxEvent("Details Source Picker",
                    "origin=$streamOrigin ${com.saab.tv.data.stream.TorBoxDiagnosticSummary.sources(streams)}")

                val preferredStream = if (forceSourcePicker || !rememberSourceSelection) {
                    null
                } else {
                    sourceSelectionStore.findPreferredStream(sourceSelectionId, streams)
                }

                if (preferredStream != null) {
                    _state.value = _state.value.copy(
                        isLoadingStreams = false,
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
                _state.value = _state.value.copy(
                    isLoadingStreams = false,
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
            val historyItems = if (meta.type == "series") {
                dao.getSeriesEpisodeHistory("$progressId:%")
            } else {
                listOfNotNull(dao.getHistoryItem(progressId))
            }

            // Delete from local DB
            if (meta.type == "series") {
                dao.deleteSeriesHistory("$progressId:%")
                dao.deleteSeriesNextUp(progressId)
                sourceSelectionStore.clearSelectionsForPrefix(progressId)
                playbackTrackSelectionStore.clearSelectionsForPrefix(progressId)
                activeProfileId.value?.let { profileId ->
                    seekThumbnailCache.clearContentPrefix(profileId, progressId)
                }
            } else {
                dao.deleteHistoryItem(progressId)
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
            if (dao.isInWatchlist(profileId, itemId)) {
                dao.removeFromWatchlist(profileId, itemId)
                traktSyncManager.pushRemove(itemId, meta.type)
            } else {
                val entity = WatchlistEntity(
                    profileId = profileId,
                    id = itemId,
                    type = meta.type,
                    title = meta.name,
                    poster = meta.poster,
                    addedAt = System.currentTimeMillis()
                )
                dao.addToWatchlist(entity)
                traktSyncManager.pushAdd(entity)
            }
        }
    }

    // 3. Close Logic
    fun closeSidebar() {
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        _state.value = _state.value.copy(
            isLoadingStreams = false,
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
