package com.saab.tv.ui.player.base

import android.app.Activity
import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.saab.tv.R
import com.saab.tv.data.player.PlaybackDiagnostics
import com.saab.tv.data.subtitle.SubtitleAutoSync
import com.saab.tv.data.cache.ThumbnailSourceSelector
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.assrender.AssExtractorsFactory
import io.github.assrender.AssHandler
import io.github.assrender.AssRenderersFactory
import io.github.assrender.SubtitleOverlayView
import java.util.Locale
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield


private const val SUBTITLE_OFF_ID = "#none"
private val BRACKET_CONTENT_REGEX = Regex("\\[[^\\]]*]")
private val NON_LETTER_DIGIT_REGEX = Regex("[^\\p{L}\\p{N}]")
private val WHITESPACE_REGEX = Regex("\\s+")
private const val AUDIO_SWITCH_RECOVERY_DELAY_MS = 1_500L
private const val AUDIO_SWITCH_RECOVERY_SEEK_BACK_MS = 500L
private const val IO_AUTO_RETRY_DELAY_MS = 450L
private const val AUDIO_AUTO_RETRY_DELAY_MS = 1_200L
private const val MAX_IO_AUTO_RETRIES_PER_SOURCE = 1
private const val MAX_AUDIO_AUTO_RETRIES_PER_SOURCE = 3
private const val MIN_SEEK_INDICATOR_MS = 250L
private const val SEEK_INDICATOR_POLL_MS = 80L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ExoPlayerBackend(
    private val appContext: Context,
    private val playbackSettings: PlaybackSettings = PlaybackSettings(),
    activity: Activity? = null
) : PlayerPlaybackController, PlayerRenderSurface {

    private val frameRateManager: FrameRateManager? =
        if (playbackSettings.frameRateMatching && activity != null) FrameRateManager(activity) else null

    /**
     * Called when the user selects a magnet source in the player.
     * The lambda receives (magnetUrl, fileIdx, fileName, onReady) where onReady should be
     * called with the localhost proxy URL once the torrent stream is ready.
     */
    var onMagnetSourceSelected: ((magnetUrl: String, fileIdx: Int, fileName: String, onReady: (resolvedMagnetUrl: String, localUrl: String) -> Unit, onError: (String) -> Unit) -> Unit)? = null

    override val backendType: PlayerBackendType = PlayerBackendType.EXOPLAYER

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main)

    private val _uiState = MutableStateFlow(PlayerUiState())
    override val uiState: StateFlow<PlayerUiState> = _uiState

    private val _sourceOptions = MutableStateFlow<List<PlayerSourceOption>>(emptyList())
    override val sourceOptions: StateFlow<List<PlayerSourceOption>> = _sourceOptions

    private val _audioTracks = MutableStateFlow<List<PlayerTrackOption>>(emptyList())
    override val audioTracks: StateFlow<List<PlayerTrackOption>> = _audioTracks

    private val _subtitleTracks = MutableStateFlow<List<PlayerTrackOption>>(emptyList())
    override val subtitleTracks: StateFlow<List<PlayerTrackOption>> = _subtitleTracks

    private val _playerReady = MutableStateFlow<ExoPlayer?>(null)

    private var released = false
    private var loadToken = 0L
    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null
    private var audioSwitchRecoveryJob: Job? = null
    private var seekFeedbackJob: Job? = null
    private var subtitleAutoSyncJob: Job? = null
    private var subtitleLookupJob: Job? = null
    private var autoSyncSubtitleId: String? = null
    private var autoSyncMediaUrl: String? = null
    private var subtitleDelayChangedManually = false
    private var autoAppliedSubtitleDelay = false
    private var decoderStressJob: Job? = null
    private var assHandler: AssHandler? = null
    private val jitterRecoveryPolicy = VideoJitterRecoveryPolicy()
    private val bufferRecoveryPolicy = PlaybackBufferRecoveryPolicy()
    private val reliabilityStateMachine = PlaybackReliabilityStateMachine()

    private fun getOrCreateAssHandler(): AssHandler {
        assHandler?.let { return it }
        val overlay = SubtitleOverlayView(appContext)
        val handler = AssHandler(appContext, overlay)
        handler.onAssTrackDetected = {
            // Hide ExoPlayer's subtitle view when ASS track is detected
            scope.launch(Dispatchers.Main) {
                playerView?.subtitleView?.visibility = android.view.View.GONE
            }
        }
        assHandler = handler
        return handler
    }

    private fun syncAssTrackWithExoPlayer(tracks: Tracks) {
        val handler = assHandler ?: return
        // Count ALL text tracks (not just ASS) to match AssTrackOutput numbering
        var textTrackIndex = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: ""
                val isAss = mime == "text/x-ssa" || mime == "text/x-ass" || mime == androidx.media3.common.MimeTypes.TEXT_SSA
                if (group.isTrackSelected(i)) {
                    if (isAss) {
                        handler.selectTrack(textTrackIndex)
                        playerView?.subtitleView?.visibility = android.view.View.GONE
                    } else {
                        handler.clearOverlay()
                        playerView?.subtitleView?.visibility = android.view.View.VISIBLE
                    }
                    return
                }
                textTrackIndex++
            }
        }
    }

    /** Find the text track index for a given TrackLocator by counting ALL text tracks */
    private fun findAssTrackIndex(tracks: Tracks, locator: TrackLocator): Int {
        var textIndex = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.mediaTrackGroup == locator.trackGroup && i == locator.trackIndex) {
                    val mime = group.getTrackFormat(i).sampleMimeType ?: ""
                    val isAss = mime == "text/x-ssa" || mime == "text/x-ass" || mime == MimeTypes.TEXT_SSA
                    return if (isAss) textIndex else -1
                }
                textIndex++
            }
        }
        return -1
    }

    private var loadRequest: PlayerLoadRequest? = null
    private var currentSourceId: String? = null
    private var pendingAudioTrackId: String? = null
    private var pendingSubtitleTrackId: String? = null
    private var hasAppliedAudioLanguagePref = false
    private var hasAppliedSubtitleLanguagePref = false
    private var manualSubtitleSelection = false
    var resolveStreamSubtitles: (suspend (PlayerSourceOption) -> List<PlayerSubtitleSource>)? = null

    private var externalSubtitleSources: Map<String, PlayerSubtitleSource> = emptyMap()
    private var okHttpClient: OkHttpClient? = null
    private var forcedSubtitleTrackId: String? = null
    private var sourcePreparedElapsedMs: Long = 0L
    private var diagnosticsSessionId: String = ""
    private var bufferingStartedElapsedMs: Long = 0L
    private val lastNetworkDiagnosticElapsedMs = AtomicLong(0L)
    private var ioAutoRetrySourceId: String? = null
    private var ioAutoRetryCountForCurrentSource: Int = 0
    private var hasRetriedCurrentSourceAfter416: Boolean = false
    private var pendingHintTrackRefresh: Boolean = false
    private var hintTrackRefreshJob: Job? = null
    private var pendingStartPositionMs: Long = 0L
    private var isTorrentStream: Boolean = false
    private var cachedIsTvDevice: Boolean? = null
    private val sharedExtractorsFactory by lazy { createExtractorsFactory() }
    private val subtitleFormatHintsByTrackId = mutableMapOf<String, String>()
    private val subtitleFormatHintsByLabelLanguage = mutableMapOf<String, String>()
    private val subtitleFormatHintsByLabel = mutableMapOf<String, String>()
    private var playerView: PlayerView? = null
    private var subtitleVerticalOffsetPercent: Int = 0
    private var subtitleSizePercent: Int = 100
    private val subtitleDelayUs = AtomicLong(0L)
    private var subtitleTextColor: Int = 0xFFFFFFFF.toInt()
    private var subtitleBackgroundColor: Int = 0x00000000
    private var lastCueGroup: androidx.media3.common.text.CueGroup? = null
    private companion object {
        val SHARED_PLAYBACK_CONNECTION_POOL = ConnectionPool(8, 5, TimeUnit.MINUTES)
        private const val DEFAULT_SUBTITLE_BOTTOM_PADDING_FRACTION = 0.08f
        private const val MIN_SUBTITLE_OFFSET_PERCENT = -20
        private const val MAX_SUBTITLE_OFFSET_PERCENT = 20
        private const val DEFAULT_SUBTITLE_BASE_FONT_SIZE_SP = 24f
        private const val MIN_SUBTITLE_SIZE_PERCENT = 50
        private const val MAX_SUBTITLE_SIZE_PERCENT = 200
        private const val MIN_SUBTITLE_DELAY_MS = -10_000L
        private const val MAX_SUBTITLE_DELAY_MS = 10_000L
    }

    private data class TrackLocator(
        val trackGroup: TrackGroup,
        val trackIndex: Int
    )

    private val audioTrackLocators = mutableMapOf<String, TrackLocator>()
    private val subtitleTrackLocators = mutableMapOf<String, TrackLocator>()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val now = SystemClock.elapsedRealtime()
            if (playbackState == Player.STATE_BUFFERING && bufferingStartedElapsedMs == 0L) {
                bufferingStartedElapsedMs = now
            } else if (playbackState == Player.STATE_READY && bufferingStartedElapsedMs > 0L) {
                diagnostic(
                    "Buffering Finished",
                    "duration=${(now - bufferingStartedElapsedMs).coerceAtLeast(0L)}ms"
                )
                bufferingStartedElapsedMs = 0L
            }
            val phase = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackReliabilityPhase.BUFFERING
                Player.STATE_READY -> PlaybackReliabilityPhase.READY
                Player.STATE_ENDED -> PlaybackReliabilityPhase.ENDED
                else -> reliabilityStateMachine.phase
            }
            reliabilityStateMachine.transition(phase)
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isReady = playbackState == Player.STATE_READY,
                    isEnded = playbackState == Player.STATE_ENDED,
                    reliabilityPhase = phase.name.lowercase(Locale.US)
                )
            }
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                _uiState.update { state -> state.copy(errorMessage = null) }
                ioAutoRetrySourceId = currentSourceId
                ioAutoRetryCountForCurrentSource = 0
            }
            if (playbackState == Player.STATE_READY) {
                val pendingSeekMs = pendingStartPositionMs
                if (pendingSeekMs > 0L) {
                    pendingStartPositionMs = 0L
                    exoPlayer?.let { p ->
                        p.setSeekParameters(
                            if (isTorrentStream) SeekParameters.NEXT_SYNC
                            else SeekParameters.CLOSEST_SYNC
                        )
                        p.seekTo(pendingSeekMs)
                    }
                }
                if (pendingHintTrackRefresh) {
                    pendingHintTrackRefresh = false
                    scheduleHintTrackRefresh()
                }
            }
            updateProgressLoopState()
            updateProgressState()
            diagnostic(
                "Player State",
                when (playbackState) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready startup=${(SystemClock.elapsedRealtime() - sourcePreparedElapsedMs).coerceAtLeast(0L)}ms"
                    Player.STATE_ENDED -> "ended"
                    else -> "unknown=$playbackState"
                }
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            updateProgressLoopState()
            updateProgressState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _uiState.update { it.copy(playWhenReady = playWhenReady) }
            updateProgressLoopState()
            updateProgressState()
        }

        override fun onRenderedFirstFrame() {
            _uiState.update { it.copy(hasRenderedFirstFrame = true) }
            diagnostic(
                "First Frame Rendered",
                "startup=${(SystemClock.elapsedRealtime() - sourcePreparedElapsedMs).coerceAtLeast(0L)}ms"
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (loadRequest?.trailerRequestHeaders?.isNotEmpty() == true) {
                val selected = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }
                com.saab.tv.AppDiagnostics.event("Trailer", "Decoded Quality",
                    selected.joinToString { "height=${it.height} codec=${it.codecs}" })
            }
            refreshTrackOptions(tracks)
            applyPendingTrackSelections()
            if (playbackSettings.assRendererEnabled) syncAssTrackWithExoPlayer(tracks)
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            if (_uiState.value.selectedSubtitleTrackId == SUBTITLE_OFF_ID) {
                lastCueGroup = null
                playerView?.subtitleView?.setCues(emptyList())
                return
            }
            lastCueGroup = cueGroup
            applyTransformedCues()
        }

        override fun onPlayerError(error: PlaybackException) {
            diagnostic(
                "Playback Error",
                "code=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName.orEmpty()} " +
                    "message=${error.message.orEmpty().take(180)}",
                "ERROR"
            )
            if (retryFromStartAfter416(error)) return
            if (attemptAutoRetryFromIoError(error)) return
            // Parsing errors near the end of a stream are common with some sources.
            // Treat them as playback completion instead of showing an error.
            if (isParsingErrorNearEnd(error)) {
                seekFeedbackJob?.cancel()
                transitionReliability(PlaybackReliabilityPhase.ENDED)
                _uiState.update { it.copy(isEnded = true, isBuffering = false, isSeeking = false) }
                return
            }
            if (attemptAutomaticSourceFallback(error)) return
            seekFeedbackJob?.cancel()
            transitionReliability(PlaybackReliabilityPhase.FAILED)
            _uiState.update {
                it.copy(
                    errorMessage = error.errorCodeName.ifBlank { error.message ?: "Playback error" },
                    isBuffering = false,
                    isSeeking = false
                )
            }
        }
    }

    private var appliedVideoFrameRate: Float = 0f

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            diagnostic(
                "Video Decoder",
                "name=${decoderName.take(120)} init=${initializationDurationMs}ms"
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            diagnostic(
                "Dropped Frames",
                "count=$droppedFrames window=${elapsedMs}ms position=${eventTime.eventPlaybackPositionMs}ms",
                if (droppedFrames >= 10) "WARN" else "INFO"
            )
            if (droppedFrames >= 6) {
                decoderStressJob?.cancel()
                _uiState.update { it.copy(playbackUnderStress = true) }
                decoderStressJob = scope.launch {
                    delay(5_000L)
                    _uiState.update { it.copy(playbackUnderStress = false) }
                }
            }
            val player = exoPlayer ?: return
            val shouldRecover = jitterRecoveryPolicy.shouldRecover(
                nowMs = SystemClock.elapsedRealtime(),
                sourcePreparedAtMs = sourcePreparedElapsedMs,
                droppedFrames = droppedFrames,
                eligible = player.isPlaying &&
                    player.playbackState == Player.STATE_READY &&
                    _uiState.value.hasRenderedFirstFrame
            )
            if (!shouldRecover) return

            // A one-millisecond seek cannot be optimized away and flushes the
            // video renderer, matching the pause/play recovery without forcing
            // the user to interrupt playback.
            val position = player.currentPosition.coerceAtLeast(0L)
            beginSeekFeedback()
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.seekTo(position + 1L)
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
                mediaLoadData.trackFormat?.let { format ->
                    diagnostic(
                        "Video Format",
                        "${format.width}x${format.height} mime=${format.sampleMimeType.orEmpty()} " +
                            "codec=${format.codecs.orEmpty()} bitrate=${format.bitrate} " +
                            "fps=${format.frameRate}"
                    )
                }
            }
            // Frame rate matching: detect video track format changes
            if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO && frameRateManager != null) {
                val rawRate = mediaLoadData.trackFormat?.frameRate ?: Format.NO_VALUE.toFloat()
                if (rawRate > 0f) {
                    val snapped = frameRateManager.snapToStandardRate(rawRate)
                    if (snapped != appliedVideoFrameRate) {
                        appliedVideoFrameRate = snapped
                        scope.launch(Dispatchers.Main) {
                            frameRateManager.matchDisplayToFrameRate(snapped)
                        }
                    }
                }
            }
            if (mediaLoadData.trackType != C.TRACK_TYPE_TEXT) return
            val trackFormat = mediaLoadData.trackFormat ?: return
            val formatTag = inferSubtitleFormatTag(format = trackFormat, externalUrl = null) ?: return

            var didUpdateHint = false
            val explicitId = trackFormat.id?.trim()?.takeIf { it.isNotEmpty() }
            if (explicitId != null) {
                val stableId = matchExternalSubtitleTrackId(explicitId, externalSubtitleSources.keys)
                    ?: stableTrackId("s", 0, 0, explicitId)
                if (subtitleFormatHintsByTrackId[stableId] != formatTag) {
                    subtitleFormatHintsByTrackId[stableId] = formatTag
                    didUpdateHint = true
                }
            }
            subtitleLabelLanguageKey(trackFormat.label, trackFormat.language)?.let { key ->
                if (subtitleFormatHintsByLabelLanguage[key] != formatTag) {
                    subtitleFormatHintsByLabelLanguage[key] = formatTag
                    didUpdateHint = true
                }
            }
            normalizedSubtitleLabelKey(trackFormat.label)?.let { key ->
                if (subtitleFormatHintsByLabel[key] != formatTag) {
                    subtitleFormatHintsByLabel[key] = formatTag
                    didUpdateHint = true
                }
            }
            if (!didUpdateHint) return
            val livePlayer = exoPlayer ?: return
            if (livePlayer.playbackState != Player.STATE_READY) {
                pendingHintTrackRefresh = true
                return
            }
            scheduleHintTrackRefresh()
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val player by _playerReady.collectAsStateWithLifecycle()
        if (player != null) {
            AndroidView(
                modifier = modifier.fillMaxSize(),
                factory = { context ->
                    android.widget.FrameLayout(context).apply {
                        // PlayerView
                        val pv = PlayerView(context).apply {
                            this.player = player
                            useController = false
                            keepScreenOn = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            applySubtitleOffset(this, subtitleVerticalOffsetPercent)
                            applySubtitleSize(this, subtitleSizePercent)
                            applySubtitleCaptionStyle(this)
                            playerView = this
                        }
                        addView(pv, android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        ))

                        // ASS subtitle overlay as sibling ON TOP of PlayerView
                        if (playbackSettings.assRendererEnabled) {
                            val overlay = assHandler?.overlayView ?: SubtitleOverlayView(context)
                            addView(overlay, android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            ))
                        }
                    }
                },
                update = { frame ->
                    val pv = frame.getChildAt(0) as? PlayerView
                    if (pv != null && pv.player !== player) {
                        pv.player = player
                    }
                }
            )
        } else {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }

    override fun load(request: PlayerLoadRequest) {
        if (released) return
        loadToken++
        diagnosticsSessionId = request.diagnosticsSessionId
        diagnostic(
            "Load Requested",
            "source=${PlaybackDiagnostics.describeUrl(request.mediaUrl)} " +
                "resume=${request.startPositionMs}ms sources=${request.sources.size}"
        )

        // Emit loading state immediately so Compose can render the spinner
        _uiState.update {
            it.copy(
                isBuffering = true,
                isSeeking = false,
                hasRenderedFirstFrame = false,
                isEnded = false,
                errorMessage = null
            )
        }

        // Run all normalization and preparation in a coroutine to avoid blocking the main thread
        scope.launch {
            yield() // Let Compose render loading state before any heavy work

            if (released) return@launch

            subtitleFormatHintsByTrackId.clear()
            subtitleFormatHintsByLabelLanguage.clear()
            subtitleFormatHintsByLabel.clear()

            val normalizedSubtitles = request.subtitles.mapIndexed { index, subtitle ->
                val normalizedId = subtitle.id.trim().ifBlank { "ext_$index" }
                val normalizedLabel = subtitle.label.ifBlank { "Subtitle ${index + 1}" }
                subtitle.copy(id = normalizedId, label = normalizedLabel)
            }
            val normalizedRequest = request.copy(subtitles = normalizedSubtitles)

            loadRequest = normalizedRequest
            externalSubtitleSources = normalizedSubtitles.associateBy { subtitle ->
                externalSubtitleTrackId(subtitle.id)
            }
            pendingAudioTrackId = normalizedRequest.preferredAudioTrackId
            pendingSubtitleTrackId = normalizeSubtitleSelectionId(normalizedRequest.preferredSubtitleTrackId)
            forcedSubtitleTrackId = pendingSubtitleTrackId?.takeIf { it in externalSubtitleSources.keys }
            hasAppliedAudioLanguagePref = false
            hasAppliedSubtitleLanguagePref = false
            manualSubtitleSelection = pendingSubtitleTrackId != null
            _uiState.update { it.copy(subtitleSelectionWasManual = false) }

            val defaultSource = PlayerSourceOption(
                id = "default",
                url = normalizedRequest.mediaUrl,
                label = normalizedRequest.title.ifBlank { "Source" },
                title = normalizedRequest.title.ifBlank { "Source" }
            )
            val normalizedSources = normalizedRequest.sources
                .mapIndexed { index, source ->
                    val id = source.id.ifBlank { "source_$index" }
                    val label = source.label.ifBlank { "Source ${index + 1}" }
                    source.copy(id = id, label = label)
                }
                .distinctBy { it.id }
                .ifEmpty { listOf(defaultSource) }

            _sourceOptions.value = normalizedSources
            // Match by URL first; if no match (e.g. torrent magnet resolved to localhost),
            // use defaultSource for playback but track the first source as current
            val initialSource = normalizedSources.firstOrNull { it.id == normalizedRequest.initialSourceId }
                ?: normalizedSources.firstOrNull { it.url == normalizedRequest.mediaUrl }
            if (initialSource != null) {
                currentSourceId = initialSource.id
                reliabilityStateMachine.begin(initialSource.id)
                transitionReliability(PlaybackReliabilityPhase.PREPARING)
                prepareSource(
                    source = initialSource.copy(url = normalizedRequest.mediaUrl),
                    startPositionMs = request.startPositionMs,
                    autoPlay = request.autoPlay
                )
            } else {
                // mediaUrl doesn't match any source (e.g. resolved torrent localhost URL)
                // Play the mediaUrl directly, mark first source as current
                currentSourceId = defaultSource.id
                reliabilityStateMachine.begin(currentSourceId)
                transitionReliability(PlaybackReliabilityPhase.PREPARING)
                prepareSource(
                    source = defaultSource,
                    startPositionMs = request.startPositionMs,
                    autoPlay = request.autoPlay
                )
            }
        }
    }

    override fun play() {
        if (released) return
        // Resuming a ready decoder is instantaneous. Jitter recovery is handled by
        // the dropped-frame policy, so an unconditional flush/seek is unnecessary.
        exoPlayer?.play()
        diagnostic("Play")
    }

    override fun pause() {
        if (released) return
        exoPlayer?.pause()
        diagnostic("Pause")
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        pendingStartPositionMs = 0L
        val player = exoPlayer ?: return
        player.setSeekParameters(SeekParameters.EXACT)
        beginSeekFeedback()
        player.seekTo(positionMs.coerceAtLeast(0L))
        diagnostic("Seek", "target=${positionMs.coerceAtLeast(0L)}ms")
        updateProgressState()
    }

    override fun seekBy(deltaMs: Long) {
        if (released) return
        pendingStartPositionMs = 0L
        val player = exoPlayer ?: return
        // CLOSEST_SYNC often lands on a buffered earlier keyframe and avoids an
        // unnecessary torrent range request for ordinary forward/rewind jumps.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        beginSeekFeedback()
        player.seekTo(target)
        diagnostic("Seek", "delta=${deltaMs}ms target=${target}ms")
        updateProgressState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return
        val safeSpeed = speed.coerceIn(0.25f, 2.0f)
        exoPlayer?.setPlaybackSpeed(safeSpeed)
        _uiState.update { it.copy(playbackSpeed = safeSpeed) }
    }

    override fun selectSource(sourceId: String) {
        if (released) return
        if (sourceId == currentSourceId) return
        val source = _sourceOptions.value.firstOrNull { it.id == sourceId } ?: return

        // Magnet URLs need TorrentService — delegate to the callback
        if (source.url.startsWith("magnet:")) {
            val handler = onMagnetSourceSelected
            if (handler != null) {
                currentSourceId = sourceId
                _uiState.update { it.copy(currentSourceId = sourceId, isBuffering = true, hasRenderedFirstFrame = false) }
                handler.invoke(
                    source.url,
                    source.fileIdx,
                    source.fileName,
                    { resolvedMagnetUrl, localUrl ->
                        if (!released && currentSourceId == sourceId) {
                            val resolvedOption = _sourceOptions.value.firstOrNull { it.id == sourceId } ?: source
                            switchToSource(resolvedOption.id, resolvedOption.copy(url = localUrl))
                        }
                    },
                    { message ->
                        if (!released && currentSourceId == sourceId) {
                            _uiState.update {
                                it.copy(
                                    isBuffering = false,
                                    errorMessage = message.ifBlank { "Torrent playback failed" }
                                )
                            }
                        }
                    }
                )
                return
            }
        }

        switchToSource(sourceId, source)
    }

    private fun switchToSource(sourceId: String, source: PlayerSourceOption) {
        val player = exoPlayer
        val lastPosition = player?.currentPosition ?: _uiState.value.positionMs
        val wasPlaying = player?.playWhenReady ?: _uiState.value.playWhenReady
        val currentSpeed = player?.playbackParameters?.speed ?: _uiState.value.playbackSpeed
        val keepSubtitlesOff = SubtitleSelectionPolicy.preserveOffOnSourceChange(
            manualSubtitleSelection, _uiState.value.selectedSubtitleTrackId
        )

        loadToken++
        pendingAudioTrackId = null
        pendingSubtitleTrackId = if (keepSubtitlesOff) SUBTITLE_OFF_ID else null
        hasAppliedAudioLanguagePref = false
        hasAppliedSubtitleLanguagePref = false
        manualSubtitleSelection = keepSubtitlesOff
        currentSourceId = sourceId
        reliabilityStateMachine.begin(sourceId)
        transitionReliability(PlaybackReliabilityPhase.PREPARING)

        subtitleVerticalOffsetPercent = 0
        lastCueGroup = null
        _uiState.update { it.copy(subtitleVerticalOffsetPercent = 0) }
        playerView?.let { applySubtitleOffset(it, 0) }

        prepareSource(
            source = source,
            startPositionMs = lastPosition,
            autoPlay = wasPlaying
        )
        setPlaybackSpeed(currentSpeed)
    }

    override fun selectAudioTrack(trackId: String?) {
        if (released) return
        val id = trackId?.takeIf { it.isNotBlank() } ?: return
        val player = exoPlayer ?: return
        val shouldResumeAfterSwitch = player.playWhenReady
        val locator = audioTrackLocators[id]
        if (locator == null) {
            pendingAudioTrackId = id
            return
        }

        val builder = player.trackSelectionParameters.buildUpon()
        builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        builder.setOverrideForType(
            TrackSelectionOverride(locator.trackGroup, listOf(locator.trackIndex))
        )
        player.trackSelectionParameters = builder.build()
        if (shouldResumeAfterSwitch) {
            player.playWhenReady = true
            scheduleAudioSwitchRecovery(player)
        }
        pendingAudioTrackId = null
        _uiState.update { it.copy(selectedAudioTrackId = id) }
        refreshTrackOptions(player.currentTracks)
    }

    override fun selectSubtitleTrack(trackId: String?) {
        manualSubtitleSelection = true
        _uiState.update { it.copy(subtitleSelectionWasManual = true) }
        applySubtitleTrack(trackId)
    }

    private fun scheduleSubtitleAutoSync(id: String) {
        if (id != autoSyncSubtitleId && autoAppliedSubtitleDelay) {
            autoAppliedSubtitleDelay = false
            subtitleDelayUs.set(0L)
            _uiState.update { it.copy(subtitleDelayMs = 0L) }
        }
        val mediaUrl = autoSyncMediaUrl
        val subtitle = externalSubtitleSources[id]
        if (id == SUBTITLE_OFF_ID || subtitle == null || mediaUrl == null || subtitleDelayChangedManually) {
            subtitleAutoSyncJob?.cancel()
            subtitleAutoSyncJob = null
            autoSyncSubtitleId = null
            return
        }
        if (autoSyncSubtitleId == id) return
        subtitleAutoSyncJob?.cancel()
        autoSyncSubtitleId = id
        val token = loadToken
        subtitleAutoSyncJob = scope.launch {
            // Audio probing is secondary to playback and never delays the first frame.
            repeat(30) {
                if (released || token != loadToken || autoSyncSubtitleId != id) return@launch
                if (_uiState.value.hasRenderedFirstFrame) return@repeat
                delay(1_000L)
            }
            if (!_uiState.value.hasRenderedFirstFrame) return@launch
            delay(6_000L)
            if (_uiState.value.isBuffering || subtitleDelayChangedManually) return@launch
            diagnostic("Subtitle Auto-Sync Started", "track=$id")
            val result = try {
                kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    withContext(Dispatchers.IO) { SubtitleAutoSync.align(mediaUrl, subtitle.url) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostic("Subtitle Auto-Sync Unavailable", error.javaClass.simpleName)
                null
            }
            if (result == null) {
                diagnostic("Subtitle Auto-Sync Skipped", "insufficient or low-confidence match")
                return@launch
            }
            if (released || token != loadToken || autoSyncSubtitleId != id ||
                _uiState.value.selectedSubtitleTrackId != id || subtitleDelayChangedManually
            ) return@launch
            subtitleDelayUs.set(result.offsetMs * 1_000L)
            autoAppliedSubtitleDelay = true
            _uiState.update { it.copy(subtitleDelayMs = result.offsetMs) }
            diagnostic(
                "Subtitle Auto-Sync Applied",
                "offset=${result.offsetMs}ms improvement=${"%.2f".format(Locale.US, result.improvement)} " +
                    "analyzed=${result.analyzedMs}ms"
            )
        }
    }

    private fun applySubtitleTrack(trackId: String?) {
        if (released) return
        val player = exoPlayer ?: return
        val id = normalizeSubtitleSelectionId(trackId) ?: SUBTITLE_OFF_ID
        scheduleSubtitleAutoSync(id)
        val builder = player.trackSelectionParameters.buildUpon()
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)

        if (id == SUBTITLE_OFF_ID) {
            forcedSubtitleTrackId = null
            pendingSubtitleTrackId = null
            lastCueGroup = null
            _uiState.update { it.copy(selectedSubtitleTrackId = SUBTITLE_OFF_ID) }
            playerView?.subtitleView?.setCues(emptyList())
            playerView?.subtitleView?.visibility = android.view.View.GONE
            assHandler?.clearOverlay()
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            player.trackSelectionParameters = builder.build()
            refreshTrackOptions(player.currentTracks)
            return
        }

        playerView?.subtitleView?.visibility = android.view.View.VISIBLE

        val locator = subtitleTrackLocators[id]
        if (locator != null) {
            forcedSubtitleTrackId = null
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(
                TrackSelectionOverride(locator.trackGroup, listOf(locator.trackIndex))
            )
            player.trackSelectionParameters = builder.build()
            pendingSubtitleTrackId = null
            _uiState.update { it.copy(selectedSubtitleTrackId = id) }
            refreshTrackOptions(player.currentTracks)

            // Switch ASS renderer to matching track, or clear if non-ASS
            if (playbackSettings.assRendererEnabled) {
                val format = locator.trackGroup.getFormat(locator.trackIndex)
                val mime = format.sampleMimeType ?: ""
                val isAss = mime == "text/x-ssa" || mime == "text/x-ass" || mime == androidx.media3.common.MimeTypes.TEXT_SSA
                if (isAss) {
                    val assIndex = findAssTrackIndex(player.currentTracks, locator)
                    if (assIndex >= 0) assHandler?.selectTrack(assIndex)
                    playerView?.subtitleView?.visibility = android.view.View.GONE
                } else {
                    assHandler?.clearOverlay()
                    playerView?.subtitleView?.visibility = android.view.View.VISIBLE
                }
            }
            return
        }

        // External or not-yet-available track — mark as pending so it gets
        // applied once onTracksChanged fires with the locator available.
        forcedSubtitleTrackId = if (id in externalSubtitleSources.keys) id else null
        pendingSubtitleTrackId = id
        _uiState.update { it.copy(selectedSubtitleTrackId = id) }
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        player.trackSelectionParameters = builder.build()
    }

    override fun setSubtitleVerticalOffset(percent: Int) {
        if (released) return
        val clamped = percent.coerceIn(MIN_SUBTITLE_OFFSET_PERCENT, MAX_SUBTITLE_OFFSET_PERCENT)
        subtitleVerticalOffsetPercent = clamped
        _uiState.update { it.copy(subtitleVerticalOffsetPercent = clamped) }
        playerView?.let { applySubtitleOffset(it, clamped) }
        applyTransformedCues()
    }

    override fun setSubtitleSize(percent: Int) {
        if (released) return
        val clamped = percent.coerceIn(MIN_SUBTITLE_SIZE_PERCENT, MAX_SUBTITLE_SIZE_PERCENT)
        subtitleSizePercent = clamped
        _uiState.update { it.copy(subtitleSizePercent = clamped) }
        playerView?.let { applySubtitleSize(it, clamped) }
        applyTransformedCues()
    }

    override fun setSubtitleDelay(delayMs: Long) {
        if (released) return
        subtitleDelayChangedManually = true
        autoAppliedSubtitleDelay = false
        subtitleAutoSyncJob?.cancel()
        val clamped = delayMs.coerceIn(MIN_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)
        subtitleDelayUs.set(clamped * 1000L)
        _uiState.update { it.copy(subtitleDelayMs = clamped) }
    }

    override fun persistableSubtitleDelayMs(): Long =
        if (autoAppliedSubtitleDelay) 0L else _uiState.value.subtitleDelayMs

    override fun setSubtitleTextColor(color: Int) {
        if (released) return
        subtitleTextColor = color
        _uiState.update { it.copy(subtitleTextColor = color) }
        playerView?.let { applySubtitleCaptionStyle(it) }
    }

    override fun setSubtitleBackgroundColor(color: Int) {
        if (released) return
        subtitleBackgroundColor = color
        _uiState.update { it.copy(subtitleBackgroundColor = color) }
        playerView?.let { applySubtitleCaptionStyle(it) }
    }

    private fun applySubtitleCaptionStyle(pv: PlayerView) {
        pv.subtitleView?.setStyle(
            CaptionStyleCompat(
                subtitleTextColor,
                subtitleBackgroundColor,
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                ResourcesCompat.getFont(appContext, R.font.lato_regular)
                    ?: android.graphics.Typeface.DEFAULT
            )
        )
    }

    private fun applySubtitleSize(pv: PlayerView, percent: Int) {
        pv.subtitleView?.apply {
            val scaledSize = DEFAULT_SUBTITLE_BASE_FONT_SIZE_SP * (percent / 100f)
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledSize)
            setApplyEmbeddedFontSizes(false)
        }
    }

    private fun applySubtitleOffset(pv: PlayerView, percent: Int) {
        pv.subtitleView?.apply {
            val fraction = (DEFAULT_SUBTITLE_BOTTOM_PADDING_FRACTION + percent / 100f)
                .coerceIn(0f, 0.5f)
            setBottomPaddingFraction(fraction)
            // Apply explicit bottom padding for stronger offset into letterbox areas
            post {
                val extraPadding = (height * (percent / 400f)).toInt().coerceAtLeast(0)
                setPadding(paddingLeft, paddingTop, paddingRight, extraPadding)
            }
        }
    }

    private fun applyTransformedCues() {
        if (_uiState.value.selectedSubtitleTrackId == SUBTITLE_OFF_ID) return
        val cueGroup = lastCueGroup ?: return
        val offset = subtitleVerticalOffsetPercent
        val sizeScale = subtitleSizePercent
        val needsOffset = offset != 0
        val needsSize = sizeScale != 100
        if (!needsOffset && !needsSize) return
        val sv = playerView?.subtitleView ?: return
        val hasBitmapOrPositionedCues = cueGroup.cues.any { cue ->
            cue.line != androidx.media3.common.text.Cue.DIMEN_UNSET ||
                cue.bitmap != null
        }
        if (!hasBitmapOrPositionedCues) return
        val scaleFactor = sizeScale / 100f
        val transformed = cueGroup.cues.map { cue ->
            var builder: androidx.media3.common.text.Cue.Builder? = null
            // Offset: shift line position for positioned cues
            if (needsOffset &&
                cue.line != androidx.media3.common.text.Cue.DIMEN_UNSET &&
                cue.lineType == androidx.media3.common.text.Cue.LINE_TYPE_FRACTION
            ) {
                val newLine = (cue.line - offset / 100f).coerceIn(0f, 1f)
                builder = (builder ?: cue.buildUpon())
                    .setLine(newLine, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION)
            }
            // Size: scale bitmap cues and re-center horizontally
            if (needsSize && cue.bitmap != null) {
                val b = builder ?: cue.buildUpon()
                if (cue.size != androidx.media3.common.text.Cue.DIMEN_UNSET) {
                    val oldSize = cue.size
                    val newSize = oldSize * scaleFactor
                    b.setSize(newSize)
                    // Adjust horizontal position to keep the bitmap centered
                    if (cue.position != androidx.media3.common.text.Cue.DIMEN_UNSET) {
                        val newPosition = when (cue.positionAnchor) {
                            androidx.media3.common.text.Cue.ANCHOR_TYPE_START ->
                                (cue.position + (oldSize - newSize) / 2f).coerceIn(0f, 1f)
                            androidx.media3.common.text.Cue.ANCHOR_TYPE_END ->
                                (cue.position - (oldSize - newSize) / 2f).coerceIn(0f, 1f)
                            else -> cue.position // ANCHOR_TYPE_MIDDLE: already centered
                        }
                        b.setPosition(newPosition)
                    }
                }
                if (cue.bitmapHeight != androidx.media3.common.text.Cue.DIMEN_UNSET) {
                    b.setBitmapHeight(cue.bitmapHeight * scaleFactor)
                }
                builder = b
            }
            builder?.build() ?: cue
        }
        // Use post to apply after PlayerView's internal cue handling
        sv.post {
            sv.setCues(if (_uiState.value.selectedSubtitleTrackId == SUBTITLE_OFF_ID) emptyList() else transformed)
        }
    }

    override fun release() {
        subtitleLookupJob?.cancel()
        diagnostic("Player Released", PlaybackDiagnostics.memorySummary())
        released = true
        frameRateManager?.restoreOriginalMode()
        assHandler?.release()
        assHandler = null

        scopeJob.cancel()
        subtitleAutoSyncJob?.cancel()
        stopProgressLoop()
        audioSwitchRecoveryJob = null
        seekFeedbackJob = null
        decoderStressJob = null

        exoPlayer?.let { player ->
            player.removeListener(playerListener)
            player.removeAnalyticsListener(analyticsListener)
            player.release()
        }
        exoPlayer = null
        _playerReady.value = null
        playerView = null
        lastCueGroup = null
        subtitleDelayUs.set(0L)

        // Cancel this playback's requests but retain the process-wide connection
        // pool so the next episode can reuse warm TLS connections immediately.
        listOfNotNull(okHttpClient, torrentOkHttpClient).forEach { client ->
            client.dispatcher.cancelAll()
        }
        okHttpClient = null
        torrentOkHttpClient = null

        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        externalSubtitleSources = emptyMap()
        forcedSubtitleTrackId = null
        subtitleFormatHintsByTrackId.clear()
        subtitleFormatHintsByLabelLanguage.clear()
        subtitleFormatHintsByLabel.clear()
        bufferRecoveryPolicy.reset()
        sourcePreparedElapsedMs = 0L
        jitterRecoveryPolicy.reset()
        ioAutoRetrySourceId = null
        ioAutoRetryCountForCurrentSource = 0
        pendingStartPositionMs = 0L
        pendingHintTrackRefresh = false
        hintTrackRefreshJob?.cancel()
        hintTrackRefreshJob = null
        reliabilityStateMachine.transition(PlaybackReliabilityPhase.IDLE)
    }

    /**
     * Pre-create ExoPlayer and the appropriate HTTP client for direct or torrent playback.
     */
    fun warmup(isLocalhost: Boolean = true) {
        if (released) return
        isTorrentStream = isLocalhost
        scope.launch {
            withContext(Dispatchers.IO) { getOrCreateOkHttpClient(isLocalhost = isLocalhost) }
            ensurePlayer()
        }
    }

    private fun prepareSource(
        source: PlayerSourceOption,
        startPositionMs: Long,
        autoPlay: Boolean,
        resetSourceRetryBudget: Boolean = true
    ) {
        val request = loadRequest ?: return
        subtitleLookupJob?.cancel()

        subtitleAutoSyncJob?.cancel()
        val lightweightReference = ThumbnailSourceSelector.select(source.url, request.sources)?.source
        autoSyncMediaUrl = (lightweightReference ?: source)
            .takeIf { candidate ->
                candidate.videoSize?.let { it <= 3_000_000_000L } == true ||
                    (candidate.videoSize == null && (candidate.qualityHeight ?: Int.MAX_VALUE) <= 720)
            }
            ?.url
        autoSyncSubtitleId = null
        if (autoAppliedSubtitleDelay) {
            autoAppliedSubtitleDelay = false
            subtitleDelayUs.set(0L)
            _uiState.update { it.copy(subtitleDelayMs = 0L) }
        }

        if (resetSourceRetryBudget) {
            ioAutoRetrySourceId = source.id
            ioAutoRetryCountForCurrentSource = 0
            hasRetriedCurrentSourceAfter416 = false
        }

        audioSwitchRecoveryJob?.cancel()
        audioSwitchRecoveryJob = null
        bufferRecoveryPolicy.reset()
        sourcePreparedElapsedMs = SystemClock.elapsedRealtime()
        diagnostic(
            "Source Preparing",
            "id=${source.id.take(80)} source=${PlaybackDiagnostics.describeUrl(source.url)} " +
                "resume=${startPositionMs.coerceAtLeast(0L)}ms autoplay=$autoPlay"
        )
        jitterRecoveryPolicy.reset()

        _uiState.update {
            it.copy(
                currentSourceId = source.id,
                isBuffering = true,
                isSeeking = false,
                hasRenderedFirstFrame = false,
                errorMessage = null
            )
        }

        val token = loadToken

        scope.launch {
            val playerPreWarmed = exoPlayer != null

            // Yield so Compose can render the loading/buffering state before heavy work.
            // Skip when player is pre-warmed (torrent) since there's no heavy work.
            if (!playerPreWarmed) yield()

            // Initialize OkHttpClient off the main thread to avoid blocking UI
            withContext(Dispatchers.IO) { getOrCreateOkHttpClient() }

            if (loadToken != token || released) return@launch
            val fallbackSubtitles = request.subtitles.filter { it.sourcePriority == SubtitleSourcePriority.ADDON }
            // Prepare video immediately using subtitles already available.
            val activeSubtitles = (source.subtitles + fallbackSubtitles)
                .distinctBy { it.url }
            externalSubtitleSources = activeSubtitles.associateBy { externalSubtitleTrackId(it.id) }

            // Include ALL external subtitles upfront as sidecar sources.
            // SingleSampleMediaSource is lazy — it won't download until the
            // renderer actually selects the track, so this is cheap.
            // Ensure AssHandler exists before media source creation
            if (playbackSettings.assRendererEnabled) getOrCreateAssHandler()

            val mediaSource = withContext(Dispatchers.Default) {
                val subtitleConfigs = externalSubtitleSources.map { (_, subtitle) ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setId(externalSubtitleTrackId(subtitle.id))
                        .setLabel(subtitle.label)
                        .setLanguage(subtitle.language)
                        .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                        .also { builder ->
                            inferSubtitleMimeType(subtitle.url)?.let { builder.setMimeType(it) }
                        }
                        .build()
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(source.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(request.title)
                            .setDisplayTitle(request.title)
                            .build()
                    )
                    .setSubtitleConfigurations(subtitleConfigs)
                    .build()
                createMediaSource(source.url, mediaItem)
            }

            if (loadToken != token || released) return@launch

            // Create ExoPlayer inside coroutine so UI can render between yields.
            // Skip yield when pre-warmed — player already exists, nothing heavy.
            if (!playerPreWarmed) yield()
            val player = ensurePlayer()
            if (released) return@launch

            // Let Compose process _playerReady and create the PlayerView/surface
            // before prepare() starts. Without this, audio auto-starts with no surface.
            // When pre-warmed, surface is already created — skip the yield.
            if (!playerPreWarmed) yield()

            // If a separate audio URL is provided (YouTube adaptive streams),
            // merge video + audio sources for high-quality playback.
            // For YouTube adaptive streams, use chunked data source for both
            // video and audio to prevent YouTube's download throttling.
            val finalMediaSource = if (!request.separateAudioUrl.isNullOrBlank() || request.trailerRequestHeaders.isNotEmpty()) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true)
                    .setForceHighestSupportedBitrate(true).build()
                com.saab.tv.AppDiagnostics.event("Trailer", "Player Quality Policy", "highestSupported=true viewportCap=false")
                val ytSourceFactory = DefaultMediaSourceFactory(
                    com.saab.tv.data.trailer.YoutubeChunkedDataSourceFactory(requestHeaders = request.trailerRequestHeaders)
                )
                val videoItem = MediaItem.Builder().setUri(source.url).apply {
                    if (source.url.contains("/manifest/hls/") || source.url.contains(".m3u8"))
                        setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    if (source.url.contains("/manifest/dash/") || source.url.contains(".mpd"))
                        setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                }.build()
                val videoSource = ytSourceFactory.createMediaSource(videoItem)
                request.separateAudioUrl?.takeIf { it.isNotBlank() }?.let {
                    MergingMediaSource(videoSource, ytSourceFactory.createMediaSource(MediaItem.fromUri(it)))
                } ?: videoSource
            } else {
                mediaSource
            }

            val requestedStartPosition = startPositionMs.coerceAtLeast(0L)
            if (!isTorrentStream && requestedStartPosition > 0L) {
                // Direct streams can prepare at the resume position immediately,
                // avoiding a redundant request at byte/time zero first.
                player.setMediaSource(finalMediaSource, requestedStartPosition)
                pendingStartPositionMs = 0L
            } else {
                player.setMediaSource(finalMediaSource)
                // Large torrent MKVs prepare their index more reliably at zero;
                // defer only this case until STATE_READY.
                pendingStartPositionMs = requestedStartPosition
            }

            player.playWhenReady = autoPlay
            player.prepare()
            // Optional filename/size subtitle lookup must not delay the first frame.
            // Only reload once if no embedded/preloaded subtitle can satisfy the
            // preference; off and manual selections are never overridden.
            if (source.subtitles.isEmpty() && request.trailerRequestHeaders.isEmpty()) {
                subtitleLookupJob = scope.launch {
                    val rendered = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                        _uiState.first { it.hasRenderedFirstFrame }
                    } ?: return@launch
                    fun embeddedOrOffPreferred(): Boolean {
                        val preferredId = resolvePreferredSubtitleTrack()
                        return preferredId == SUBTITLE_OFF_ID || _subtitleTracks.value.any {
                            it.id == preferredId && it.subtitleSourcePriority == SubtitleSourcePriority.EMBEDDED
                        }
                    }
                    if (released || token != loadToken || manualSubtitleSelection ||
                        rendered.selectedSubtitleTrackId == SUBTITLE_OFF_ID && request.preferredSubtitleTrackId == SUBTITLE_OFF_ID ||
                        embeddedOrOffPreferred()) return@launch
                    val matched = try {
                        kotlinx.coroutines.withTimeoutOrNull(2_500L) { resolveStreamSubtitles?.invoke(source) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (_: Exception) { null }
                    if (matched.isNullOrEmpty() || released || token != loadToken ||
                        manualSubtitleSelection || embeddedOrOffPreferred()) return@launch
                    // A matching preloaded catalog needs no timeline reset.
                    if (matched.map { it.url } == activeSubtitles.map { it.url }) return@launch
                    diagnostic("Deferred Subtitles Ready", "count=${matched.size}")
                    hasAppliedSubtitleLanguagePref = false
                    prepareSource(source.copy(subtitles = matched), player.currentPosition, player.playWhenReady,
                        resetSourceRetryBudget = false)
                }
            }
            updateProgressLoopState()
        }
    }

    private fun diagnostic(event: String, details: String = "", level: String = "INFO") {
        PlaybackDiagnostics.event(
            context = appContext,
            sessionId = diagnosticsSessionId,
            component = "ExoPlayer",
            event = event,
            details = details,
            level = level
        )
    }

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val trackSelector = DefaultTrackSelector(appContext).apply {
            setParameters(
                buildUponParameters()
                    .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                    .setTunnelingEnabled(playbackSettings.tunnelingEnabled)
            )
        }

        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val currentQuality = _sourceOptions.value
            .firstOrNull { it.id == currentSourceId }
            ?.qualityHeight
        val bufferConfig = AdaptiveBufferPolicy.choose(
            isTorrent = isTorrentStream,
            memoryClassMb = activityManager?.memoryClass ?: 256,
            isLowRamDevice = activityManager?.isLowRamDevice ?: true,
            qualityHeight = currentQuality
        )
        diagnostic(
            "Adaptive Buffer",
            "memory=${activityManager?.memoryClass ?: 0}MB lowRam=${activityManager?.isLowRamDevice} " +
                "quality=${currentQuality ?: 0} max=${bufferConfig.maxBufferMs}ms " +
                "target=${bufferConfig.targetBufferBytes / (1024 * 1024)}MB"
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferConfig.minBufferMs,
                bufferConfig.maxBufferMs,
                bufferConfig.bufferForPlaybackMs,
                bufferConfig.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(bufferConfig.targetBufferBytes)
            .setBackBuffer(bufferConfig.backBufferMs, false)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val extensionRendererMode = when (playbackSettings.decoderPriority) {
            0 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            2 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        val renderersFactory = if (playbackSettings.assRendererEnabled) {
            val handler = getOrCreateAssHandler()
            AssRenderersFactory(appContext, handler)
                .setExtensionRendererMode(extensionRendererMode)
                .setMapDV7ToHevc(playbackSettings.mapDV7ToHevc)
        } else {
            SubtitleDelayRenderersFactory(appContext, subtitleDelayUs::get)
                .setExtensionRendererMode(extensionRendererMode)
                .setMapDV7ToHevc(playbackSettings.mapDV7ToHevc)
        }

        val mediaSourceFactory = if (playbackSettings.assRendererEnabled) {
            val handler = getOrCreateAssHandler()
            DefaultMediaSourceFactory(appContext, AssExtractorsFactory(handler))
        } else {
            DefaultMediaSourceFactory(appContext, sharedExtractorsFactory)
        }

        if (loadRequest?.trailerRequestHeaders?.isNotEmpty() == true) {
            renderersFactory.setMediaCodecSelector(com.saab.tv.data.trailer.TrailerHardwareCodecSupport.selector)
        }
        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(SeekIntervalPolicy.intervalMs(playbackSettings.seekTimeIntervalSeconds))
            .setSeekForwardIncrementMs(SeekIntervalPolicy.intervalMs(playbackSettings.seekTimeIntervalSeconds))
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, true)
        player.setHandleAudioBecomingNoisy(true)
        if (frameRateManager != null) {
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        } else {
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        }
        assHandler?.player = player
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        exoPlayer = player
        _playerReady.value = player
        updateProgressLoopState()
        return player
    }

    private fun isTvDevice(): Boolean {
        cachedIsTvDevice?.let { return it }
        val pm = appContext.packageManager

        val result = when {
            (appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION -> true
            pm.hasSystemFeature("amazon.hardware.fire_tv") -> true
            !hasSafChooser(pm) -> true
            Build.VERSION.SDK_INT < 30 && (
                (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
                    !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) ||
                pm.hasSystemFeature("android.hardware.hdmi.cec") ||
                Build.MANUFACTURER.equals("zidoo", ignoreCase = true)
            ) -> true
            else -> false
        }
        cachedIsTvDevice = result
        return result
    }

    private fun hasSafChooser(pm: PackageManager): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        return intent.resolveActivity(pm) != null
    }

    private fun createExtractorsFactory(): DefaultExtractorsFactory {
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1_500 * TsExtractor.TS_PACKET_SIZE)
    }

    private fun createMediaSource(sourceUrl: String, mediaItem: MediaItem): MediaSource {
        val sourceUri = Uri.parse(sourceUrl)
        val isHttp = sourceUri.scheme?.lowercase(Locale.US)?.startsWith("http") == true

        if (!isHttp) {
            return DefaultMediaSourceFactory(appContext, sharedExtractorsFactory)
                .createMediaSource(mediaItem)
        }

        val userInfo = sourceUri.userInfo
        val isLocalhost = sourceUri.host == "127.0.0.1" || sourceUri.host == "localhost"
        isTorrentStream = isLocalhost
        val okHttpFactory = OkHttpDataSource.Factory(getOrCreateOkHttpClient(isLocalhost))
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

        if (!userInfo.isNullOrEmpty() && userInfo.contains(':')) {
            val authorization = "Basic " + Base64.getEncoder()
                .encodeToString(userInfo.toByteArray(Charsets.UTF_8))
            okHttpFactory.setDefaultRequestProperties(mapOf("Authorization" to authorization))
        }

        val urlLower = sourceUrl.lowercase(Locale.US)
        val isHls = urlLower.contains(".m3u8") || urlLower.contains("/hls") ||
            urlLower.contains("/playlist")
        val isDash = urlLower.contains(".mpd") || urlLower.contains("/dash")

        // DefaultMediaSourceFactory handles SubtitleConfigurations natively,
        // so for non-HLS/non-DASH (e.g. MKV) pass the full mediaItem directly.
        if (!isHls && !isDash) {
            val handler = assHandler
            val factory = if (playbackSettings.assRendererEnabled && handler != null) {
                DefaultMediaSourceFactory(okHttpFactory, AssExtractorsFactory(handler))
            } else {
                DefaultMediaSourceFactory(okHttpFactory)
            }
            return factory.createMediaSource(mediaItem)
        }

        // HLS/DASH factories don't support SubtitleConfigurations on the MediaItem,
        // so we strip them and merge sidecar sources manually.
        val subtitleConfigs = mediaItem.localConfiguration
            ?.subtitleConfigurations.orEmpty()

        val mainMediaItem = if (subtitleConfigs.isNotEmpty()) {
            mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
        } else {
            mediaItem
        }

        val mainSource = if (isHls) {
            HlsMediaSource.Factory(okHttpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mainMediaItem)
        } else {
            DashMediaSource.Factory(okHttpFactory)
                .createMediaSource(mainMediaItem)
        }

        if (subtitleConfigs.isEmpty()) return mainSource

        val subtitleSources = subtitleConfigs.map { config ->
            SingleSampleMediaSource.Factory(okHttpFactory)
                .createMediaSource(config, C.TIME_UNSET)
        }
        return MergingMediaSource(mainSource, *subtitleSources.toTypedArray())
    }

    private var torrentOkHttpClient: OkHttpClient? = null

    private fun getOrCreateOkHttpClient(isLocalhost: Boolean = false): OkHttpClient {
        if (isLocalhost) {
            return torrentOkHttpClient ?: OkHttpClient.Builder()
                .connectTimeout(8000, TimeUnit.MILLISECONDS)
                .readTimeout(120_000, TimeUnit.MILLISECONDS)
                .connectionPool(SHARED_PLAYBACK_CONNECTION_POOL)
                .retryOnConnectionFailure(true)
                .apply {
                    if (diagnosticsSessionId.isNotBlank()) {
                        addNetworkInterceptor(::diagnosticNetworkRequest)
                    }
                }
                .build()
                .also { torrentOkHttpClient = it }
        }
        return okHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(8000, TimeUnit.MILLISECONDS)
            .readTimeout(8000, TimeUnit.MILLISECONDS)
            .connectionPool(SHARED_PLAYBACK_CONNECTION_POOL)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                if (diagnosticsSessionId.isNotBlank()) {
                    addNetworkInterceptor(::diagnosticNetworkRequest)
                }
            }
            .build()
            .also { okHttpClient = it }
    }

    private fun diagnosticNetworkRequest(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val response = chain.proceed(chain.request())
            val now = SystemClock.elapsedRealtime()
            val last = lastNetworkDiagnosticElapsedMs.get()
            if (!response.isSuccessful ||
                now - last >= 10_000L && lastNetworkDiagnosticElapsedMs.compareAndSet(last, now)
            ) {
                diagnostic(
                    "Network Response",
                    "source=${PlaybackDiagnostics.describeUrl(response.request.url.toString())} " +
                        "status=${response.code} latency=${now - startedAt}ms " +
                        "range=${response.header("Content-Range") ?: response.header("Accept-Ranges") ?: "unknown"} " +
                        "length=${response.header("Content-Length") ?: "unknown"}",
                    if (response.isSuccessful) "INFO" else "WARN"
                )
            }
            response
        } catch (error: java.io.IOException) {
            diagnostic(
                "Network Failure",
                "source=${PlaybackDiagnostics.describeUrl(chain.request().url.toString())} " +
                    "after=${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "${error::class.java.simpleName}: ${error.message.orEmpty().take(160)}",
                "ERROR"
            )
            throw error
        }
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                updateProgressState()
                // Player controls display whole seconds. A 1 Hz update halves
                // full-screen Compose invalidations without reducing seek input
                // responsiveness, which is handled by its own immediate state.
                delay(1_000L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateProgressLoopState() {
        val player = exoPlayer
        val shouldRun = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (shouldRun) startProgressLoop() else stopProgressLoop()
    }


    private fun updateProgressState() {
        val player = exoPlayer ?: return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val buffered = player.bufferedPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed

        _uiState.update {
            it.copy(
                durationMs = duration,
                positionMs = position,
                bufferedPositionMs = if (player.isPlaying) buffered else it.bufferedPositionMs,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                isReady = player.playbackState == Player.STATE_READY,
                playbackSpeed = speed,
                currentSourceId = currentSourceId ?: it.currentSourceId
            )
        }

        if (bufferRecoveryPolicy.shouldRecover(
                nowMs = SystemClock.elapsedRealtime(),
                positionMs = position,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                playWhenReady = player.playWhenReady
            )
        ) {
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.seekTo((position - 750L).coerceAtLeast(0L))
            player.prepare()
        }
    }

    private fun beginSeekFeedback() {
        seekFeedbackJob?.cancel()
        transitionReliability(PlaybackReliabilityPhase.SEEKING)
        _uiState.update { it.copy(isSeeking = true, isEnded = false, errorMessage = null) }
        seekFeedbackJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            do {
                delay(SEEK_INDICATOR_POLL_MS)
                val minimumVisible = SystemClock.elapsedRealtime() - startedAt < MIN_SEEK_INDICATOR_MS
                val stillBuffering = exoPlayer?.playbackState == Player.STATE_BUFFERING
                if (!minimumVisible && !stillBuffering) break
            } while (isActive)
            val finalPhase = if (exoPlayer?.playbackState == Player.STATE_BUFFERING) {
                PlaybackReliabilityPhase.BUFFERING
            } else {
                PlaybackReliabilityPhase.READY
            }
            transitionReliability(finalPhase)
            _uiState.update { it.copy(isSeeking = false) }
        }
    }

    private fun attemptAutoRetryFromIoError(error: PlaybackException): Boolean {
        if (!isRecoverableError(error)) return false

        val player = exoPlayer ?: return false
        val sourceId = currentSourceId ?: return false
        if (ioAutoRetrySourceId != sourceId) {
            ioAutoRetrySourceId = sourceId
            ioAutoRetryCountForCurrentSource = 0
        }

        val isAudioError = isAudioTrackError(error)
        val maxRetries = if (isAudioError) MAX_AUDIO_AUTO_RETRIES_PER_SOURCE else MAX_IO_AUTO_RETRIES_PER_SOURCE
        if (ioAutoRetryCountForCurrentSource >= maxRetries) {
            return false
        }

        val source = _sourceOptions.value.firstOrNull { option -> option.id == sourceId } ?: return false
        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val shouldAutoPlay = player.playWhenReady

        ioAutoRetryCountForCurrentSource += 1
        reliabilityStateMachine.retryCurrent()
        transitionReliability(PlaybackReliabilityPhase.RETRYING)
        val tokenAtRetry = loadToken
        val retryDelay = if (isAudioError) AUDIO_AUTO_RETRY_DELAY_MS else IO_AUTO_RETRY_DELAY_MS
        _uiState.update {
            it.copy(
                errorMessage = "Reconnecting...",
                isBuffering = true
            )
        }

        scope.launch {
            delay(retryDelay)
            if (released || loadToken != tokenAtRetry) return@launch
            prepareSource(
                source = source,
                startPositionMs = resumePosition,
                autoPlay = shouldAutoPlay,
                resetSourceRetryBudget = false
            )
        }
        return true
    }

    private fun isParsingErrorNearEnd(error: PlaybackException): Boolean {
        val codeName = error.errorCodeName.uppercase(Locale.US)
        if (!codeName.contains("PARSING")) return false
        val player = exoPlayer ?: return false
        val duration = player.duration.takeIf { it > 0 } ?: _uiState.value.durationMs
        val position = player.currentPosition.takeIf { it > 0 } ?: _uiState.value.positionMs
        if (duration <= 0) return false
        val ratio = position.toDouble() / duration.toDouble()
        return ratio >= 0.80
    }

    private fun isAudioTrackError(error: PlaybackException): Boolean {
        val codeName = error.errorCodeName.uppercase(Locale.US)
        return codeName == "ERROR_CODE_AUDIO_TRACK_INIT_FAILED" ||
            codeName == "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED"
    }

    private fun isRecoverableError(error: PlaybackException): Boolean {
        val codeName = error.errorCodeName.uppercase(Locale.US)

        if (codeName == "ERROR_CODE_AUDIO_TRACK_INIT_FAILED" ||
            codeName == "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED"
        ) {
            return true
        }

        if (!codeName.startsWith("ERROR_CODE_IO_")) return false
        if (codeName == "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED") return false
        if (codeName == "ERROR_CODE_IO_NO_PERMISSION") return false
        if (codeName == "ERROR_CODE_IO_FILE_NOT_FOUND") return false
        return true
    }

    private fun retryFromStartAfter416(error: PlaybackException): Boolean {
        if (hasRetriedCurrentSourceAfter416) return false
        val httpCause = error.cause as? HttpDataSource.InvalidResponseCodeException ?: return false
        if (httpCause.responseCode != 416) return false

        hasRetriedCurrentSourceAfter416 = true
        reliabilityStateMachine.retryCurrent()
        transitionReliability(PlaybackReliabilityPhase.RETRYING)
        val player = exoPlayer ?: return false
        val source = _sourceOptions.value.firstOrNull { it.id == currentSourceId } ?: return false

        _uiState.update { it.copy(errorMessage = null, isBuffering = true) }

        runCatching {
            val mediaItem = MediaItem.Builder()
                .setUri(source.url)
                .build()
            val mediaSource = createMediaSource(source.url, mediaItem)
            player.stop()
            player.clearMediaItems()
            player.setMediaSource(mediaSource)
            pendingStartPositionMs = 0L
            player.seekTo(0L)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    errorMessage = e.message ?: "Playback error",
                    isBuffering = false
                )
            }
        }
        return true
    }

    private fun attemptAutomaticSourceFallback(error: PlaybackException): Boolean {
        if (!isRecoverableError(error)) return false
        val player = exoPlayer ?: return false
        val fallbackId = reliabilityStateMachine.nextFallback(
            _sourceOptions.value
                .filter {
                    it.url.startsWith("https://") || it.url.startsWith("http://") ||
                        (it.url.startsWith("magnet:") && onMagnetSourceSelected != null)
                }
                .map(PlayerSourceOption::id)
        ) ?: return false
        val fallback = _sourceOptions.value.firstOrNull { it.id == fallbackId } ?: return false
        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val shouldAutoPlay = player.playWhenReady

        currentSourceId = fallback.id
        pendingAudioTrackId = null
        pendingSubtitleTrackId = null
        hasAppliedAudioLanguagePref = false
        hasAppliedSubtitleLanguagePref = false
        loadToken++
        transitionReliability(PlaybackReliabilityPhase.FALLING_BACK)
        diagnostic(
            "Source Fallback",
            "failed=${error.errorCodeName} next=${fallback.id.take(80)} resume=${resumePosition}ms",
            "WARN"
        )
        _uiState.update {
            it.copy(errorMessage = "Trying the next best source…", isBuffering = true)
        }
        if (fallback.url.startsWith("magnet:")) {
            val handler = onMagnetSourceSelected ?: return false
            handler.invoke(
                fallback.url,
                fallback.fileIdx,
                fallback.fileName,
                { resolvedMagnetUrl, localUrl ->
                    if (released) return@invoke
                    val resolvedOption = _sourceOptions.value.firstOrNull { it.id == fallback.id } ?: fallback
                    currentSourceId = resolvedOption.id
                    prepareSource(
                        source = resolvedOption.copy(url = localUrl),
                        startPositionMs = resumePosition,
                        autoPlay = shouldAutoPlay,
                        resetSourceRetryBudget = true
                    )
                },
                { message ->
                    if (released) return@invoke
                    transitionReliability(PlaybackReliabilityPhase.FAILED)
                    _uiState.update {
                        it.copy(
                            errorMessage = message.ifBlank { "Backup sources could not be played" },
                            isBuffering = false,
                            isSeeking = false
                        )
                    }
                }
            )
            return true
        }
        prepareSource(
            source = fallback,
            startPositionMs = resumePosition,
            autoPlay = shouldAutoPlay,
            resetSourceRetryBudget = true
        )
        return true
    }

    private fun transitionReliability(phase: PlaybackReliabilityPhase) {
        reliabilityStateMachine.transition(phase)
        _uiState.update { it.copy(reliabilityPhase = phase.name.lowercase(Locale.US)) }
    }

    private fun scheduleAudioSwitchRecovery(player: ExoPlayer) {
        audioSwitchRecoveryJob?.cancel()
        val tokenAtSwitch = loadToken
        audioSwitchRecoveryJob = scope.launch {
            delay(AUDIO_SWITCH_RECOVERY_DELAY_MS)
            if (released || loadToken != tokenAtSwitch) return@launch
            val livePlayer = exoPlayer ?: return@launch
            if (livePlayer !== player) return@launch
            if (!livePlayer.playWhenReady) return@launch

            val stalledAfterSwitch = livePlayer.playbackState == Player.STATE_BUFFERING
            if (!stalledAfterSwitch) return@launch

            val position = livePlayer.currentPosition.coerceAtLeast(0L)
            val duration = livePlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val target = if (position > AUDIO_SWITCH_RECOVERY_SEEK_BACK_MS) {
                position - AUDIO_SWITCH_RECOVERY_SEEK_BACK_MS
            } else {
                (position + AUDIO_SWITCH_RECOVERY_SEEK_BACK_MS).coerceAtMost(duration)
            }

            livePlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            livePlayer.seekTo(target)
        }
    }

    private fun refreshTrackOptions(tracks: Tracks) {
        audioTrackLocators.clear()
        subtitleTrackLocators.clear()

        val audioOptions = mutableListOf<PlayerTrackOption>()
        var selectedAudioId: String? = null
        val subtitleOptionsRaw = mutableListOf<PlayerTrackOption>()
        var selectedSubtitleId: String? = null

        tracks.groups.forEachIndexed { groupIndex, group ->
            val trackType = group.type
            val mediaTrackGroup = group.mediaTrackGroup
            for (trackIndex in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(trackIndex)
                val isSupported = group.isTrackSupported(trackIndex)
                when (trackType) {
                    C.TRACK_TYPE_AUDIO -> {
                        if (!isSupported) continue
                        val id = stableTrackId("a", groupIndex, trackIndex, format.id)
                        val selected = group.isTrackSelected(trackIndex)
                        audioTrackLocators[id] = TrackLocator(mediaTrackGroup, trackIndex)
                        if (selected) selectedAudioId = id
                        audioOptions += PlayerTrackOption(
                            id = id,
                            label = buildAudioTrackLabel(format, audioOptions.size + 1),
                            language = format.language,
                            selected = selected,
                            audioFormat = inferAudioFormatTag(format)
                        )
                    }

                    C.TRACK_TYPE_TEXT -> {
                        val rawId = stableTrackId("s", groupIndex, trackIndex, format.id)
                        // SubtitleConfiguration.id is carried into Format.id.
                        // Language/label matching can misclassify an embedded
                        // "English" track as an external "English" sidecar.
                        val matchedExternalId = matchExternalSubtitleTrackId(format.id, externalSubtitleSources.keys)
                        val id = matchedExternalId ?: rawId
                        val selected = group.isTrackSelected(trackIndex)
                        val externalSubtitle = externalSubtitleSources[id]
                        subtitleTrackLocators[id] = TrackLocator(mediaTrackGroup, trackIndex)
                        if (selected) selectedSubtitleId = id
                        subtitleOptionsRaw += PlayerTrackOption(
                            id = id,
                            label = externalSubtitle?.label?.takeIf { it.isNotBlank() } ?: buildSubtitleTrackLabel(
                                format = format,
                                fallbackIndex = subtitleOptionsRaw.size + 1
                            ),
                            language = externalSubtitle?.language ?: format.language,
                            selected = selected,
                            supported = isSupported,
                            isExternal = externalSubtitle != null,
                            subtitleSourcePriority = externalSubtitle?.sourcePriority
                                ?: SubtitleSourcePriority.EMBEDDED,
                            roleFlags = format.roleFlags or (externalSubtitle?.let(::inferExternalSubtitleRoleFlags)
                                ?: 0),
                            selectionFlags = format.selectionFlags or (externalSubtitle
                                ?.let(::inferExternalSubtitleSelectionFlags) ?: 0),
                            subtitleFormat = resolveSubtitleFormatTag(
                                optionId = id,
                                format = format,
                                externalUrl = externalSubtitle?.url
                            )
                        )
                    }
                }
            }
        }

        val mergedSubtitleOptions = subtitleOptionsRaw.toMutableList()
        externalSubtitleSources.forEach { (stableId, subtitleSource) ->
            if (mergedSubtitleOptions.none { option -> option.id == stableId }) {
                mergedSubtitleOptions += PlayerTrackOption(
                    id = stableId,
                    label = buildExternalSubtitleLabel(subtitleSource, mergedSubtitleOptions.size + 1),
                    language = subtitleSource.language,
                    supported = true,
                    isExternal = true,
                    subtitleSourcePriority = subtitleSource.sourcePriority,
                    roleFlags = inferExternalSubtitleRoleFlags(subtitleSource),
                    selectionFlags = inferExternalSubtitleSelectionFlags(subtitleSource),
                    subtitleFormat = inferSubtitleFormatTagFromUrl(subtitleSource.url)
                )
            }
        }

        val optionById = mergedSubtitleOptions.associateBy { option -> option.id }
        val availableSubtitleIds = optionById.keys
        val selectableSubtitleIds = optionById
            .asSequence()
            .filter { (_, option) -> option.supported }
            .map { (id, _) -> id }
            .toSet()
        val previousSelectedSubtitleId = _uiState.value.selectedSubtitleTrackId
        val resolvedSelectedSubtitleId = when {
            previousSelectedSubtitleId == SUBTITLE_OFF_ID -> SUBTITLE_OFF_ID
            selectedSubtitleId != null && selectedSubtitleId in selectableSubtitleIds -> selectedSubtitleId
            previousSelectedSubtitleId != null && previousSelectedSubtitleId in selectableSubtitleIds -> {
                previousSelectedSubtitleId
            }
            forcedSubtitleTrackId != null && forcedSubtitleTrackId in selectableSubtitleIds -> {
                forcedSubtitleTrackId
            }
            else -> SUBTITLE_OFF_ID
        }

        _audioTracks.value = audioOptions
        _subtitleTracks.value = buildList {
            add(
                PlayerTrackOption(
                    id = SUBTITLE_OFF_ID,
                    label = "Off",
                    selected = resolvedSelectedSubtitleId == SUBTITLE_OFF_ID
                )
            )
            mergedSubtitleOptions.forEach { option ->
                add(option.copy(selected = option.id == resolvedSelectedSubtitleId))
            }
        }

        _uiState.update {
            it.copy(
                selectedAudioTrackId = selectedAudioId ?: it.selectedAudioTrackId,
                selectedSubtitleTrackId = resolvedSelectedSubtitleId
            )
        }
    }


    private fun applyPendingTrackSelections() {
        val audioId = pendingAudioTrackId
        if (!audioId.isNullOrBlank()) {
            selectAudioTrack(audioId)
        } else if (!hasAppliedAudioLanguagePref) {
            val preferredId = resolvePreferredAudioTrack()
            if (preferredId != null) {
                selectAudioTrack(preferredId)
                hasAppliedAudioLanguagePref = true
            }
        }

        val subtitleId = pendingSubtitleTrackId?.takeIf { id ->
            id == SUBTITLE_OFF_ID || _subtitleTracks.value.any { it.id == id && it.supported }
        }
        manualSubtitleSelection = SubtitleSelectionPolicy.retainManualSelection(
            manualSubtitleSelection, pendingSubtitleTrackId, subtitleId
        )
        if (pendingSubtitleTrackId != null && subtitleId == null) {
            pendingSubtitleTrackId = null
        }
        val preferredId = resolvePreferredSubtitleTrack()
        val preferred = _subtitleTracks.value.firstOrNull { it.id == preferredId }
        val current = _subtitleTracks.value.firstOrNull { it.id == _uiState.value.selectedSubtitleTrackId }
        val upgradeToEmbedded = SubtitleSelectionPolicy.shouldUpgradeToEmbedded(
            manualSubtitleSelection, subtitleId, current?.id, preferred
        )
        when {
            upgradeToEmbedded -> {
                pendingSubtitleTrackId = null
                hasAppliedSubtitleLanguagePref = true
                applySubtitleTrack(preferredId)
            }
            !subtitleId.isNullOrBlank() -> applySubtitleTrack(subtitleId)
            !manualSubtitleSelection && !hasAppliedSubtitleLanguagePref && preferredId != null -> {
                hasAppliedSubtitleLanguagePref = true
                applySubtitleTrack(preferredId)
            }
        }
    }

    private fun resolvePreferredAudioTrack(): String? {
        val priorities = listOf(
            playbackSettings.preferredAudioLanguage,
            playbackSettings.preferredAudioLanguageSecondary,
            playbackSettings.preferredAudioLanguageTertiary
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val options = _audioTracks.value
        for (language in priorities) {
            findTrackByLanguage(options, language)?.let { return it.id }
        }
        return null
    }

    private fun resolvePreferredSubtitleTrack(): String? {
        val primary = playbackSettings.preferredSubtitleLanguage.trim()
        val secondary = playbackSettings.preferredSubtitleLanguageSecondary.trim()
        if (primary.isEmpty() && secondary.isEmpty()) return null

        if (primary == "#off") return SUBTITLE_OFF_ID
        if (primary.isEmpty() && secondary == "#off") return SUBTITLE_OFF_ID

        val options = _subtitleTracks.value.filter { it.id != SUBTITLE_OFF_ID }

        if (primary.isNotEmpty()) {
            val match = findSubtitleTrackByLanguage(options, primary)
            if (match != null) return match.id
        }
        if (secondary.isNotEmpty()) {
            if (secondary == "#off") return SUBTITLE_OFF_ID
            val match = findSubtitleTrackByLanguage(options, secondary)
            if (match != null) return match.id
        }
        return null
    }

    private fun findTrackByLanguage(
        tracks: List<PlayerTrackOption>,
        preferredLanguage: String
    ): PlayerTrackOption? {
        val prefKey = normalizeLanguageToIso2(preferredLanguage)
        if (prefKey == "und") return null
        return tracks.firstOrNull { track ->
            normalizeLanguageToIso2(track.language) == prefKey
        }
    }

    private fun findSubtitleTrackByLanguage(
        tracks: List<PlayerTrackOption>,
        preferredLanguage: String
    ): PlayerTrackOption? = SubtitleTrackPreference.findPreferred(tracks, preferredLanguage)

    private fun scheduleHintTrackRefresh() {
        hintTrackRefreshJob?.cancel()
        hintTrackRefreshJob = scope.launch {
            delay(120L)
            val livePlayer = exoPlayer ?: return@launch
            if (released) return@launch
            refreshTrackOptions(livePlayer.currentTracks)
        }
    }

    private fun stableTrackId(
        prefix: String,
        groupIndex: Int,
        trackIndex: Int,
        explicitId: String?
    ): String {
        val cleanExplicit = explicitId?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanExplicit != null) {
            if (cleanExplicit.startsWith("$prefix:")) cleanExplicit else "$prefix:$cleanExplicit"
        } else {
            "$prefix:g$groupIndex:t$trackIndex"
        }
    }

    private fun buildAudioTrackLabel(format: Format, fallbackIndex: Int): String {
        val label = format.label?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null) return label

        val language = format.language?.trim()?.takeIf { it.isNotEmpty() }
        if (language != null) {
            val displayLanguage = Locale.forLanguageTag(language).displayLanguage
            if (displayLanguage.isNotBlank()) return displayLanguage
            return language.uppercase(Locale.US)
        }

        return "Audio $fallbackIndex"
    }

    private fun inferAudioFormatTag(format: Format): String? {
        val sampleMime = format.sampleMimeType?.lowercase(Locale.US)
        val codecs = format.codecs?.lowercase(Locale.US)
        val label = format.label?.lowercase(Locale.US)
        val channelCount = format.channelCount

        // Determine base codec name
        val codecTag = when {
            // Dolby Digital Plus / E-AC-3 (check before AC-3)
            sampleMime == MimeTypes.AUDIO_E_AC3 ||
                sampleMime == MimeTypes.AUDIO_E_AC3_JOC ||
                codecs?.contains("ec-3") == true ||
                codecs?.contains("eac3") == true ||
                codecs?.contains("ec3") == true ||
                label?.contains("e-ac-3") == true ||
                label?.contains("eac3") == true ||
                label?.contains("dolby digital plus") == true -> "EAC3"

            // Dolby AC-3
            sampleMime == MimeTypes.AUDIO_AC3 ||
                codecs?.contains("ac-3") == true ||
                codecs?.contains("ac3") == true ||
                label?.contains("ac-3") == true ||
                label?.contains("dolby digital") == true -> "AC3"

            // Dolby AC-4
            sampleMime == MimeTypes.AUDIO_AC4 ||
                codecs?.contains("ac-4") == true ||
                codecs?.contains("ac4") == true -> "AC4"

            // Dolby TrueHD / Atmos
            sampleMime == "audio/true-hd" ||
                sampleMime == "audio/truehd" ||
                sampleMime == MimeTypes.AUDIO_TRUEHD ||
                codecs?.contains("truehd") == true ||
                codecs?.contains("mlp") == true ||
                label?.contains("truehd") == true ||
                label?.contains("atmos") == true -> "TrueHD"

            // DTS-HD Master Audio
            sampleMime == "audio/vnd.dts.hd" ||
                sampleMime == MimeTypes.AUDIO_DTS_HD ||
                codecs?.contains("dtshd") == true ||
                codecs?.contains("dts-hd") == true ||
                label?.contains("dts-hd") == true ||
                label?.contains("dts hd") == true -> "DTS-HD"

            // DTS:X
            sampleMime == "audio/vnd.dts.uhd;profile=p2" ||
                codecs?.contains("dtsx") == true ||
                codecs?.contains("dts:x") == true ||
                label?.contains("dts:x") == true ||
                label?.contains("dtsx") == true -> "DTS:X"

            // DTS (base)
            sampleMime == MimeTypes.AUDIO_DTS ||
                sampleMime == MimeTypes.AUDIO_DTS_EXPRESS ||
                codecs?.contains("dts") == true ||
                label?.contains("dts") == true -> "DTS"

            // Opus
            sampleMime == MimeTypes.AUDIO_OPUS ||
                codecs?.contains("opus") == true -> "Opus"

            // Vorbis
            sampleMime == MimeTypes.AUDIO_VORBIS ||
                codecs?.contains("vorbis") == true -> "Vorbis"

            // FLAC
            sampleMime == MimeTypes.AUDIO_FLAC ||
                sampleMime == "audio/x-flac" ||
                codecs?.contains("flac") == true ||
                codecs?.contains("fLaC") == true -> "FLAC"

            // ALAC
            sampleMime == MimeTypes.AUDIO_ALAC ||
                codecs?.contains("alac") == true -> "ALAC"

            // PCM variants
            sampleMime?.startsWith("audio/raw") == true ||
                sampleMime?.contains("pcm") == true -> "PCM"

            // AAC-HE / HE-AAC v2 (check before AAC)
            codecs?.contains("mp4a.40.5") == true ||
                codecs?.contains("mp4a.40.29") == true ||
                label?.contains("he-aac") == true -> "HE-AAC"

            // AAC
            sampleMime == MimeTypes.AUDIO_AAC ||
                codecs?.contains("mp4a") == true ||
                codecs?.contains("aac") == true -> "AAC"

            // MP3
            sampleMime == MimeTypes.AUDIO_MPEG ||
                codecs?.contains("mp3") == true ||
                codecs?.contains("mp4a.6b") == true -> "MP3"

            // WMA
            sampleMime?.contains("wma") == true ||
                sampleMime?.contains("x-ms-wma") == true -> "WMA"

            else -> null
        } ?: return null

        // Append channel layout suffix
        val channelSuffix = when {
            channelCount <= 0 || channelCount == Format.NO_VALUE -> null
            channelCount == 1 -> "Mono"
            channelCount == 2 -> "Stereo"
            channelCount in 5..6 -> "5.1"
            channelCount in 7..8 -> "7.1"
            else -> "${channelCount}ch"
        }

        return if (channelSuffix != null) "$codecTag $channelSuffix" else codecTag
    }

    private fun buildSubtitleTrackLabel(format: Format, fallbackIndex: Int): String {
        val label = format.label?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null) return label

        val language = format.language?.trim()?.takeIf { it.isNotEmpty() }
        if (language != null) {
            val displayLanguage = Locale.forLanguageTag(language).displayLanguage
            return if (displayLanguage.isNotBlank()) displayLanguage else language.uppercase(Locale.US)
        }

        return "Subtitle $fallbackIndex"
    }

    private fun inferSubtitleMimeType(url: String): String? {
        val normalized = url
            .substringBefore('?')
            .substringBefore('#')
            .lowercase(Locale.US)

        return when {
            normalized.endsWith(".vtt") || normalized.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            normalized.endsWith(".ass") || normalized.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalized.endsWith(".ttml") || normalized.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            normalized.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun resolveSubtitleFormatTag(
        optionId: String,
        format: Format,
        externalUrl: String?
    ): String? {
        subtitleFormatHintsByTrackId[optionId]?.let { return it }
        subtitleLabelLanguageKey(format.label, format.language)?.let { key ->
            subtitleFormatHintsByLabelLanguage[key]?.let { return it }
        }
        normalizedSubtitleLabelKey(format.label)?.let { key ->
            subtitleFormatHintsByLabel[key]?.let { return it }
        }
        return inferSubtitleFormatTag(
            format = format,
            externalUrl = externalUrl
        )
    }

    private fun inferSubtitleFormatTag(
        format: Format,
        externalUrl: String?
    ): String? {
        inferSubtitleFormatTagFromUrl(externalUrl)?.let { return it }

        val sampleMime = format.sampleMimeType?.lowercase(Locale.US)
        val containerMime = format.containerMimeType?.lowercase(Locale.US)
        val codecs = format.codecs?.lowercase(Locale.US)
        val label = format.label?.lowercase(Locale.US)
        val id = format.id?.lowercase(Locale.US)

        formatTagFromMime(sampleMime)?.let { return it }
        formatTagFromMime(containerMime)?.let { return it }
        formatTagFromText(codecs)?.let { return it }
        formatTagFromText(label)?.let { return it }
        formatTagFromText(id)?.let { return it }

        // If this is an external subtitle and URL doesn't reveal extension,
        // fallback to the same mime heuristic used when we build SubtitleConfiguration.
        if (!externalUrl.isNullOrBlank()) {
            formatTagFromMime(inferSubtitleMimeType(externalUrl))?.let { return it }
        }

        // Embedded tracks often expose vendor-specific mime/codec values.
        // Show a compact generic subtype tag instead of dropping the format badge.
        genericFormatTag(sampleMime)?.let { return it }
        genericFormatTag(containerMime)?.let { return it }
        genericFormatTag(codecs)?.let { return it }
        return null
    }

    private fun inferSubtitleFormatTagFromUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedRaw = raw.lowercase(Locale.US)

        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase(Locale.US)
        val query = uri?.query.orEmpty().lowercase(Locale.US)
        val joined = "$path?$query"

        return formatTagFromText(normalizedRaw)
            ?: formatTagFromText(joined)
    }

    private fun formatTagFromMime(mimeType: String?): String? {
        val mime = mimeType?.lowercase(Locale.US)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            mime == MimeTypes.APPLICATION_SUBRIP || mime.contains("subrip") -> "SRT"
            mime == MimeTypes.TEXT_VTT || mime == "application/x-mp4-vtt" || mime == "application/mp4vtt" || mime.contains("webvtt") || mime.contains("wvtt") || mime.endsWith("/vtt") -> "VTT"
            mime == MimeTypes.TEXT_SSA || mime == "text/x-ssa" || mime == "text/x-ass" || mime.contains("ssa") || mime.contains("ass") -> "ASS"
            mime == MimeTypes.APPLICATION_TTML || mime.contains("ttml") || mime.contains("dfxp") -> "TTML"
            mime == "application/pgs" || mime.contains("pgs") || mime.contains("sup") || mime.contains("hdmv") -> "PGS"
            mime == "application/dvbsubs" || mime.contains("dvbsub") -> "DVB"
            mime.contains("dvd_subtitle") || mime.contains("vobsub") -> "VOBSUB"
            mime == "application/x-quicktime-tx3g" || mime.contains("tx3g") -> "TX3G"
            mime == "application/cea-608" || mime.contains("cea-608") || mime.contains("cea608") -> "CEA-608"
            mime == "application/cea-708" || mime.contains("cea-708") || mime.contains("cea708") -> "CEA-708"
            mime.contains("sub") -> "SUB"
            else -> null
        }
    }

    private fun formatTagFromText(text: String?): String? {
        val value = text?.lowercase(Locale.US)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.contains("media3_cues")) return null
        return when {
            value.contains(".srt") || value.contains("subrip") || value.contains("format=srt") -> "SRT"
            value.contains(".vtt") || value.contains(".webvtt") || value.contains("webvtt") || value.contains("wvtt") || value.contains("format=vtt") -> "VTT"
            value.contains(".ass") || value.contains(".ssa") || value.contains("s_text/ass") || value.contains("s_text/ssa") || value.contains("format=ass") || value.contains("format=ssa") -> "ASS"
            value.contains("s_text/utf8") || value.contains("srt") -> "SRT"
            value.contains(".ttml") || value.contains(".dfxp") || value.contains("stpp") || value.contains("format=ttml") -> "TTML"
            value.contains(".pgs") || value.contains(".sup") || value.contains("pgs") || value.contains("hdmv") || value.contains("format=pgs") -> "PGS"
            value.contains("dvbsub") || value.contains("dvbsubs") -> "DVB"
            value.contains("dvd_subtitle") || value.contains("vobsub") -> "VOBSUB"
            value.contains("tx3g") -> "TX3G"
            value.contains("cea-608") || value.contains("cea608") -> "CEA-608"
            value.contains("cea-708") || value.contains("cea708") -> "CEA-708"
            value.contains(".sub") || value.contains(" format=sub") || value.contains("format=sub") -> "SUB"
            else -> null
        }
    }

    private fun genericFormatTag(rawValue: String?): String? {
        val value = rawValue?.lowercase(Locale.US)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Reject container MIME types – they describe the file wrapper, not the subtitle codec.
        if (
            value.startsWith("video/") ||
            value.startsWith("audio/") ||
            value.startsWith("application/x-matroska") ||
            value.contains("matroska") ||
            value.contains("webm") ||
            value.contains("mp4")
        ) {
            return null
        }
        val tail = value.substringAfterLast('/').substringBefore(';').substringBefore('+').trim()
        if (tail.isBlank()) return null
        val normalized = tail
            .removePrefix("x-")
            .replace('-', '_')
            .uppercase(Locale.US)
        if (
            normalized == "MEDIA3_CUES" ||
            normalized == "CUES" ||
            normalized == "TEXT" ||
            normalized == "UNKNOWN"
        ) {
            return null
        }
        return normalized.takeIf { it.length in 2..14 }
    }

    private fun normalizeSubtitleSelectionId(rawId: String?): String? {
        val cleanId = rawId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (cleanId == SUBTITLE_OFF_ID) return SUBTITLE_OFF_ID
        if (cleanId in externalSubtitleSources.keys) return cleanId

        val stableId = externalSubtitleTrackId(cleanId)
        return if (stableId in externalSubtitleSources.keys) stableId else cleanId
    }

    private fun buildExternalSubtitleLabel(
        subtitleSource: PlayerSubtitleSource,
        fallbackIndex: Int
    ): String {
        val cleanLabel = subtitleSource.label.trim().takeIf { it.isNotEmpty() }
        if (cleanLabel != null) return cleanLabel

        val language = subtitleSource.language?.trim()?.takeIf { it.isNotEmpty() }
        if (language != null) {
            val displayLanguage = Locale.forLanguageTag(language).displayLanguage
            if (displayLanguage.isNotBlank()) return displayLanguage
            return language.uppercase(Locale.US)
        }

        return "Subtitle $fallbackIndex"
    }

    private fun inferExternalSubtitleRoleFlags(subtitleSource: PlayerSubtitleSource): Int {
        val label = subtitleSource.label.lowercase(Locale.ROOT)
        var flags = 0
        if (
            label.contains("sdh") ||
            label.contains("cc") ||
            label.contains("hearing impaired")
        ) {
            flags = flags or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_CAPTION
        }
        return flags
    }

    private fun inferExternalSubtitleSelectionFlags(subtitleSource: PlayerSubtitleSource): Int {
        val label = subtitleSource.label.lowercase(Locale.ROOT)
        return if (label.contains("forced")) C.SELECTION_FLAG_FORCED else 0
    }

    private fun normalizedSubtitleLabelKey(rawLabel: String?): String? {
        return rawLabel
            ?.lowercase(Locale.ROOT)
            ?.replace(BRACKET_CONTENT_REGEX, " ")
            ?.replace(NON_LETTER_DIGIT_REGEX, " ")
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalizedSubtitleLanguageKey(rawLanguage: String?): String? {
        return rawLanguage
            ?.trim()
            ?.replace('_', '-')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?.substringBefore('-')
    }

    private fun subtitleLanguagesRoughlyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.startsWith(b) || b.startsWith(a)) return true
        val aLocale = Locale.forLanguageTag(a).language
        val bLocale = Locale.forLanguageTag(b).language
        return aLocale.isNotBlank() && bLocale.isNotBlank() && aLocale == bLocale
    }

    private fun subtitleLabelLanguageKey(label: String?, language: String?): String? {
        val labelKey = normalizedSubtitleLabelKey(label) ?: return null
        val languageKey = normalizedSubtitleLanguageKey(language) ?: "und"
        return "$labelKey|$languageKey"
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class SubtitleDelayRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: androidx.media3.exoplayer.text.TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val startIndex = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleDelayRenderer(out[index], subtitleDelayUsProvider)
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class SubtitleDelayRenderer(
    renderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long
) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}
