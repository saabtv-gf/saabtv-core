package com.saab.tv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.saab.tv.ui.player.base.BasePlayerScaffold
import com.saab.tv.ui.player.base.NextEpisodeInfo
import com.saab.tv.ui.player.base.PlaybackSettings
import com.saab.tv.ui.player.base.ExoPlayerBackend
import com.saab.tv.ui.player.base.PlayerBackendFactory
import com.saab.tv.ui.player.base.PlayerBackendType
import com.saab.tv.ui.player.base.PlayerLoadRequest
import com.saab.tv.ui.player.base.PlayerSourceOption
import com.saab.tv.ui.player.base.PlayerSubtitleSource
import com.saab.tv.ui.player.base.SkipSegmentInfo
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.torrent.TorrentProgress
import com.saab.tv.data.trailer.TrailerPlaybackVariant
import com.saab.tv.data.cache.SeekThumbnailProgress
import com.saab.tv.data.cache.SeekThumbnailWorkerRequest
import com.saab.tv.data.cache.SeekThumbnailWorkerService
import com.saab.tv.data.cache.TorBoxStreamUrlPolicy
import com.saab.tv.data.cache.ThumbnailSourceSelector
import com.saab.tv.data.player.PlaybackDiagnostics

private const val THUMBNAIL_WORKER_RESTART_INTERVAL_MS = 1_000L
private const val THUMBNAIL_WORKER_INITIAL_DELAY_MS = 1_000L

data class PlayerSessionResult(
    val positionMs: Long,
    val durationMs: Long?,
    val isCompleted: Boolean,
    val selectedSourceUrl: String?,
    val selectedAudioTrackId: String?,
    val selectedSubtitleTrackId: String?,
    val subtitleSelectionWasManual: Boolean = false,
    val subtitleDelayMs: Long = 0L
)

@Composable
fun PlayerScreen(
    videoUrl: String,
    trailerAudioUrl: String? = null,
    trailerVariants: List<TrailerPlaybackVariant> = emptyList(),
    title: String,
    seriesTitle: String? = null,
    logoUrl: String? = null,
    poster: String,
    movieId: String,
    mediaType: String,
    onBack: (PlayerSessionResult) -> Unit,
    backendType: PlayerBackendType = PlayerBackendType.EXOPLAYER,
    sources: List<PlayerSourceOption> = emptyList(),
    initialSourceId: String? = null,
    onActiveSourceChanged: (PlayerSourceOption) -> Unit = {},
    subtitles: List<PlayerSubtitleSource> = emptyList(),
    preferredAudioTrackId: String? = null,
    preferredSubtitleTrackId: String? = null,
    initialSubtitleDelayMs: Long = 0L,
    playbackSettings: PlaybackSettings = PlaybackSettings(),
    skipSegmentInfo: SkipSegmentInfo? = null,
    nextEpisodeInfo: NextEpisodeInfo? = null,
    onAutoplayNextEpisode: ((currentSourceUrl: String?, positionMs: Long, durationMs: Long?) -> Unit)? = null,
    episodes: List<MetaVideo> = emptyList(),
    currentPlaybackId: String? = null,
    onEpisodeSelected: ((episode: MetaVideo, currentSourceUrl: String?, positionMs: Long, durationMs: Long?) -> Unit)? = null,
    episodeSwitchSources: List<PlayerSourceOption>? = null,
    isEpisodeSwitchLoading: Boolean = false,
    episodeSwitchTitle: String? = null,
    onEpisodeSwitchSourceSelected: ((sourceUrl: String) -> Unit)? = null,
    onEpisodeSwitchDismissed: (() -> Unit)? = null,
    onMagnetSourceSelected: ((magnetUrl: String, fileIdx: Int, fileName: String, onReady: (resolvedMagnetUrl: String, localUrl: String) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    torrentProgress: TorrentProgress? = null,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtime = remember(movieId, backendType, playbackSettings) {
        PlayerBackendFactory.create(context, backendType, playbackSettings)
    }
    val playbackController = runtime.playbackController
    val renderSurface = runtime.renderSurface
    DisposableEffect(playbackController, movieId, mediaType) {
        val backend = playbackController as? ExoPlayerBackend
        backend?.resolveStreamSubtitles = { source ->
            if (movieId.startsWith("trailer_")) emptyList()
            else viewModel.streamSubtitles(mediaType, movieId, source)
        }
        onDispose { backend?.resolveStreamSubtitles = null }
    }

    LaunchedEffect(playbackController, onMagnetSourceSelected) {
        (playbackController as? ExoPlayerBackend)?.onMagnetSourceSelected = onMagnetSourceSelected
    }

    // Pre-create ExoPlayer + OkHttpClient while torrent pieces are still downloading.
    // By the time the URL arrives, the player is ready — prepareSource() just calls prepare().
    LaunchedEffect(playbackController, videoUrl) {
        if (videoUrl.isBlank()) {
            (playbackController as? ExoPlayerBackend)?.warmup()
        }
    }
    val uiState by playbackController.uiState.collectAsStateWithLifecycle()
    val liveSources by playbackController.sourceOptions.collectAsStateWithLifecycle()
    val activeSource = liveSources.firstOrNull { it.id == uiState.currentSourceId }
    val latestSourceCallback by rememberUpdatedState(onActiveSourceChanged)
    LaunchedEffect(playbackController, uiState.currentSourceId, uiState.hasRenderedFirstFrame) {
        if (uiState.hasRenderedFirstFrame) activeSource?.let { latestSourceCallback(it) }
    }
    var thumbnailPriorityMs by remember(movieId) { mutableLongStateOf(0L) }
    val latestThumbnailPriority by rememberUpdatedState(thumbnailPriorityMs)
    val shouldKeepScreenOn = uiState.playWhenReady || uiState.isPlaying || uiState.isBuffering
    val isTrailer = movieId.startsWith("trailer_")
    val effectiveTrailerVariants = remember(
        isTrailer,
        videoUrl,
        trailerAudioUrl,
        trailerVariants
    ) {
        if (!isTrailer) {
            emptyList()
        } else {
            trailerVariants.ifEmpty {
                listOf(TrailerPlaybackVariant(videoUrl = videoUrl, audioUrl = trailerAudioUrl))
            }
        }
    }
    var trailerVariantIndex by remember(movieId, videoUrl) { mutableIntStateOf(0) }
    var canAdvanceTrailerVariant by remember(movieId, videoUrl) { mutableStateOf(true) }
    val activeTrailerVariant = effectiveTrailerVariants.getOrNull(trailerVariantIndex)
    val effectiveVideoUrl = activeTrailerVariant?.videoUrl ?: videoUrl
    val effectiveTrailerAudioUrl = activeTrailerVariant?.audioUrl ?: trailerAudioUrl
    val diagnosticsEnabled = remember { PlaybackDiagnostics.isEnabled(context) }
    val diagnosticsSessionId = remember(movieId, effectiveVideoUrl, diagnosticsEnabled) {
        if (diagnosticsEnabled) PlaybackDiagnostics.newSessionId() else ""
    }
    LaunchedEffect(diagnosticsSessionId, diagnosticsEnabled) {
        if (!diagnosticsEnabled) return@LaunchedEffect
        PlaybackDiagnostics.beginSession(
            context = context,
            sessionId = diagnosticsSessionId,
            title = title,
            player = backendType.name,
            mediaUrl = effectiveVideoUrl
        )
    }
    val seekThumbnailsEnabled = playbackSettings.seekThumbnailsEnabled &&
        playbackSettings.profileId > 0 && !isTrailer
    // Seeking and preview generation share one cadence by design.
    val seekThumbnailIntervalSeconds = playbackSettings.seekTimeIntervalSeconds
        .takeIf { it == 10 || it == 20 || it == 30 } ?: 10
    var seekThumbnailCacheProgress by remember(movieId, seekThumbnailIntervalSeconds) {
        mutableStateOf(SeekThumbnailProgress(0, 0, 0))
    }
    val seekThumbnailProvider: (suspend (Long) -> android.graphics.Bitmap?)? =
        if (seekThumbnailsEnabled) {
            { positionMs: Long ->
                viewModel.loadSeekThumbnail(
                    profileId = playbackSettings.profileId,
                    contentId = movieId,
                    positionMs = positionMs,
                    intervalSeconds = seekThumbnailIntervalSeconds
                )
            }
        } else null

    // Keep the worker-process preference synchronized with the profile database.
    // This also repairs stale state left by an older build after thumbnails were
    // switched off and subsequently enabled again.
    LaunchedEffect(playbackSettings.profileId, seekThumbnailsEnabled) {
        if (playbackSettings.profileId > 0) {
            SeekThumbnailWorkerService.setProfileEnabled(
                context,
                playbackSettings.profileId,
                seekThumbnailsEnabled
            )
        }
    }

    LaunchedEffect(
        movieId,
        seekThumbnailsEnabled,
        seekThumbnailIntervalSeconds,
        uiState.durationMs
    ) {
        if (!seekThumbnailsEnabled || uiState.durationMs <= 0L) {
            seekThumbnailCacheProgress = SeekThumbnailProgress(0, 0, 0)
            return@LaunchedEffect
        }
        var lastReportedFrames = -1
        while (isActive) {
            seekThumbnailCacheProgress = try {
                viewModel.seekThumbnailCacheProgress(
                    profileId = playbackSettings.profileId,
                    contentId = movieId,
                    durationMs = uiState.durationMs,
                    intervalSeconds = seekThumbnailIntervalSeconds
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                seekThumbnailCacheProgress
            }
            if (diagnosticsEnabled && seekThumbnailCacheProgress.cachedFrames != lastReportedFrames) {
                PlaybackDiagnostics.event(
                    context,
                    diagnosticsSessionId,
                    "Thumbnails",
                    "Cache Progress",
                    "cached=${seekThumbnailCacheProgress.cachedFrames}/" +
                        "${seekThumbnailCacheProgress.totalFrames} " +
                        "percent=${seekThumbnailCacheProgress.percent}%"
                )
                lastReportedFrames = seekThumbnailCacheProgress.cachedFrames
            }
            delay(if (seekThumbnailCacheProgress.percent >= 100) 5_000L else 2_000L)
        }
    }

    val thumbnailSourceSelection = remember(isTrailer, effectiveVideoUrl, sources) {
        if (isTrailer) null else ThumbnailSourceSelector.select(effectiveVideoUrl, sources)
    }
    val thumbnailMediaUrl = thumbnailSourceSelection?.source?.url ?: effectiveVideoUrl
    LaunchedEffect(thumbnailSourceSelection, diagnosticsSessionId, diagnosticsEnabled) {
        if (diagnosticsEnabled && thumbnailSourceSelection != null) {
            PlaybackDiagnostics.event(
                context,
                diagnosticsSessionId,
                "Thumbnails",
                "Fast Source Selected",
                thumbnailSourceSelection.reason
            )
        }
    }
    val thumbnailWorkerRequest = remember(
        movieId,
        thumbnailMediaUrl,
        uiState.durationMs,
        seekThumbnailIntervalSeconds,
        seekThumbnailsEnabled
    ) {
        if (
            seekThumbnailsEnabled && uiState.durationMs > 0L &&
            TorBoxStreamUrlPolicy.isRemoteHttpStream(thumbnailMediaUrl)
        ) {
            SeekThumbnailWorkerRequest(
                profileId = playbackSettings.profileId,
                contentId = movieId,
                mediaUrl = thumbnailMediaUrl,
                durationMs = uiState.durationMs,
                intervalSeconds = seekThumbnailIntervalSeconds,
                priorityPositionMs = uiState.positionMs,
                diagnosticsSessionId = diagnosticsSessionId
            )
        } else null
    }

    // The decoder lives in :thumbnail_worker at background priority. Once the first
    // playback frame is visible it remains active across ordinary pause, seek, and
    // rebuffer events; repeatedly pausing it discarded remote decoder momentum.
    LaunchedEffect(thumbnailWorkerRequest) {
        val request = thumbnailWorkerRequest ?: return@LaunchedEffect
        var workerRequested = false
        var lastStartRequestMs = 0L
        delay(THUMBNAIL_WORKER_INITIAL_DELAY_MS)
        try {
            while (isActive && seekThumbnailCacheProgress.percent < 100) {
                val state = playbackController.uiState.value
                val powerManager = context.getSystemService(PowerManager::class.java)
                val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                } else {
                    PowerManager.THERMAL_STATUS_NONE
                }
                val mustPause = !state.errorMessage.isNullOrBlank() ||
                    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                val canGenerate = state.hasRenderedFirstFrame && !mustPause
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (canGenerate && (
                        !workerRequested ||
                            nowMs - lastStartRequestMs >= THUMBNAIL_WORKER_RESTART_INTERVAL_MS
                        )
                ) {
                    runCatching { SeekThumbnailWorkerService.start(context,
                        request.copy(priorityPositionMs = latestThumbnailPriority)) }
                    workerRequested = true
                    lastStartRequestMs = nowMs
                } else if (mustPause && workerRequested) {
                    PlaybackDiagnostics.event(
                        context,
                        diagnosticsSessionId,
                        "Thumbnails",
                        "Worker Paused",
                        "playing=${state.isPlaying} ready=${state.isReady} " +
                            "buffering=${state.isBuffering} seeking=${state.isSeeking} " +
                            "stress=${state.playbackUnderStress} thermal=$thermalStatus"
                    )
                    runCatching { SeekThumbnailWorkerService.pause(context) }
                    workerRequested = false
                }
                delay(1_000L)
            }
        } finally {
            // ACTION_PAUSE now preserves the decoder, so always terminate the
            // session when this player/request leaves composition.
            runCatching { SeekThumbnailWorkerService.cancel(context, request) }
        }
    }

    DisposableEffect(hostView, shouldKeepScreenOn) {
        hostView.keepScreenOn = shouldKeepScreenOn
        onDispose {
            hostView.keepScreenOn = false
        }
    }

    // Trakt scrobble: track last known state for episode switch detection
    var lastScrobbleId by remember { mutableStateOf(movieId) }
    var lastScrobblePositionMs by remember { mutableStateOf(0L) }
    var lastScrobbleDurationMs by remember { mutableStateOf(0L) }
    var finalizedTransitionId by remember { mutableStateOf<String?>(null) }

    // Update last known state while playing
    LaunchedEffect(playbackController) {
        playbackController.uiState.collect { state ->
            if (state.durationMs > 0L) {
                lastScrobblePositionMs = state.positionMs
                lastScrobbleDurationMs = state.durationMs
            }
        }
    }

    // Stop previous episode when switching to a new one
    LaunchedEffect(movieId) {
        if (lastScrobbleId != movieId && lastScrobbleDurationMs > 0L &&
            finalizedTransitionId != lastScrobbleId
        ) {
            viewModel.scrobbleStop(lastScrobbleId, mediaType, lastScrobblePositionMs, lastScrobbleDurationMs)
        }
        if (finalizedTransitionId == lastScrobbleId) finalizedTransitionId = null
        lastScrobbleId = movieId
    }

    // Trakt scrobble: start when playing, pause when paused
    LaunchedEffect(movieId, uiState.isPlaying) {
        if (uiState.durationMs <= 0L) return@LaunchedEffect
        if (uiState.isPlaying) {
            viewModel.scrobbleStart(movieId, mediaType, uiState.positionMs, uiState.durationMs)
        } else if (uiState.isReady) {
            viewModel.saveProgress(movieId, mediaType, title, poster, uiState.positionMs, uiState.durationMs, syncBoundary = true)
            viewModel.scrobblePause(movieId, mediaType, uiState.positionMs, uiState.durationMs)
        }
    }

    DisposableEffect(playbackController, movieId, mediaType, title, poster) {
        onDispose {
            val state = playbackController.uiState.value
            PlaybackDiagnostics.event(
                context,
                diagnosticsSessionId,
                "Playback",
                "Session Closed",
                "position=${state.positionMs}ms duration=${state.durationMs}ms " +
                    PlaybackDiagnostics.memorySummary()
            )
            if (state.positionMs >= 5_000L) {
                viewModel.saveProgress(
                    id = movieId,
                    type = mediaType,
                    title = title,
                    poster = poster,
                    position = state.positionMs,
                    duration = state.durationMs.takeIf { it > 0L },
                    syncBoundary = true
                )
            }
            playbackController.release()
        }
    }

    DisposableEffect(lifecycleOwner, playbackController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                playbackController.pause()
                val state = playbackController.uiState.value
                val pos = state.positionMs.coerceAtLeast(0L)
                val dur = state.durationMs.takeIf { it > 0L }
                viewModel.saveProgress(
                    id = movieId,
                    type = mediaType,
                    title = title,
                    poster = poster,
                    position = pos,
                    duration = dur,
                    syncBoundary = true
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(playbackController) {
        while (isActive) {
            delay(5_000L)
            if (!isActive) break
            val state = playbackController.uiState.value
            if ((state.isPlaying || state.isBuffering || state.isReady) && state.positionMs > 0L) {
                viewModel.saveProgress(
                    id = movieId,
                    type = mediaType,
                    title = title,
                    poster = poster,
                    position = state.positionMs.coerceAtLeast(0L),
                    duration = state.durationMs.takeIf { it > 0L }
                )
            }
        }
    }

    LaunchedEffect(playbackController, diagnosticsSessionId, diagnosticsEnabled) {
        if (!diagnosticsEnabled) return@LaunchedEffect
        var previousState = ""
        var lastMetricsAt = 0L
        while (isActive) {
            val state = playbackController.uiState.value
            val stateLabel = buildString {
                append(if (state.isPlaying) "playing" else if (state.isReady) "paused" else "preparing")
                if (state.isBuffering) append("+buffering")
                if (state.isSeeking) append("+seeking")
                if (state.isEnded) append("+ended")
                if (!state.errorMessage.isNullOrBlank()) append("+error")
            }
            if (stateLabel != previousState) {
                PlaybackDiagnostics.event(
                    context,
                    diagnosticsSessionId,
                    "Playback",
                    "State Changed",
                    "$previousState -> $stateLabel position=${state.positionMs}ms " +
                        "buffered=${state.bufferedPositionMs}ms"
                )
                previousState = stateLabel
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastMetricsAt >= 5_000L) {
                PlaybackDiagnostics.event(
                    context,
                    diagnosticsSessionId,
                    "Performance",
                    "Sample",
                    "position=${state.positionMs}ms buffered=${state.bufferedPositionMs}ms " +
                        PlaybackDiagnostics.memorySummary()
                )
                lastMetricsAt = now
            }
            delay(1_000L)
        }
    }

    LaunchedEffect(movieId, effectiveVideoUrl, effectiveTrailerAudioUrl, backendType) {
        if (effectiveVideoUrl.isBlank()) return@LaunchedEffect // Wait for torrent stream URL
        val resumePosition = viewModel.getResumePosition(movieId)
        thumbnailPriorityMs = resumePosition
        playbackController.load(
            PlayerLoadRequest(
                mediaUrl = effectiveVideoUrl,
                title = title,
                initialSourceId = initialSourceId,
                startPositionMs = resumePosition,
                autoPlay = true,
                sources = sources,
                subtitles = subtitles,
                preferredAudioTrackId = preferredAudioTrackId,
                preferredSubtitleTrackId = preferredSubtitleTrackId,
                separateAudioUrl = effectiveTrailerAudioUrl,
                diagnosticsSessionId = diagnosticsSessionId
            )
        )
        playbackController.setSubtitleVerticalOffset(playbackSettings.subtitleOffset)
        playbackController.setSubtitleSize(playbackSettings.subtitleSize)
        playbackController.setSubtitleTextColor(playbackSettings.subtitleTextColor)
        playbackController.setSubtitleBackgroundColor(playbackSettings.subtitleBackgroundColor)
        if (initialSubtitleDelayMs != 0L) {
            playbackController.setSubtitleDelay(initialSubtitleDelayMs)
        }
    }

    // YouTube occasionally rejects one CDN/client URL with 403 even though the
    // trailer metadata is valid. After the backend's normal retry is exhausted,
    // transparently advance to the next high-quality client/codec variant.
    LaunchedEffect(uiState.errorMessage) {
        val hasPlaybackError = !uiState.errorMessage.isNullOrBlank()
        if (!hasPlaybackError) {
            canAdvanceTrailerVariant = true
        } else if (
            isTrailer &&
            canAdvanceTrailerVariant &&
            trailerVariantIndex + 1 < effectiveTrailerVariants.size
        ) {
            canAdvanceTrailerVariant = false
            trailerVariantIndex += 1
        }
    }

    val persistAndBack = {
        val position = uiState.positionMs.coerceAtLeast(0L)
        val duration = uiState.durationMs.takeIf { it > 0L }
        val watchedThreshold = playbackSettings.watchedThresholdPercent.coerceIn(50, 99) / 100.0
        val isCompleted = WatchProgressPolicy.evaluate(
            positionMs = position,
            reportedDurationMs = duration,
            existingDurationMs = null,
            watchedThreshold = watchedThreshold
        ).isCompleted

        // Trakt: pause keeps item in continue watching, stop marks as watched.
        // A playback error must not erase an otherwise valid final snapshot.
        if (duration != null && duration > 0L) {
            if (isCompleted) {
                viewModel.scrobbleStop(movieId, mediaType, position, duration)
            } else {
                viewModel.scrobblePause(movieId, mediaType, position, duration, force = true)
            }
        }

        if (isCompleted) {
            viewModel.markCompleted(movieId, mediaType, title, poster, position, duration)
        } else {
            viewModel.saveProgress(
                id = movieId,
                type = mediaType,
                title = title,
                poster = poster,
                position = position,
                duration = duration
            )
        }
        val selectedSourceUrl = sources.firstOrNull { it.id == uiState.currentSourceId }?.url
            ?: videoUrl
        onBack(
            PlayerSessionResult(
                positionMs = position,
                durationMs = duration,
                isCompleted = isCompleted,
                selectedSourceUrl = selectedSourceUrl,
                selectedAudioTrackId = uiState.selectedAudioTrackId,
                selectedSubtitleTrackId = uiState.selectedSubtitleTrackId,
                subtitleSelectionWasManual = uiState.subtitleSelectionWasManual,
                subtitleDelayMs = playbackController.persistableSubtitleDelayMs()
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val transitionToNextEpisode = { sourceUrl: String?, positionMs: Long, durationMs: Long? ->
            finalizedTransitionId = movieId
            val duration = durationMs?.takeIf { it > 0L }
            val isCompleted = WatchProgressPolicy.evaluate(
                positionMs = positionMs,
                reportedDurationMs = duration,
                existingDurationMs = null,
                watchedThreshold = playbackSettings.watchedThresholdPercent.coerceIn(50, 99) / 100.0
            ).isCompleted
            if (isCompleted) {
                viewModel.markCompleted(movieId, mediaType, title, poster, positionMs, duration)
            } else {
                viewModel.saveProgress(movieId, mediaType, title, poster, positionMs, duration)
            }
            duration?.let {
                if (isCompleted) viewModel.scrobbleStop(movieId, mediaType, positionMs, it)
                else viewModel.scrobblePause(movieId, mediaType, positionMs, it, force = true)
            }
            onAutoplayNextEpisode?.invoke(sourceUrl, positionMs, durationMs)
            Unit
        }
        val transitionToSelectedEpisode = { episode: MetaVideo, sourceUrl: String?, positionMs: Long, durationMs: Long? ->
            finalizedTransitionId = movieId
            viewModel.saveProgress(movieId, mediaType, title, poster, positionMs, durationMs)
            durationMs?.takeIf { it > 0L }?.let {
                viewModel.scrobblePause(movieId, mediaType, positionMs, it, force = true)
            }
            onEpisodeSelected?.invoke(episode, sourceUrl, positionMs, durationMs)
            Unit
        }
        BasePlayerScaffold(
            playbackController = playbackController,
            renderSurface = renderSurface,
            seekTimeIntervalSeconds = playbackSettings.seekTimeIntervalSeconds,
            seekThumbnailProvider = seekThumbnailProvider,
            seekThumbnailIntervalSeconds = seekThumbnailIntervalSeconds,
            seekThumbnailCachePercent = seekThumbnailCacheProgress.percent.takeIf { seekThumbnailsEnabled },
            seekThumbnailCachedFrames = seekThumbnailCacheProgress.cachedFrames.takeIf { seekThumbnailsEnabled },
            seekThumbnailTotalFrames = seekThumbnailCacheProgress.totalFrames.takeIf { seekThumbnailsEnabled },
            title = title,
            seriesTitle = seriesTitle,
            logoUrl = logoUrl,
            mediaType = mediaType,
            onBack = persistAndBack,
            skipSegmentInfo = skipSegmentInfo,
            nextEpisodeInfo = nextEpisodeInfo,
            onAutoplayNextEpisode = if (onAutoplayNextEpisode != null) transitionToNextEpisode else null,
            autoplayEnabled = playbackSettings.autoplayNextEpisode,
            autoSkipIntro = playbackSettings.autoSkipIntro,
            introSkipCountdownSeconds = playbackSettings.introSkipCountdownSeconds,
            outroSkipCountdownSeconds = playbackSettings.outroSkipCountdownSeconds,
            onSeekPreviewPosition = { position ->
                thumbnailPriorityMs = position
                thumbnailWorkerRequest?.let {
                    SeekThumbnailWorkerService.prioritize(context, it, position)
                }
            },
            onSourceChosen = { latestSourceCallback(it) },
            autoplayThresholdMode = playbackSettings.autoplayThresholdMode,
            autoplayThresholdPercent = playbackSettings.autoplayThresholdPercent,
            autoplayThresholdSeconds = playbackSettings.autoplayThresholdSeconds,
            episodes = episodes,
            currentPlaybackId = currentPlaybackId,
            onEpisodeSelected = if (onEpisodeSelected != null) transitionToSelectedEpisode else null,
            episodeSwitchSources = episodeSwitchSources,
            isEpisodeSwitchLoading = isEpisodeSwitchLoading,
            episodeSwitchTitle = episodeSwitchTitle,
            onEpisodeSwitchSourceSelected = onEpisodeSwitchSourceSelected,
            onEpisodeSwitchDismissed = onEpisodeSwitchDismissed,
            torrentProgress = torrentProgress,
            isTrailer = movieId.startsWith("trailer_")
        )
    }
}
