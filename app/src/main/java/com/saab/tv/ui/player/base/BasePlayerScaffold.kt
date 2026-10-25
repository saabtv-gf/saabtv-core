package com.saab.tv.ui.player.base

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.saab.tv.R
import com.saab.tv.ui.components.SaabMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.tmdb.TmdbCastInfo
import com.saab.tv.ui.components.FocusShelf
import com.saab.tv.domain.episodeMatchesPlaybackId
import com.saab.tv.data.torrent.TorrentProgress
import com.saab.tv.ui.details.GlassSidebar
import com.saab.tv.ui.details.GlassSidebarScaffold
import com.saab.tv.ui.details.SidebarState
import com.saab.tv.ui.navigation.isBackOrEscape
import com.saab.tv.ui.navigation.isInitialBackPress
import java.text.Collator
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val SEEK_OVERLAY_AUTO_HIDE_MS = 1_500L
private const val PAUSE_OVERLAY_IDLE_MS = 10_000L
private const val SUBTITLE_OFF_TRACK_ID = "#none"

private enum class PlayerPanel {
    NONE,
    SOURCES,
    AUDIO,
    SUBTITLES,
    EPISODES,
    CAST
}

private data class PanelItem(
    val id: String,
    val title: String,
    val subtitle: String? = null
)

private data class PlayerHeaderInfo(
    val primaryText: String,
    val secondaryText: String?
)

internal data class SeekPreviewFrame(
    val positionMs: Long?,
    val bitmap: Bitmap?,
    val cacheKey: String = ""
)

private data class SubtitleLanguageGroup(
    val key: String,
    val displayName: String,
    val tracks: List<PlayerTrackOption>,
    val isOffGroup: Boolean = false
)

internal data class AudioLanguageGroup(
    val key: String,
    val displayName: String,
    val tracks: List<PlayerTrackOption>
)

private val seriesEpisodePattern = Regex(
    pattern = """^\s*[Ss]\s*(\d+)\s*[:x]?\s*[Ee]\s*(\d+)\s*-\s*(.+)$"""
)


@Composable
fun BasePlayerScaffold(
    playbackController: PlayerPlaybackController,
    renderSurface: PlayerRenderSurface,
    seekThumbnailProvider: (suspend (positionMs: Long) -> Bitmap?)? = null,
    seekThumbnailCacheKey: String = "",
    seekTimeIntervalSeconds: Int = 30,
    seekThumbnailCachePercent: Int? = null,
    seekThumbnailCachedFrames: Int? = null,
    seekThumbnailTotalFrames: Int? = null,
    smartFallbackFrameProvider: (suspend (Long) -> Bitmap?)? = null,
    title: String,
    mediaType: String,
    castMembers: List<TmdbCastInfo> = emptyList(),
    isLoadingCast: Boolean = false,
    onLoadCast: () -> Unit = {},
    seriesTitle: String? = null,
    logoUrl: String? = null,
    onBack: () -> Unit,
    preferredAudioLanguages: List<String> = emptyList(),
    skipSegmentInfo: SkipSegmentInfo? = null,
    nextEpisodeInfo: NextEpisodeInfo? = null,
    onAutoplayNextEpisode: ((currentSourceUrl: String?, positionMs: Long, durationMs: Long?) -> Unit)? = null,
    autoplayEnabled: Boolean = true,
    skipIntroEnabled: Boolean = true,
    skipRecapEnabled: Boolean = true,
    autoSkipIntro: Boolean = true,
    introSkipCountdownSeconds: Int = 5,
    outroSkipCountdownSeconds: Int = 5,
    onSeekPreviewPosition: (Long) -> Unit = {},
    onSourceChosen: (PlayerSourceOption) -> Unit = {},
    autoplayThresholdMode: String = "introdb",
    autoplayThresholdPercent: Int = 95,
    autoplayThresholdSeconds: Int = 30,
    episodes: List<MetaVideo> = emptyList(),
    currentPlaybackId: String? = null,
    onEpisodeSelected: ((episode: MetaVideo, currentSourceUrl: String?, positionMs: Long, durationMs: Long?) -> Unit)? = null,
    episodeSwitchSources: List<PlayerSourceOption>? = null,
    isEpisodeSwitchLoading: Boolean = false,
    episodeSwitchTitle: String? = null,
    onEpisodeSwitchSourceSelected: ((sourceUrl: String) -> Unit)? = null,
    onEpisodeSwitchDismissed: (() -> Unit)? = null,
    torrentProgress: TorrentProgress? = null,
    isTrailer: Boolean = false,
    diagnosticsSessionId: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by playbackController.uiState.collectAsStateWithLifecycle()
    val seekTimeIntervalMs = SeekIntervalPolicy.intervalMs(seekTimeIntervalSeconds)
    val sources by playbackController.sourceOptions.collectAsStateWithLifecycle()
    val audioTracks by playbackController.audioTracks.collectAsStateWithLifecycle()
    val subtitleTracks by playbackController.subtitleTracks.collectAsStateWithLifecycle()

    val currentSourceUrl = sources.firstOrNull { it.id == uiState.currentSourceId }?.url
    val nextSource = remember(sources, uiState.currentSourceId) {
        nextSourceAfter(sources, uiState.currentSourceId)
    }

    val containerFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val seekBarFocusRequester = remember { FocusRequester() }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    val skipIntroFocusRequester = remember { FocusRequester() }
    val playNextFocusRequester = remember { FocusRequester() }
    val castBackFocusRequester = remember { FocusRequester() }
    val firstCastFocusRequester = remember { FocusRequester() }

    var activePanel by remember { mutableStateOf(PlayerPanel.NONE) }
    var showControls by remember { mutableStateOf(true) }
    var showSeekOverlay by remember { mutableStateOf(false) }
    var showPauseOverlay by remember { mutableStateOf(false) }
    var showSubtitleOffsetBar by remember { mutableStateOf(false) }
    var showSubtitleSizeBar by remember { mutableStateOf(false) }
    var showSubtitleDelayBar by remember { mutableStateOf(false) }
    var showSubtitleColorBar by remember { mutableStateOf(false) }
    var pendingPreviewSeekPosition by remember { mutableStateOf<Long?>(null) }
    var seekPreviewFrames by remember(playbackController) {
        mutableStateOf<List<SeekPreviewFrame>>(emptyList())
    }
    val latestSeekPreviewFrames by rememberUpdatedState(seekPreviewFrames)
    var resumeAfterPreviewSeek by remember(playbackController) { mutableStateOf(false) }
    val latestSeekThumbnailProvider by rememberUpdatedState(seekThumbnailProvider)
    var hideControlsSignal by remember { mutableIntStateOf(0) }
    var hideSeekOverlaySignal by remember { mutableIntStateOf(0) }
    var interactionSignal by remember { mutableIntStateOf(0) }

    LaunchedEffect(seekThumbnailProvider) {
        if (seekThumbnailProvider == null) {
            seekPreviewFrames.forEach { frame ->
                val bitmap = frame.bitmap
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
            seekPreviewFrames = emptyList()
        }
    }

    val hasError = !uiState.errorMessage.isNullOrBlank()

    // --- Autoplay next episode (reset when playback controller changes, i.e. new episode) ---
    var autoplayCancelled by remember(playbackController) { mutableStateOf(false) }
    var introSkipCancelled by remember(playbackController) { mutableStateOf(false) }
    var recapSkipCancelled by remember(playbackController) { mutableStateOf(false) }
    var introSkipped by remember(playbackController) { mutableStateOf(false) }
    var recapSkipped by remember(playbackController) { mutableStateOf(false) }
    val introSkipProgress = remember(playbackController) { Animatable(0f) }
    val recapSkipProgress = remember(playbackController) { Animatable(0f) }
    val nextEpisodeProgress = remember(playbackController) { Animatable(0f) }
    var countdownActive by remember(playbackController) { mutableStateOf(false) }
    var nextEpisodeTriggered by remember(playbackController) { mutableStateOf(false) }

    val triggerNextEpisode = {
        if (!nextEpisodeTriggered && onAutoplayNextEpisode != null) {
            val details = "positionMs=${uiState.positionMs} durationMs=${uiState.durationMs} " +
                "sourceAvailable=${currentSourceUrl != null} ended=${uiState.isEnded}"
            val component = if (autoplayThresholdMode == "smart") "Smart Next Episode" else "Autoplay Next Episode"
            com.saab.tv.AppDiagnostics.event(component, "Play Next Activated", details)
            com.saab.tv.data.player.PlaybackDiagnostics.event(
                context = context,
                sessionId = diagnosticsSessionId,
                component = component,
                event = "Play Next Activated",
                details = details
            )
            nextEpisodeTriggered = true
            countdownActive = false
            onAutoplayNextEpisode.invoke(
                currentSourceUrl,
                uiState.positionMs.coerceAtLeast(0L),
                uiState.durationMs.takeIf { it > 0L }
            )
        }
    }

    val validOutro = skipSegmentInfo?.outroStartMs?.let { it > 0L && it < uiState.durationMs } == true
    val smartFallbackGateReason = SmartCreditsPolicy.scanGateReason(
        autoplayEnabled = autoplayEnabled,
        thresholdMode = autoplayThresholdMode,
        hasValidOutro = validOutro,
        hasNextEpisode = nextEpisodeInfo != null,
        hasTransitionCallback = onAutoplayNextEpisode != null,
        isTrailer = isTrailer,
        hasPlaybackError = hasError,
        hasFrameProvider = seekThumbnailProvider != null,
        durationMs = uiState.durationMs
    )
    val smartFallbackEnabled = smartFallbackGateReason == "eligible"
    val smartPromptMs = rememberSmartCreditsPrompt(
        enabled = smartFallbackEnabled,
        cacheKey = seekThumbnailCacheKey,
        durationMs = uiState.durationMs,
        intervalSeconds = seekTimeIntervalSeconds,
        provider = seekThumbnailProvider,
        cachedFrames = seekThumbnailCachedFrames,
        totalFrames = seekThumbnailTotalFrames,
        requestMissingFrame = onSeekPreviewPosition,
        diagnosticsSessionId = diagnosticsSessionId
    )
    LaunchedEffect(
        diagnosticsSessionId,
        smartFallbackEnabled,
        smartFallbackGateReason,
        seekTimeIntervalSeconds,
        uiState.durationMs,
        nextEpisodeInfo != null
    ) {
        if (autoplayThresholdMode != "smart") return@LaunchedEffect
        val details = "eligible=$smartFallbackEnabled reason=$smartFallbackGateReason " +
            "durationMs=${uiState.durationMs} intervalSeconds=$seekTimeIntervalSeconds " +
            "frameProvider=${seekThumbnailProvider != null} nextEpisode=${nextEpisodeInfo != null} " +
            "transitionCallback=${onAutoplayNextEpisode != null}"
        com.saab.tv.AppDiagnostics.event("Smart Next Episode", "Fallback Gate", details)
        com.saab.tv.data.player.PlaybackDiagnostics.event(
            context = context,
            sessionId = diagnosticsSessionId,
            component = "Smart Next Episode",
            event = "Fallback Gate",
            details = details
        )
    }
    val isNearCompletion = remember(uiState.positionMs, uiState.durationMs, uiState.isEnded, skipSegmentInfo, autoplayThresholdMode, autoplayThresholdPercent, autoplayThresholdSeconds, hasError, smartPromptMs) {
        if (hasError) return@remember false
        val duration = uiState.durationMs
        val position = uiState.positionMs
        AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            positionMs = position,
            durationMs = duration,
            outroStartMs = skipSegmentInfo?.outroStartMs,
            thresholdMode = autoplayThresholdMode,
            thresholdPercent = autoplayThresholdPercent,
            thresholdSeconds = autoplayThresholdSeconds,
            playbackEnded = uiState.isEnded,
            smartPromptMs = smartPromptMs
        )
    }

    // Skip intro visibility — never show during error
    val showSkipIntro = remember(uiState.positionMs, skipSegmentInfo, hasError, introSkipped, skipIntroEnabled) {
        if (hasError || !skipIntroEnabled) return@remember false
        val info = skipSegmentInfo ?: return@remember false
        val start = info.introStartMs ?: return@remember false
        val end = info.introEndMs ?: return@remember false
        !introSkipped && start >= 0 && end > start && uiState.positionMs >= start && uiState.positionMs < end
    }

    val showSkipRecap = remember(uiState.positionMs, skipSegmentInfo, hasError, recapSkipped, showSkipIntro, skipRecapEnabled) {
        if (hasError || !skipRecapEnabled || showSkipIntro) return@remember false
        val info = skipSegmentInfo ?: return@remember false
        val start = info.recapStartMs ?: return@remember false
        val end = info.recapEndMs ?: return@remember false
        !recapSkipped && start >= 0 && end > start && uiState.positionMs >= start && uiState.positionMs < end
    }

    val introCountdownActive = showSkipIntro && autoSkipIntro && introSkipCountdownSeconds > 0 && !introSkipCancelled &&
        pendingPreviewSeekPosition == null
    LaunchedEffect(introCountdownActive, introSkipCountdownSeconds) {
        if (introCountdownActive) introSkipProgress.snapTo(0f)
    }
    LaunchedEffect(introCountdownActive, introSkipCountdownSeconds, uiState.isPlaying, uiState.isBuffering, uiState.isSeeking) {
        if (!introCountdownActive || !uiState.isPlaying || uiState.isBuffering || uiState.isSeeking) return@LaunchedEffect
        val remainingMs = PlayerActionProgressPolicy.remainingDurationMillis(
            introSkipProgress.value, introSkipCountdownSeconds
        )
        if (remainingMs > 0) introSkipProgress.animateTo(
            1f, animationSpec = tween(remainingMs, easing = LinearEasing)
        )
        skipSegmentInfo?.introEndMs?.let {
            introSkipped = true
            playbackController.seekTo(it)
            onSeekPreviewPosition(it)
        }
    }

    val recapCountdownActive = showSkipRecap && autoSkipIntro && introSkipCountdownSeconds > 0 &&
        !recapSkipCancelled && pendingPreviewSeekPosition == null
    LaunchedEffect(recapCountdownActive, introSkipCountdownSeconds) {
        if (recapCountdownActive) recapSkipProgress.snapTo(0f)
    }
    LaunchedEffect(recapCountdownActive, introSkipCountdownSeconds, uiState.isPlaying, uiState.isBuffering, uiState.isSeeking) {
        if (!recapCountdownActive || !uiState.isPlaying || uiState.isBuffering || uiState.isSeeking) return@LaunchedEffect
        val remainingMs = PlayerActionProgressPolicy.remainingDurationMillis(
            recapSkipProgress.value, introSkipCountdownSeconds
        )
        if (remainingMs > 0) recapSkipProgress.animateTo(
            1f, animationSpec = tween(remainingMs, easing = LinearEasing)
        )
        skipSegmentInfo?.recapEndMs?.let {
            recapSkipped = true
            playbackController.seekTo(it)
            onSeekPreviewPosition(it)
        }
    }

    val canAutoAdvance = AutoplayNextEpisodePolicy.shouldStartCountdown(
        uiState.positionMs, uiState.durationMs, skipSegmentInfo?.outroStartMs,
        autoplayThresholdMode, autoplayThresholdPercent, autoplayThresholdSeconds, uiState.isEnded)
    val shouldShowNextEpisode = autoplayEnabled && outroSkipCountdownSeconds > 0 &&
        canAutoAdvance &&
        nextEpisodeInfo != null &&
        onAutoplayNextEpisode != null &&
        !autoplayCancelled &&
        !nextEpisodeTriggered &&
        uiState.errorMessage.isNullOrBlank()

    val isInOutro = remember(uiState.positionMs, skipSegmentInfo, hasError) {
        if (hasError) return@remember false
        val start = skipSegmentInfo?.outroStartMs ?: return@remember false
        val end = skipSegmentInfo?.outroEndMs
        start > 0L && uiState.positionMs >= start && (end == null || end <= start || uiState.positionMs <= end)
    }

    val overlayVisible = countdownActive && nextEpisodeInfo != null && !autoplayCancelled && !nextEpisodeTriggered
    val showPlayNextButton = autoplayEnabled && (autoplayCancelled || outroSkipCountdownSeconds <= 0 || !canAutoAdvance) && !nextEpisodeTriggered &&
        nextEpisodeInfo != null &&
        onAutoplayNextEpisode != null &&
        uiState.errorMessage.isNullOrBlank() &&
        (uiState.isEnded || isInOutro || isNearCompletion)

    LaunchedEffect(diagnosticsSessionId, smartPromptMs, isNearCompletion, showPlayNextButton, countdownActive) {
        if (autoplayThresholdMode != "smart" && smartPromptMs == null) return@LaunchedEffect
        val reason = when {
            !smartFallbackEnabled -> smartFallbackGateReason
            smartPromptMs == null -> "waiting_for_credit_frames_or_ocr"
            uiState.positionMs < smartPromptMs -> "before_detected_credits_window"
            !showPlayNextButton && !countdownActive -> "threshold_reached_but_action_not_visible"
            countdownActive -> "countdown_active"
            showPlayNextButton -> "manual_action_visible"
            else -> "not_offered"
        }
        val details = "reason=$reason positionMs=${uiState.positionMs} durationMs=${uiState.durationMs} " +
            "promptMs=$smartPromptMs nearCompletion=$isNearCompletion " +
            "manualButton=$showPlayNextButton countdown=$countdownActive ended=${uiState.isEnded}"
        com.saab.tv.AppDiagnostics.event("Smart Next Episode", "Play Next State", details)
        com.saab.tv.data.player.PlaybackDiagnostics.event(
            context = context,
            sessionId = diagnosticsSessionId,
            component = "Smart Next Episode",
            event = "Play Next State",
            details = details
        )
    }

    // Start countdown when near completion
    LaunchedEffect(shouldShowNextEpisode, outroSkipCountdownSeconds) {
        if (shouldShowNextEpisode) {
            nextEpisodeProgress.snapTo(0f)
            countdownActive = true
        } else {
            countdownActive = false
            nextEpisodeProgress.snapTo(0f)
        }
    }

    // Countdown timer — pauses when video is paused or buffering
    LaunchedEffect(countdownActive, uiState.isPlaying, uiState.isEnded, uiState.isBuffering, uiState.isSeeking, pendingPreviewSeekPosition) {
        if (!countdownActive || (!uiState.isPlaying && !uiState.isEnded) || uiState.isBuffering ||
            uiState.isSeeking || pendingPreviewSeekPosition != null) return@LaunchedEffect
        val remainingMs = PlayerActionProgressPolicy.remainingDurationMillis(
            nextEpisodeProgress.value, outroSkipCountdownSeconds
        )
        if (remainingMs > 0) nextEpisodeProgress.animateTo(
            1f, animationSpec = tween(remainingMs, easing = LinearEasing)
        )
        // Countdown finished
        if (countdownActive && !autoplayCancelled) {
            triggerNextEpisode()
        }
    }

    // Re-focus buttons when controls hide
    LaunchedEffect(showControls, overlayVisible, showPlayNextButton, showSkipIntro, showSkipRecap) {
        if (!showControls) {
            when {
                overlayVisible -> runCatching { nextEpisodeFocusRequester.requestFocus() }
                showPlayNextButton -> runCatching { playNextFocusRequester.requestFocus() }
                showSkipIntro || showSkipRecap -> runCatching { skipIntroFocusRequester.requestFocus() }
            }
        }
    }

    // Also re-focus skip intro when it first appears and controls are not showing
    LaunchedEffect(showSkipIntro, showSkipRecap) {
        if ((showSkipIntro || showSkipRecap) && !showControls && !overlayVisible) {
            runCatching { skipIntroFocusRequester.requestFocus() }
        }
    }

    val headerInfo = remember(title, mediaType, seriesTitle) {
        resolveHeaderInfo(
            title = title,
            mediaType = mediaType,
            seriesTitle = seriesTitle
        )
    }
    val panelOpen = activePanel != PlayerPanel.NONE
    val displayPositionMs = pendingPreviewSeekPosition ?: uiState.positionMs
    val isPlaybackIntended = uiState.playWhenReady
    val showLoadingOverlay = uiState.errorMessage.isNullOrBlank() &&
        (uiState.isBuffering || uiState.isSeeking || !uiState.hasRenderedFirstFrame)
    val canShowPauseOverlay = !isPlaybackIntended &&
        !uiState.isBuffering &&
        uiState.isReady &&
        uiState.hasRenderedFirstFrame &&
        !panelOpen &&
        !showSubtitleOffsetBar &&
        !showSubtitleSizeBar &&
        !showSubtitleDelayBar &&
        !showSubtitleColorBar &&
        uiState.errorMessage.isNullOrBlank()

    LaunchedEffect(pendingPreviewSeekPosition, seekThumbnailCacheKey, seekTimeIntervalSeconds) {
        val targetPosition = pendingPreviewSeekPosition
        val provider = latestSeekThumbnailProvider
        if (targetPosition == null) return@LaunchedEffect
        if (provider == null) {
            seekPreviewFrames.forEach { frame -> frame.bitmap?.let { if (!it.isRecycled) it.recycle() } }
            seekPreviewFrames = emptyList()
            return@LaunchedEffect
        }
        // Resolve immediately: a cached thumbnail should appear on the first seek frame,
        // not after a debounce timer has already made the preview feel laggy.
        val positions = SeekThumbnailCarouselPolicy.positions(
            targetPosition, uiState.durationMs, seekTimeIntervalSeconds
        )
        if (positions.isEmpty()) {
            seekPreviewFrames.forEach { it.bitmap?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() } }
            seekPreviewFrames = emptyList()
            return@LaunchedEffect
        }
        val retainedIndices = SeekThumbnailCarouselPolicy.retainedFrameIndices(
            seekPreviewFrames.map { if (it.cacheKey == seekThumbnailCacheKey) it.positionMs else null }, positions
        )
        var replacements = positions.mapIndexed { index, position ->
            val oldIndex = retainedIndices[index]
            val bitmap = oldIndex?.let(seekPreviewFrames::getOrNull)?.bitmap
                ?.takeUnless { it.isRecycled }
            SeekPreviewFrame(positionMs = position, bitmap = bitmap, cacheKey = seekThumbnailCacheKey)
        }
        val centerIndex = SeekThumbnailCarouselPolicy.selectedIndex(positions, targetPosition)
        val centerPosition = positions.getOrNull(centerIndex)
        if (centerPosition != null && replacements[centerIndex].bitmap == null) {
            val bitmap = try {
                provider(centerPosition)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            replacements = replacements.toMutableList().also {
                it[centerIndex] = SeekPreviewFrame(centerPosition, bitmap, seekThumbnailCacheKey)
            }
        }
        val retainedBitmaps = replacements.mapNotNull { it.bitmap }.toSet()
        seekPreviewFrames.filter { it.bitmap != null && it.bitmap !in retainedBitmaps }
            .forEach { frame -> frame.bitmap?.let { if (!it.isRecycled) it.recycle() } }
        // Keep the last carousel visible until the new center image is ready.
        seekPreviewFrames = replacements

        // Center first, followed by immediate and outer neighbours.
        for (index in SeekThumbnailCarouselPolicy.loadOrder(positions.size)) {
            if (index == centerIndex || replacements[index].bitmap != null) continue
            val position = positions[index]
            val bitmap = try {
                provider(position)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            replacements = replacements.toMutableList().also { frames ->
                frames[index] = SeekPreviewFrame(positionMs = position, bitmap = bitmap, cacheKey = seekThumbnailCacheKey)
            }
            seekPreviewFrames = replacements
        }
    }

    DisposableEffect(playbackController) {
        onDispose {
            latestSeekPreviewFrames.forEach { frame -> frame.bitmap?.let { if (!it.isRecycled) it.recycle() } }
            seekPreviewFrames = emptyList()
        }
    }

    fun markInteraction() {
        interactionSignal++
        showPauseOverlay = false
    }

    fun scheduleHideControls() {
        hideControlsSignal++
    }

    fun showControlsTemporarily() {
        showControls = true
        showSeekOverlay = false
        scheduleHideControls()
    }

    fun showSeekOverlayTemporarily() {
        showSeekOverlay = true
        hideSeekOverlaySignal++
    }

    fun closePanel() {
        markInteraction()
        activePanel = PlayerPanel.NONE
        if (showControls) {
            scheduleHideControls()
        }
    }

    fun handleBackAction() {
        when {
            pendingPreviewSeekPosition != null -> {
                pendingPreviewSeekPosition = null
                if (resumeAfterPreviewSeek) playbackController.play()
                resumeAfterPreviewSeek = false
                showSeekOverlay = false
            }
            introCountdownActive -> { introSkipCancelled = true }
            recapCountdownActive -> { recapSkipCancelled = true }
            episodeSwitchSources != null || isEpisodeSwitchLoading -> {
                onEpisodeSwitchDismissed?.invoke()
                activePanel = PlayerPanel.EPISODES
                showControls = true
            }
            !uiState.errorMessage.isNullOrBlank() && !panelOpen -> onBack()
            showSubtitleDelayBar -> {
                markInteraction()
                showSubtitleDelayBar = false
            }
            showSubtitleSizeBar -> {
                markInteraction()
                showSubtitleSizeBar = false
            }
            showSubtitleOffsetBar -> {
                markInteraction()
                showSubtitleOffsetBar = false
            }
            showSubtitleColorBar -> {
                markInteraction()
                showSubtitleColorBar = false
            }
            showPauseOverlay -> {
                markInteraction()
                showPauseOverlay = false
                showControls = true
                showSeekOverlay = false
                if (isPlaybackIntended) {
                    scheduleHideControls()
                }
            }
            panelOpen -> closePanel()
            showControls -> {
                if (uiState.isEnded && nextEpisodeInfo == null) {
                    onBack()
                    return
                }
                markInteraction()
                showControls = false
                showSeekOverlay = false
            }
            overlayVisible -> {
                autoplayCancelled = true
                countdownActive = false
                showControls = true
                scheduleHideControls()
            }
            else -> onBack()
        }
    }

    fun previewSeekBy(deltaMs: Long) {
        val maxDuration = uiState.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        if (pendingPreviewSeekPosition == null) {
            resumeAfterPreviewSeek = uiState.playWhenReady
            if (resumeAfterPreviewSeek) playbackController.pause()
        }
        val basePosition = pendingPreviewSeekPosition ?: uiState.positionMs.coerceAtLeast(0L)
        val requestedTarget = (basePosition + deltaMs)
            .coerceAtLeast(0L)
            .coerceAtMost(maxDuration)
        val target = if (latestSeekThumbnailProvider != null) {
            com.saab.tv.data.cache.ThumbnailTimelinePolicy.gridPosition(requestedTarget, seekTimeIntervalSeconds)
                .coerceAtMost(if (uiState.durationMs > 0) {
                    val step = seekTimeIntervalSeconds.coerceAtLeast(1) * 1_000L
                    (uiState.durationMs - 1).coerceAtLeast(0) / step * step
                } else maxDuration)
        } else requestedTarget
        pendingPreviewSeekPosition = target
        showSeekOverlayTemporarily()
    }

    val latestSeekPositionCallback by rememberUpdatedState(onSeekPreviewPosition)
    LaunchedEffect(pendingPreviewSeekPosition) {
        val position = pendingPreviewSeekPosition ?: return@LaunchedEffect
        delay(250L)
        latestSeekPositionCallback(position)
    }

    fun commitPreviewSeek() {
        val target = pendingPreviewSeekPosition ?: return
        playbackController.seekTo(target)
        onSeekPreviewPosition(target)
        pendingPreviewSeekPosition = null
        if (resumeAfterPreviewSeek) playbackController.play()
        resumeAfterPreviewSeek = false
        showSeekOverlayTemporarily()
    }

    val dispatchBackFromKey = com.saab.tv.ui.navigation.rememberAppBackKeyAction { handleBackAction() }
    com.saab.tv.ui.navigation.AppBackHandler(priority = com.saab.tv.ui.navigation.AppBackPriority.Player) {
        handleBackAction()
    }

    val episodeSwitchOpen = episodeSwitchSources != null || isEpisodeSwitchLoading

    LaunchedEffect(showControls, isPlaybackIntended, panelOpen, showSubtitleOffsetBar, showSubtitleSizeBar, showSubtitleDelayBar, showSubtitleColorBar, hideControlsSignal, episodeSwitchOpen) {
        if (!showControls || !isPlaybackIntended || panelOpen || showSubtitleOffsetBar || showSubtitleSizeBar || showSubtitleDelayBar || showSubtitleColorBar) return@LaunchedEffect
        if (!uiState.errorMessage.isNullOrBlank()) return@LaunchedEffect
        if (episodeSwitchOpen) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        if (showControls && isPlaybackIntended && !panelOpen && !showSubtitleOffsetBar && !showSubtitleSizeBar && !showSubtitleDelayBar && !showSubtitleColorBar) {
            showControls = false
        }
    }

    LaunchedEffect(showSeekOverlay, showControls, hideSeekOverlaySignal) {
        if (!showSeekOverlay || showControls) return@LaunchedEffect
        delay(SEEK_OVERLAY_AUTO_HIDE_MS)
        if (showSeekOverlay && !showControls) {
            showSeekOverlay = false
        }
    }

    LaunchedEffect(canShowPauseOverlay, interactionSignal) {
        if (!canShowPauseOverlay) {
            showPauseOverlay = false
            return@LaunchedEffect
        }
        delay(PAUSE_OVERLAY_IDLE_MS)
        if (canShowPauseOverlay) {
            showControls = false
            showSeekOverlay = false
            showPauseOverlay = true
        }
    }

    LaunchedEffect(showControls, panelOpen, showSubtitleOffsetBar, showSubtitleSizeBar, showSubtitleDelayBar, showSubtitleColorBar, hasError, episodeSwitchOpen) {
        if (showSubtitleOffsetBar || showSubtitleSizeBar || showSubtitleDelayBar || showSubtitleColorBar) return@LaunchedEffect
        if (overlayVisible || showPlayNextButton || showSkipIntro || showSkipRecap || hasError) return@LaunchedEffect
        if (episodeSwitchOpen) return@LaunchedEffect

        if (showControls && !panelOpen) {
            delay(250)
            runCatching { playPauseFocusRequester.requestFocus() }
        } else if (!showControls && !panelOpen) {
            runCatching { containerFocusRequester.requestFocus() }
        }
    }

    // Close panels when episode switch source sidebar or loading spinner appears
    LaunchedEffect(episodeSwitchSources, isEpisodeSwitchLoading) {
        if (episodeSwitchSources != null || isEpisodeSwitchLoading) {
            activePanel = PlayerPanel.NONE
            showControls = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { containerFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(containerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.isBackOrEscape()) {
                    if (keyEvent.isInitialBackPress()) {
                        dispatchBackFromKey()
                    }
                    return@onPreviewKeyEvent true
                }

                if (!keyEvent.isBackOrEscape() &&
                    (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN ||
                        keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP)
                ) {
                    markInteraction()
                }

                if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_CAPTIONS) {
                    return@onPreviewKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent true
                }

                if (!panelOpen && !episodeSwitchOpen && subtitleTracks.isNotEmpty()) {
                    showControls = true
                    activePanel = PlayerPanel.SUBTITLES
                }
                true
            }
            .onKeyEvent { keyEvent ->
                if (!keyEvent.isBackOrEscape() &&
                    (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN ||
                        keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP)
                ) {
                    markInteraction()
                }

                // During error, only let back key through (handled in onPreviewKeyEvent)
                if (hasError) return@onKeyEvent false
                if (panelOpen || episodeSwitchOpen) return@onKeyEvent false

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) return@onKeyEvent false

                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onKeyEvent false
                }

                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        if (!showControls) {
                            if (pendingPreviewSeekPosition != null) {
                                commitPreviewSeek()
                            } else {
                                playbackController.togglePlayPause()
                                showControlsTemporarily()
                            }
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!showControls) {
                            previewSeekBy(seekTimeIntervalMs)
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!showControls) {
                            previewSeekBy(-seekTimeIntervalMs)
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!showControls) {
                            showControlsTemporarily()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!showControls) {
                            showControlsTemporarily()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        playbackController.togglePlayPause()
                        showControlsTemporarily()
                        true
                    }

                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        previewSeekBy(seekTimeIntervalMs)
                        if (showControls) scheduleHideControls() else showSeekOverlayTemporarily()
                        true
                    }

                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        previewSeekBy(-seekTimeIntervalMs)
                        if (showControls) scheduleHideControls() else showSeekOverlayTemporarily()
                        true
                    }

                    else -> false
                }
            }
    ) {
        ComposePlayerSurface(
            renderSurface = renderSurface,
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = showPauseOverlay,
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(320))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(220)))
        ) {
            PauseBrandOverlay(
                logoUrl = logoUrl,
                primaryText = headerInfo.primaryText
            )
        }

        AnimatedVisibility(
            visible = showLoadingOverlay,
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(150))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(120)))
        ) {
            LoadingOverlay(
                torrentProgress = torrentProgress,
                logoUrl = logoUrl,
                primaryText = headerInfo.primaryText
            )
        }

        AnimatedVisibility(
            visible = !isTrailer && showControls && !panelOpen && !showSubtitleOffsetBar && !showSubtitleSizeBar && !showSubtitleDelayBar && !showSubtitleColorBar && uiState.errorMessage.isNullOrBlank(),
            enter = slideInVertically(animationSpec = tween(SaabMotion.duration(200))) { -it } + fadeIn(animationSpec = tween(SaabMotion.duration(200))),
            exit = slideOutVertically(animationSpec = tween(SaabMotion.duration(200))) { -it } + fadeOut(animationSpec = tween(SaabMotion.duration(200))),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 74.dp, end = 36.dp)
        ) {
            PlayerHeader(
                primaryText = headerInfo.primaryText,
                secondaryText = headerInfo.secondaryText,
                durationMs = uiState.durationMs,
                positionMs = displayPositionMs,
                seekThumbnailCachePercent = seekThumbnailCachePercent,
                seekThumbnailCachedFrames = seekThumbnailCachedFrames,
                seekThumbnailTotalFrames = seekThumbnailTotalFrames
            )
        }

        AnimatedVisibility(
            visible = showControls && !panelOpen && !showSubtitleOffsetBar && !showSubtitleSizeBar && !showSubtitleDelayBar && !showSubtitleColorBar && uiState.errorMessage.isNullOrBlank(),
            enter = slideInVertically(animationSpec = tween(SaabMotion.duration(200))) { it } + fadeIn(animationSpec = tween(SaabMotion.duration(200))),
            exit = slideOutVertically(animationSpec = tween(SaabMotion.duration(200))) { it } + fadeOut(animationSpec = tween(SaabMotion.duration(200)))
        ) {
            PlayerControlsOverlay(
                currentPositionMs = displayPositionMs,
                durationMs = uiState.durationMs,
                isPlaying = isPlaybackIntended,
                isPlaybackAdvancing = uiState.isPlaying && !uiState.isBuffering && !uiState.isSeeking &&
                    pendingPreviewSeekPosition == null,
                showSourceControl = !isTrailer && sources.size > 1,
                showAudioControl = !isTrailer && audioTracks.isNotEmpty(),
                showSubtitleControl = !isTrailer && subtitleTracks.isNotEmpty(),
                showCastControl = !isTrailer,
                showNextEpisodeControl = !isTrailer && nextEpisodeInfo != null &&
                    onAutoplayNextEpisode != null,
                playPauseFocusRequester = playPauseFocusRequester,
                seekBarFocusRequester = seekBarFocusRequester,
                onShowCastPanel = {
                    markInteraction()
                    activePanel = PlayerPanel.CAST
                    showControls = true
                    showSeekOverlay = false
                    onLoadCast()
                },
                onPlayPause = {
                    markInteraction()
                    playbackController.togglePlayPause()
                    showControlsTemporarily()
                },
                onSeekBy = { deltaMs ->
                    markInteraction()
                    previewSeekBy(deltaMs)
                    scheduleHideControls()
                },
                seekTimeIntervalMs = seekTimeIntervalMs,
                onSeekCommit = {
                    markInteraction()
                    commitPreviewSeek()
                    scheduleHideControls()
                },
                onShowSourcesPanel = {
                    markInteraction()
                    activePanel = PlayerPanel.SOURCES
                    showControls = true
                    showSeekOverlay = false
                },
                onShowAudioPanel = {
                    markInteraction()
                    activePanel = PlayerPanel.AUDIO
                    showControls = true
                    showSeekOverlay = false
                },
                onShowSubtitlePanel = {
                    markInteraction()
                    activePanel = PlayerPanel.SUBTITLES
                    showControls = true
                    showSeekOverlay = false
                },
                onPlayNextEpisode = {
                    markInteraction()
                    triggerNextEpisode()
                },
                showEpisodesControl = episodes.isNotEmpty() && onEpisodeSelected != null,
                onShowEpisodesPanel = {
                    markInteraction()
                    activePanel = PlayerPanel.EPISODES
                    showControls = true
                    showSeekOverlay = false
                },
                onResetHideTimer = {
                    markInteraction()
                    scheduleHideControls()
                }
            )
        }

        AnimatedVisibility(
            visible = showSeekOverlay && !showControls && !panelOpen && uiState.errorMessage.isNullOrBlank(),
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(150))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(150))),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SeekOverlay(
                currentPositionMs = displayPositionMs,
                durationMs = uiState.durationMs,
                isPlaybackAdvancing = uiState.isPlaying && !uiState.isBuffering && !uiState.isSeeking &&
                    pendingPreviewSeekPosition == null
            )
        }

        AnimatedVisibility(
            visible = pendingPreviewSeekPosition != null && seekThumbnailProvider != null &&
                !panelOpen && uiState.errorMessage.isNullOrBlank(),
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(120))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(100))),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showControls) 142.dp else 82.dp)
        ) {
            SeekThumbnailCarousel(
                frames = seekPreviewFrames,
                selectedPositionMs = displayPositionMs,
                durationMs = uiState.durationMs,
                intervalSeconds = seekTimeIntervalSeconds,
                cacheKey = seekThumbnailCacheKey
            )
        }

        if (!uiState.errorMessage.isNullOrBlank()) {
            PlayerErrorOverlay(
                errorMessage = uiState.errorMessage.orEmpty(),
                onBack = onBack,
                onNextSource = nextSource?.let { source ->
                    {
                        markInteraction()
                        onSourceChosen(source)
                        playbackController.selectSource(source.id)
                    }
                },
                onSwitchSource = if (sources.size > 1) {
                    {
                        markInteraction()
                        activePanel = PlayerPanel.SOURCES
                        showControls = true
                        showSeekOverlay = false
                    }
                } else null
            )
        }

        PlayerSourceSidebar(
            visible = activePanel == PlayerPanel.SOURCES,
            title = headerInfo.primaryText,
            onClose = { closePanel() },
            sources = sources,
            sourceLanguagePreferences = preferredAudioLanguages,
            currentSourceId = uiState.currentSourceId,
            onLanguageSelectSource = { sourceId ->
                sources.firstOrNull { it.id == sourceId }?.let(onSourceChosen)
                playbackController.selectSource(sourceId)
                showSubtitleOffsetBar = false
                showSubtitleSizeBar = false
                showSubtitleDelayBar = false
                showSubtitleColorBar = false
            },
            onSelectSource = { sourceId ->
                sources.firstOrNull { it.id == sourceId }?.let(onSourceChosen)
                playbackController.selectSource(sourceId)
                closePanel()
                showSubtitleOffsetBar = false
                showSubtitleSizeBar = false
                showSubtitleDelayBar = false
                showSubtitleColorBar = false
            }
        )

        PlayerEpisodeSidebar(
            visible = activePanel == PlayerPanel.EPISODES && episodeSwitchSources == null && !isEpisodeSwitchLoading,
            episodes = episodes,
            currentPlaybackId = currentPlaybackId,
            onClose = { closePanel() },
            onEpisodeSelected = { episode ->
                closePanel()
                if (!episodeMatchesPlaybackId(null, currentPlaybackId, episode)) {
                    onEpisodeSelected?.invoke(
                        episode,
                        currentSourceUrl,
                        uiState.positionMs.coerceAtLeast(0L),
                        uiState.durationMs.takeIf { it > 0L }
                    )
                }
            }
        )

        EpisodeSwitchSourceSidebar(
            visible = episodeSwitchSources != null,
            title = episodeSwitchTitle ?: "",
            sources = episodeSwitchSources,
            onClose = {
                nextEpisodeTriggered = false
                onEpisodeSwitchDismissed?.invoke()
                activePanel = PlayerPanel.EPISODES
                showControls = true
            },
            onSelectSource = { sourceUrl ->
                onEpisodeSwitchSourceSelected?.invoke(sourceUrl)
            }
        )

        // Centered loading spinner for auto-resolve paths (binge group, auto-select)
        if (isEpisodeSwitchLoading && episodeSwitchSources == null) {
            LoadingOverlay(
                logoUrl = logoUrl,
                primaryText = headerInfo.primaryText
            )
        }

        AudioSelectionSidePanel(
            visible = activePanel == PlayerPanel.AUDIO,
            title = stringResource(R.string.player_audio_tracks_title),
            audioTracks = audioTracks,
            sources = sources,
            currentSourceId = uiState.currentSourceId,
            selectedAudioId = uiState.selectedAudioTrackId,
            preferredAudioLanguages = preferredAudioLanguages,
            onClose = { closePanel() },
            onSelectTrack = { trackId ->
                markInteraction()
                playbackController.selectAudioTrack(trackId)
            },
            onSelectSourceForLanguage = { sourceId, language ->
                markInteraction()
                sources.firstOrNull { it.id == sourceId }?.let(onSourceChosen)
                playbackController.selectSourceForAudioLanguage(sourceId, language)
            }
        )

        SubtitleSelectionSidePanel(
            visible = activePanel == PlayerPanel.SUBTITLES,
            title = stringResource(R.string.player_subtitles_title),
            subtitleTracks = subtitleTracks,
            selectedSubtitleId = uiState.selectedSubtitleTrackId,
            onClose = { closePanel() },
            onSelectTrack = { trackId ->
                markInteraction()
                playbackController.selectSubtitleTrack(trackId)
            },
            onShowOffsetBar = {
                closePanel()
                showSubtitleOffsetBar = true
            },
            onShowSizeBar = {
                closePanel()
                showSubtitleSizeBar = true
            },
            onShowDelayBar = {
                closePanel()
                showSubtitleDelayBar = true
            },
            onShowColorBar = {
                closePanel()
                showSubtitleColorBar = true
            }
        )

        PlaybackCastPanel(
            visible = activePanel == PlayerPanel.CAST,
            castMembers = castMembers,
            isLoading = isLoadingCast,
            onClose = { closePanel() },
            backFocusRequester = castBackFocusRequester,
            firstCastFocusRequester = firstCastFocusRequester
        )

        SubtitleOffsetTopBar(
            visible = showSubtitleOffsetBar,
            offsetPercent = uiState.subtitleVerticalOffsetPercent,
            isPlaying = isPlaybackIntended,
            onPlayPause = {
                markInteraction()
                playbackController.togglePlayPause()
            },
            onDecrement = {
                markInteraction()
                playbackController.setSubtitleVerticalOffset(uiState.subtitleVerticalOffsetPercent - 1)
            },
            onIncrement = {
                markInteraction()
                playbackController.setSubtitleVerticalOffset(uiState.subtitleVerticalOffsetPercent + 1)
            },
            onClose = {
                markInteraction()
                showSubtitleOffsetBar = false
                showControls = true
                scheduleHideControls()
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        )

        SubtitleSizeTopBar(
            visible = showSubtitleSizeBar,
            sizePercent = uiState.subtitleSizePercent,
            isPlaying = isPlaybackIntended,
            onPlayPause = {
                markInteraction()
                playbackController.togglePlayPause()
            },
            onDecrement = {
                markInteraction()
                playbackController.setSubtitleSize(uiState.subtitleSizePercent - 10)
            },
            onIncrement = {
                markInteraction()
                playbackController.setSubtitleSize(uiState.subtitleSizePercent + 10)
            },
            onClose = {
                markInteraction()
                showSubtitleSizeBar = false
                showControls = true
                scheduleHideControls()
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        )

        SubtitleDelayTopBar(
            visible = showSubtitleDelayBar,
            delayMs = uiState.subtitleDelayMs,
            isPlaying = isPlaybackIntended,
            onPlayPause = {
                markInteraction()
                playbackController.togglePlayPause()
            },
            onDecrement = {
                markInteraction()
                playbackController.setSubtitleDelay(uiState.subtitleDelayMs - 100L)
            },
            onIncrement = {
                markInteraction()
                playbackController.setSubtitleDelay(uiState.subtitleDelayMs + 100L)
            },
            onClose = {
                markInteraction()
                showSubtitleDelayBar = false
                showControls = true
                scheduleHideControls()
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        )

        SubtitleColorTopBar(
            visible = showSubtitleColorBar,
            currentTextColor = uiState.subtitleTextColor,
            currentBackgroundColor = uiState.subtitleBackgroundColor,
            isPlaying = isPlaybackIntended,
            onPlayPause = {
                markInteraction()
                playbackController.togglePlayPause()
            },
            onSetTextColor = { color ->
                markInteraction()
                playbackController.setSubtitleTextColor(color)
            },
            onSetBackgroundColor = { color ->
                markInteraction()
                playbackController.setSubtitleBackgroundColor(color)
            },
            onClose = {
                markInteraction()
                showSubtitleColorBar = false
                showControls = true
                scheduleHideControls()
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        )

        // Animate bottom padding so buttons move above controls when visible
        val controlsVisible = showControls && !panelOpen && !showSubtitleOffsetBar && !showSubtitleSizeBar && !showSubtitleDelayBar && !showSubtitleColorBar && uiState.errorMessage.isNullOrBlank()
        val buttonBottomPadding by animateDpAsState(
            targetValue = if (controlsVisible) 120.dp else 32.dp,
            animationSpec = tween(SaabMotion.duration(200)),
            label = "buttonBottomPadding"
        )

        // Skip intro button
        AnimatedVisibility(
            visible = (showSkipIntro || showSkipRecap) && !overlayVisible,
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(300))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(200))),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = buttonBottomPadding)
        ) {
            SkipIntroButton(
                label = if (showSkipRecap) "Skip Recap" else "Skip Intro",
                progress = when {
                    recapCountdownActive -> recapSkipProgress.value
                    introCountdownActive -> introSkipProgress.value
                    else -> null
                },
                onSkip = {
                    val endMs = if (showSkipRecap) skipSegmentInfo?.recapEndMs else skipSegmentInfo?.introEndMs
                    endMs?.let {
                        if (showSkipRecap) recapSkipped = true else introSkipped = true
                        playbackController.seekTo(it)
                        onSeekPreviewPosition(it)
                    }
                },
                focusRequester = skipIntroFocusRequester
            )
        }

        // Next episode countdown button
        AnimatedVisibility(
            visible = countdownActive && nextEpisodeInfo != null && !autoplayCancelled,
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(300))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(200))),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = buttonBottomPadding)
        ) {
            nextEpisodeInfo?.let {
                NextEpisodeButton(
                    progress = nextEpisodeProgress.value,
                    onPlayNow = triggerNextEpisode,
                    focusRequester = nextEpisodeFocusRequester
                )
            }
        }

        // Manual outro skip / next-episode action.
        AnimatedVisibility(
            visible = showPlayNextButton,
            enter = fadeIn(animationSpec = tween(SaabMotion.duration(300))),
            exit = fadeOut(animationSpec = tween(SaabMotion.duration(200))),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = buttonBottomPadding)
        ) {
            PlayNextEpisodeButton(
                onPlayNext = triggerNextEpisode,
                label = stringResource(R.string.player_play_next_episode),
                focusRequester = playNextFocusRequester
            )
        }
    }
}

@Composable
private fun NextEpisodeButton(
    progress: Float,
    onPlayNow: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    PlayerActionButton(stringResource(R.string.player_play_next_episode), progress, onPlayNow, focusRequester)
}

@Composable
private fun PlayNextEpisodeButton(
    onPlayNext: () -> Unit,
    label: String,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    PlayerActionButton(label, null, onPlayNext, focusRequester)
}

@Composable
private fun SkipIntroButton(
    onSkip: () -> Unit,
    label: String = "Skip Intro",
    progress: Float? = null,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    PlayerActionButton(label, progress, onSkip, focusRequester)
}

@Composable
private fun PlayerActionButton(
    label: String,
    progress: Float?,
    onClick: () -> Unit,
    focusRequester: FocusRequester
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(focusRequester) {
        runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .padding(end = 24.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.07f))
            .drawBehind {
                val fraction = progress?.coerceIn(0f, 1f) ?: 0f
                if (fraction > 0f) drawRect(
                    color = accentColor.copy(alpha = if (isFocused) 0.32f else 0.24f),
                    size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height)
                )
            }
            .border(
                if (isFocused) 2.dp else 1.dp,
                if (isFocused) accentColor else Color.White.copy(0.15f),
                RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.SkipNext,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            color = if (isFocused) accentColor else Color.White.copy(0.8f),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun PlayerControlsOverlay(
    currentPositionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isPlaybackAdvancing: Boolean,
    showSourceControl: Boolean,
    showAudioControl: Boolean,
    showSubtitleControl: Boolean,
    showCastControl: Boolean,
    showNextEpisodeControl: Boolean,
    playPauseFocusRequester: FocusRequester,
    seekBarFocusRequester: FocusRequester,
    onShowCastPanel: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    seekTimeIntervalMs: Long,
    onShowSourcesPanel: () -> Unit,
    onShowAudioPanel: () -> Unit,
    onShowSubtitlePanel: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    showEpisodesControl: Boolean = false,
    onShowEpisodesPanel: () -> Unit = {},
    onResetHideTimer: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.15f to Color.Black.copy(alpha = 0.05f),
                            0.3f to Color.Black.copy(alpha = 0.14f),
                            0.45f to Color.Black.copy(alpha = 0.28f),
                            0.6f to Color.Black.copy(alpha = 0.44f),
                            0.75f to Color.Black.copy(alpha = 0.60f),
                            0.88f to Color.Black.copy(alpha = 0.74f),
                            1.0f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            FocusableSeekBar(
                currentPosition = currentPositionMs,
                duration = durationMs,
                isPlaybackAdvancing = isPlaybackAdvancing,
                onSeekBy = onSeekBy,
                onSeekCommit = onSeekCommit,
                seekTimeIntervalMs = seekTimeIntervalMs,
                onFocused = onResetHideTimer,
                focusRequester = seekBarFocusRequester,
                downFocusRequester = playPauseFocusRequester
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlButton(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.player_pause else R.string.player_play
                        ),
                        onClick = onPlayPause,
                        focusRequester = playPauseFocusRequester,
                        onFocused = onResetHideTimer,
                        upFocusRequester = seekBarFocusRequester,
                        buttonSize = 54.dp,
                        iconSize = 29.dp
                    )

                    if (showSubtitleControl) {
                        ControlButton(
                            icon = Icons.Default.ClosedCaption,
                            contentDescription = stringResource(R.string.player_subtitles_control),
                            onClick = onShowSubtitlePanel,
                            onFocused = onResetHideTimer,
                            buttonSize = 36.dp,
                            iconSize = 18.dp
                        )
                    }

                    if (showAudioControl) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.player_audio_tracks_control),
                            onClick = onShowAudioPanel,
                            onFocused = onResetHideTimer,
                            buttonSize = 36.dp,
                            iconSize = 20.dp
                        )
                    }

                    if (showNextEpisodeControl) {
                        ControlButton(
                            icon = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.player_next_episode_control),
                            onClick = onPlayNextEpisode,
                            onFocused = onResetHideTimer,
                            buttonSize = 36.dp,
                            iconSize = 20.dp
                        )
                    }

                    if (showEpisodesControl) {
                        ControlButton(
                            icon = Icons.Default.VideoLibrary,
                            contentDescription = stringResource(R.string.player_episodes_control),
                            onClick = onShowEpisodesPanel,
                            onFocused = onResetHideTimer,
                            buttonSize = 36.dp,
                            iconSize = 18.dp
                        )
                    }

                    if (showCastControl) {
                        ControlButton(
                            icon = Icons.Default.People,
                            contentDescription = stringResource(R.string.player_cast_control),
                            onClick = onShowCastPanel,
                            testTag = "player-cast-control",
                            onFocused = onResetHideTimer,
                            buttonSize = 36.dp,
                            iconSize = 20.dp
                        )
                    }

                    if (showSourceControl || showAudioControl || showSubtitleControl) {
                        ControlButton(
                            icon = Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.player_more_sources_control),
                            onClick = onShowSourcesPanel,
                            onFocused = onResetHideTimer,
                            buttonSize = 36.dp,
                            iconSize = 18.dp
                        )
                    }
                }

                Text(
                    text = "${formatTime(currentPositionMs)} \u2022 ${formatTime(durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    testTag: String? = null,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .focusProperties {
                if (upFocusRequester != null) up = upFocusRequester
                if (downFocusRequester != null) down = downFocusRequester
            }
            .onFocusChanged {
                if (it.isFocused) onFocused?.invoke()
            }
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        colors = playerIconButtonColors(Color.Transparent),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize)
        )
    }
}

internal data class PlayerIconButtonPalette(
    val containerColor: Color,
    val focusedContainerColor: Color,
    val contentColor: Color,
    val focusedContentColor: Color
)

internal fun playerIconButtonPalette(containerColor: Color) = PlayerIconButtonPalette(
    containerColor = containerColor,
    focusedContainerColor = Color.White,
    contentColor = Color.White,
    focusedContentColor = Color.Black
)

@Composable
private fun playerIconButtonColors(containerColor: Color) = playerIconButtonPalette(containerColor).let { palette ->
    IconButtonDefaults.colors(
        containerColor = palette.containerColor,
        focusedContainerColor = palette.focusedContainerColor,
        contentColor = palette.contentColor,
        focusedContentColor = palette.focusedContentColor
    )
}

@Composable
private fun FocusableSeekBar(
    currentPosition: Long,
    duration: Long,
    isPlaybackAdvancing: Boolean,
    onSeekBy: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    seekTimeIntervalMs: Long,
    onFocused: () -> Unit,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val safeDurationMs = duration.coerceAtLeast(0L)
    val safePositionMs = currentPosition.coerceIn(0L, safeDurationMs)
    val progress = if (safeDurationMs > 0L) {
        safePositionMs.toFloat() / safeDurationMs.toFloat()
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .focusProperties {
                if (downFocusRequester != null) down = downFocusRequester
            }
            .semantics {
                contentDescription = context.getString(R.string.player_seek_progress)
                stateDescription = context.getString(
                    R.string.player_seek_position,
                    formatTime(safePositionMs),
                    formatTime(safeDurationMs)
                )
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                setProgress { requestedProgress ->
                    if (safeDurationMs <= 0L) {
                        false
                    } else {
                        val targetPositionMs =
                            (requestedProgress.coerceIn(0f, 1f) * safeDurationMs).toLong()
                        onSeekBy(targetPositionMs - safePositionMs)
                        onSeekCommit()
                        true
                    }
                }
            }
            .onFocusChanged {
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onKeyEvent false
                }
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onFocused()
                        onSeekBy(seekTimeIntervalMs)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onFocused()
                        onSeekBy(-seekTimeIntervalMs)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        onSeekCommit()
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource)
    ) {
        ProgressBar(
            currentPosition = currentPosition,
            duration = duration,
            isFocused = isFocused,
            isPlaybackAdvancing = isPlaybackAdvancing
        )
    }
}

@Composable
private fun SeekThumbnailCarousel(
    frames: List<SeekPreviewFrame>,
    selectedPositionMs: Long,
    durationMs: Long,
    intervalSeconds: Int,
    cacheKey: String
) {
    val positions = SeekThumbnailCarouselPolicy.positions(selectedPositionMs, durationMs, intervalSeconds)
    val selectedIndex = SeekThumbnailCarouselPolicy.selectedIndex(positions, selectedPositionMs)
    val visibleFrames = if (SeekThumbnailCarouselPolicy.hasCurrentFrames(
            framePositions = frames.map { it.positionMs },
            frameCacheKeys = frames.map { it.cacheKey },
            requestedPositions = positions,
            cacheKey = cacheKey
        )) frames
    else {
        val reusableIndices = SeekThumbnailCarouselPolicy.retainedFrameIndices(
            previous = frames.map { frame -> frame.positionMs.takeIf { frame.cacheKey == cacheKey } },
            requested = positions
        )
        positions.mapIndexed { index, position ->
            reusableIndices[index]?.let { frames[it].copy(positionMs = position) }
                ?: SeekPreviewFrame(positionMs = position, bitmap = null, cacheKey = cacheKey)
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        visibleFrames.forEachIndexed { index, frame ->
            SeekThumbnailTile(frame = frame, isSelected = index == selectedIndex)
        }
    }
}

@Composable
internal fun SeekThumbnailTile(
    frame: SeekPreviewFrame,
    isSelected: Boolean
) {
    val width by animateDpAsState(
        targetValue = if (isSelected) 256.dp else 176.dp,
        animationSpec = tween(SaabMotion.duration(170)),
        label = "seekThumbnailWidth"
    )
    val height by animateDpAsState(
        targetValue = if (isSelected) 144.dp else 99.dp,
        animationSpec = tween(SaabMotion.duration(170)),
        label = "seekThumbnailHeight"
    )
    val shape = RoundedCornerShape(if (isSelected) 10.dp else 7.dp)

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(Color(0xFF101116))
            .border(
                width = if (isSelected) 4.dp else 1.dp,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.24f),
                shape = shape
            )
    ) {
        val bitmap = frame.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = frame.positionMs?.let {
                    stringResource(R.string.player_scene_preview_at, formatTime(it))
                },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        frame.positionMs?.let { positionMs ->
            Text(
                text = formatTime(positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isSelected) 8.dp else 5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(horizontal = if (isSelected) 12.dp else 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ProgressBar(
    currentPosition: Long,
    duration: Long,
    isFocused: Boolean = false,
    isPlaybackAdvancing: Boolean = false
) {
    val progress = PlaybackProgressAnimationPolicy.fraction(currentPosition, duration)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = PlaybackProgressAnimationPolicy.interpolationDurationMs(isPlaybackAdvancing),
            easing = LinearEasing
        ),
        label = "playbackProgress"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
    ) {
        val trackHeight = if (isFocused) 5.dp else 4.dp
        val thumbSize = if (isFocused) 12.dp else 9.dp
        val clampedProgress = animatedProgress.coerceIn(0f, 1f)
        val thumbOffset = (maxWidth - thumbSize) * clampedProgress

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.34f))
        )

        val primaryColor = MaterialTheme.colorScheme.primary

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(clampedProgress)
                .height(trackHeight)
                .testTag("player-progress-fill")
                .clip(RoundedCornerShape(999.dp))
                .background(primaryColor)
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(primaryColor)
        )
    }
}

@Composable
private fun SeekOverlay(
    currentPositionMs: Long,
    durationMs: Long,
    isPlaybackAdvancing: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        ProgressBar(
            currentPosition = currentPositionMs,
            duration = durationMs,
            isPlaybackAdvancing = isPlaybackAdvancing
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTime(currentPositionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun PlayerHeader(
    primaryText: String,
    secondaryText: String?,
    durationMs: Long = 0L,
    positionMs: Long = 0L,
    seekThumbnailCachePercent: Int? = null,
    seekThumbnailCachedFrames: Int? = null,
    seekThumbnailTotalFrames: Int? = null
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!secondaryText.isNullOrBlank()) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (durationMs > 0L) {
            val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
            val endTime = remember(remainingMs / 60_000) {
                val formatter = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                formatter.format(java.util.Date(System.currentTimeMillis() + remainingMs))
            }
            Text(
                text = stringResource(R.string.player_ends_at, endTime),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            seekThumbnailCachePercent?.let { percent ->
                val frameProgress = if (
                    seekThumbnailCachedFrames != null && seekThumbnailTotalFrames != null &&
                    seekThumbnailTotalFrames > 0
                ) {
                    " (${seekThumbnailCachedFrames.coerceAtLeast(0)}/${seekThumbnailTotalFrames})"
                } else ""
                Text(
                    text = stringResource(
                        R.string.player_thumbnail_cache,
                        percent.coerceIn(0, 100),
                        frameProgress
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun PauseBrandOverlay(
    logoUrl: String?,
    primaryText: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 66.dp, end = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = primaryText,
                    modifier = Modifier
                        .width(360.dp)
                        .height(120.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
private fun PlayerErrorOverlay(
    errorMessage: String,
    onBack: () -> Unit,
    onNextSource: (() -> Unit)? = null,
    onSwitchSource: (() -> Unit)? = null
) {
    val backFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { backFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PlayerStatusPill(
                text = errorMessage,
                background = Color(0xFF8B1E1E).copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerErrorButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.common_back),
                    onClick = onBack,
                    focusRequester = backFocusRequester
                )
                if (onNextSource != null) {
                    PlayerErrorButton(
                        icon = Icons.Default.SkipNext,
                        label = stringResource(R.string.player_next_source),
                        onClick = onNextSource
                    )
                }
                if (onSwitchSource != null) {
                    PlayerErrorButton(
                        icon = Icons.Default.SwapHoriz,
                        label = stringResource(R.string.player_switch_source),
                        onClick = onSwitchSource
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerErrorButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.07f))
            .border(
                if (isFocused) 2.dp else 1.dp,
                if (isFocused) accentColor else Color.White.copy(0.15f),
                RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isFocused) accentColor else Color.White.copy(0.8f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            color = if (isFocused) accentColor else Color.White.copy(0.8f),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}

@Composable
private fun PlayerStatusPill(
    text: String,
    background: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun LoadingOverlay(
    torrentProgress: TorrentProgress? = null,
    logoUrl: String? = null,
    primaryText: String
) {
    val rotation: Float
    val pulse: Float
    val brandAlpha: Float
    if (!SaabMotion.animationsEnabled) {
        rotation = 0f
        pulse = 1f
        brandAlpha = 1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "videoLoader")
        val animatedRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "videoLoaderRotation"
        )
        val animatedPulse by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850),
                repeatMode = RepeatMode.Reverse
            ),
            label = "videoLoaderPulse"
        )
        val animatedBrandAlpha by infiniteTransition.animateFloat(
            initialValue = 0.38f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850),
                repeatMode = RepeatMode.Reverse
            ),
            label = "videoLoaderBrandAlpha"
        )
        rotation = animatedRotation
        pulse = animatedPulse
        brandAlpha = animatedBrandAlpha
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .testTag("playback-loading-overlay"),
        contentAlignment = Alignment.Center
    ) {
        val progress = torrentProgress?.progress
        val animatedProgress by animateFloatAsState(
            targetValue = progress ?: 0.72f,
            animationSpec = tween(SaabMotion.duration(300)),
            label = "preload"
        )

        val loaderLogo = logoUrl?.takeIf(String::isNotBlank)
        var logoLoaded by remember(loaderLogo) { mutableStateOf(false) }
        if (loaderLogo != null) {
            Box(Modifier.width(360.dp).height(120.dp), contentAlignment = Alignment.Center) {
                if (!logoLoaded) {
                    PlaybackLoaderProgress(progress, animatedProgress, rotation, pulse)
                }
                AsyncImage(
                    model = loaderLogo,
                    contentDescription = primaryText,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(pulse)
                        .alpha(brandAlpha)
                        .testTag("playback-loading-logo"),
                    contentScale = ContentScale.Fit,
                    onSuccess = { logoLoaded = true },
                    onError = { logoLoaded = false }
                )
            }
        } else {
            PlaybackLoaderProgress(progress, animatedProgress, rotation, pulse)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 170.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (progress != null) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            if (torrentProgress != null) {
                Text(
                    text = torrentProgress.status,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (torrentProgress.peers > 0 || torrentProgress.downloadSpeed > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val parts = mutableListOf<String>()
                    if (torrentProgress.downloadSpeed > 0) {
                        parts.add(formatSpeed(torrentProgress.downloadSpeed))
                    }
                    if (torrentProgress.peers > 0) {
                        parts.add("${torrentProgress.peers} peers")
                    }
                    if (torrentProgress.seeds > 0) {
                        parts.add("${torrentProgress.seeds} seeds")
                    }
                    Text(
                        text = parts.joinToString("  \u2022  "),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackLoaderProgress(
    progress: Float?,
    animatedProgress: Float,
    rotation: Float,
    pulse: Float
) {
    Box(
        modifier = Modifier.size(72.dp).scale(pulse).testTag("playback-loading-spinner"),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxSize()
                .rotate(if (progress == null) rotation else 0f),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.14f),
            strokeWidth = 4.dp
        )
        CircularProgressIndicator(
            progress = { 0.22f },
            modifier = Modifier
                .size(54.dp)
                .rotate(-rotation * 1.35f),
            color = Color.White.copy(alpha = 0.72f),
            trackColor = Color.Transparent,
            strokeWidth = 2.dp
        )
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> "${"%.1f".format(bytesPerSec / 1_048_576.0)} MB/s"
        bytesPerSec >= 1_024 -> "${"%.0f".format(bytesPerSec / 1_024.0)} KB/s"
        else -> "$bytesPerSec B/s"
    }
}

@Composable
internal fun BoxScope.AudioSelectionSidePanel(
    visible: Boolean,
    title: String,
    audioTracks: List<PlayerTrackOption>,
    sources: List<PlayerSourceOption> = emptyList(),
    currentSourceId: String? = null,
    selectedAudioId: String?,
    preferredAudioLanguages: List<String> = emptyList(),
    onClose: () -> Unit,
    onSelectTrack: (String?) -> Unit,
    onSelectSource: (String) -> Unit = {},
    onSelectSourceForLanguage: (sourceId: String, language: String) -> Unit = { sourceId, _ -> onSelectSource(sourceId) }
) {
    if (!visible) return

    val sourceAudioOptions = remember(sources) {
        buildSourceAudioOptions(sources)
    }
    val selectionOptions = remember(audioTracks, sourceAudioOptions) {
        audioTracks + sourceAudioOptions
    }
    val selectedAudioOptionId = remember(selectedAudioId, currentSourceId, sourceAudioOptions, audioTracks) {
        selectedAudioId?.takeIf { id -> audioTracks.any { it.id == id } }
            ?: sourceAudioOptions.firstOrNull { it.sourceId == currentSourceId }?.id
            ?: audioTracks.firstOrNull { it.selected }?.id
    }
    val languageGroups = remember(audioTracks, sourceAudioOptions, preferredAudioLanguages) {
        buildAudioLanguageGroups(selectionOptions, preferredAudioLanguages)
    }
    val preferredLanguageGroups = remember(languageGroups, preferredAudioLanguages) {
        languageGroups.filter { group ->
            AudioTrackPreferencePolicy.languageRank(group.key, preferredAudioLanguages) != Int.MAX_VALUE
        }
    }
    val otherLanguageGroups = remember(languageGroups, preferredLanguageGroups) {
        languageGroups.filterNot { group -> group in preferredLanguageGroups }
    }
    val selectedGroupKeyFromTrack = remember(languageGroups, selectedAudioOptionId) {
        resolveSelectedAudioLanguageKey(
            groups = languageGroups,
            selectedAudioId = selectedAudioOptionId
        )
    }

    var selectedLanguageKey by remember(visible, selectedGroupKeyFromTrack, languageGroups) {
        mutableStateOf(
            selectedGroupKeyFromTrack ?: languageGroups.firstOrNull()?.key
        )
    }
    var optimisticSelectedAudioId by remember(visible) {
        mutableStateOf<String?>(null)
    }
    val effectiveSelectedAudioId = optimisticSelectedAudioId ?: selectedAudioOptionId

    val selectedLanguageGroup = languageGroups.firstOrNull { it.key == selectedLanguageKey }
        ?: languageGroups.firstOrNull()
    val selectedLanguageTracks = selectedLanguageGroup?.tracks.orEmpty()
    val selectedTrackIndex = remember(selectedLanguageTracks, effectiveSelectedAudioId) {
        val index = selectedLanguageTracks.indexOfFirst { track -> track.id == effectiveSelectedAudioId }
        if (index >= 0) index else 0
    }
    val selectedLanguageIndex = remember(languageGroups, selectedLanguageKey) {
        val index = languageGroups.indexOfFirst { group -> group.key == selectedLanguageKey }
        if (index >= 0) index else 0
    }
    val languageGroupsStructureKey = remember(languageGroups) {
        languageGroups.joinToString(separator = "|") { group ->
            "${group.key}:${group.tracks.size}"
        }
    }

    val languageFocusRequesters = remember(languageGroups.size) {
        List(languageGroups.size) { FocusRequester() }
    }
    val trackFocusRequesters = remember(selectedLanguageTracks.size) {
        List(selectedLanguageTracks.size) { FocusRequester() }
    }
    val selectedTrackFocusRequester = trackFocusRequesters.getOrNull(selectedTrackIndex)
    val panelScope = rememberCoroutineScope()
    val languageListState = rememberLazyListState()
    val trackListState = rememberLazyListState()
    val selectedTrack = remember(selectionOptions, effectiveSelectedAudioId) {
        selectionOptions.firstOrNull { track -> track.id == effectiveSelectedAudioId }
            ?: selectionOptions.firstOrNull { track -> track.selected }
    }
    val preferenceSummary = remember(preferredAudioLanguages) {
        AudioTrackPreferencePolicy.normalizePreferences(preferredAudioLanguages)
            .map { language -> subtitleLanguageDisplayName(language, language) }
            .joinToString(" › ")
    }
    val currentTrackFormat = stringResource(R.string.player_current_track)
    val noTrackLabel = stringResource(R.string.common_none)
    val unknownLanguageLabel = stringResource(R.string.sort_filter_unknown)
    val offLanguageLabel = stringResource(R.string.common_off)
    val currentSelectionText = remember(
        selectedTrack,
        selectedLanguageGroup,
        currentTrackFormat,
        noTrackLabel,
        unknownLanguageLabel,
        offLanguageLabel
    ) {
        when {
            selectedTrack == null -> currentTrackFormat.format(noTrackLabel)
            else -> {
                val language = localizedTrackLanguageLabel(
                    selectedLanguageGroup?.displayName,
                    unknownLanguageLabel,
                    offLanguageLabel
                )
                val format = selectedTrack.audioFormat
                val currentTrack = if (format != null) "$language - $format" else language
                currentTrackFormat.format(currentTrack)
            }
        }
    }
    var lastFocusedLanguageIndex by remember(visible, languageGroupsStructureKey) {
        mutableIntStateOf(selectedLanguageIndex)
    }

    LaunchedEffect(visible, languageGroupsStructureKey) {
        if (!visible || languageGroups.isEmpty()) return@LaunchedEffect

        val targetKey = selectedGroupKeyFromTrack
            ?: selectedLanguageKey
            ?: languageGroups.firstOrNull()?.key
            ?: return@LaunchedEffect
        selectedLanguageKey = targetKey

        val targetIndex = languageGroups.indexOfFirst { group -> group.key == targetKey }
            .takeIf { it >= 0 }
            ?: 0
        lastFocusedLanguageIndex = targetIndex

        val targetListIndex = if (preferredLanguageGroups.any { it.key == targetKey }) {
            targetIndex + 1 // Preferred section heading precedes preferred entries.
        } else {
            val otherIndex = otherLanguageGroups.indexOfFirst { it.key == targetKey }.coerceAtLeast(0)
            otherIndex + preferredLanguageGroups.size +
                (if (preferredLanguageGroups.isNotEmpty()) 2 else 1)
        }
        runCatching { languageListState.scrollToItem(targetListIndex) }
        withFrameNanos { }
        runCatching { languageFocusRequesters[targetIndex].requestFocus() }
    }

    LaunchedEffect(selectedAudioOptionId, languageGroups) {
        val key = resolveSelectedAudioLanguageKey(languageGroups, selectedAudioOptionId) ?: return@LaunchedEffect
        selectedLanguageKey = key
    }
    LaunchedEffect(selectedAudioOptionId, languageGroups, optimisticSelectedAudioId) {
        val optimisticId = optimisticSelectedAudioId ?: return@LaunchedEffect
        val selectedLanguage = resolveSelectedAudioLanguageKey(languageGroups, selectedAudioOptionId)
        val requestedLanguage = resolveSelectedAudioLanguageKey(languageGroups, optimisticId)
        if (selectedAudioOptionId == optimisticId ||
            (selectedLanguage != null && selectedLanguage == requestedLanguage)
        ) {
            optimisticSelectedAudioId = null
        }
    }

    val moveFocusToSelectedTrack = remember(
        selectedLanguageTracks,
        selectedTrackIndex,
        selectedTrackFocusRequester
    ) {
        {
            if (selectedLanguageTracks.isEmpty() || selectedTrackFocusRequester == null) {
                true
            } else {
                panelScope.launch {
                    runCatching { trackListState.scrollToItem(selectedTrackIndex) }
                    withFrameNanos { }
                    runCatching { selectedTrackFocusRequester.requestFocus() }
                }
                true
            }
        }
    }
    val requestAudioSelection: (String?) -> Unit = remember(
        panelScope,
        effectiveSelectedAudioId,
        onSelectTrack,
        selectionOptions,
        onSelectSource,
        onSelectSourceForLanguage
    ) {
        { targetTrackId ->
            if (targetTrackId != null && targetTrackId != effectiveSelectedAudioId) {
                optimisticSelectedAudioId = targetTrackId
                panelScope.launch {
                    selectionOptions.firstOrNull { it.id == targetTrackId }?.let { option ->
                        if (option.sourceId != null) {
                            onSelectSourceForLanguage(option.sourceId, option.language.orEmpty())
                        } else {
                            onSelectTrack(option.id)
                        }
                    }
                }
            }
        }
    }

    GlassSidebarScaffold(
        visible = visible,
        onDismiss = onClose,
        panelWidth = 500.dp,
        panelPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        overlayAlpha = 0.45f
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentSelectionText,
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (preferenceSummary.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.player_audio_preference_order, preferenceSummary),
                            color = Color.White.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.playback_languages),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(0.46f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.player_tracks),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(0.54f)
                )
            }

            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        state = languageListState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        if (preferredLanguageGroups.isNotEmpty()) {
                            item(key = "preferred-audio-language-heading") {
                                AudioLanguageSectionHeading(
                                    text = stringResource(R.string.player_preferred_languages)
                                )
                            }
                        }
                        itemsIndexed(
                            items = preferredLanguageGroups,
                            key = { _, group -> group.key }
                        ) { index, group ->
                            val groupIndex = languageGroups.indexOfFirst { it.key == group.key }
                            AudioLanguageListItem(
                                group = group,
                                selectedLanguage = group.key == selectedLanguageGroup?.key,
                                activeTrackInGroup = group.tracks.any { it.id == effectiveSelectedAudioId },
                                rightFocusRequester = selectedTrackFocusRequester,
                                onMoveRight = moveFocusToSelectedTrack,
                                focusRequester = languageFocusRequesters[groupIndex],
                                onFocused = {
                                    lastFocusedLanguageIndex = groupIndex
                                },
                                onClick = {
                                    selectedLanguageKey = group.key
                                    val topTrack = group.tracks.firstOrNull() ?: return@AudioLanguageListItem
                                    requestAudioSelection(topTrack.id)
                                }
                            )
                        }
                        if (otherLanguageGroups.isNotEmpty()) {
                            item(key = "other-audio-language-heading") {
                                AudioLanguageSectionHeading(
                                    text = stringResource(R.string.player_other_languages)
                                )
                            }
                        }
                        itemsIndexed(
                            items = otherLanguageGroups,
                            key = { _, group -> group.key }
                        ) { _, group ->
                            val groupIndex = languageGroups.indexOfFirst { it.key == group.key }
                            AudioLanguageListItem(
                                group = group,
                                selectedLanguage = group.key == selectedLanguageGroup?.key,
                                activeTrackInGroup = group.tracks.any { it.id == effectiveSelectedAudioId },
                                rightFocusRequester = selectedTrackFocusRequester,
                                onMoveRight = moveFocusToSelectedTrack,
                                focusRequester = languageFocusRequesters[groupIndex],
                                onFocused = { lastFocusedLanguageIndex = groupIndex },
                                onClick = {
                                    selectedLanguageKey = group.key
                                    val topTrack = group.tracks.firstOrNull() ?: return@AudioLanguageListItem
                                    requestAudioSelection(topTrack.id)
                                }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.3f))
                )

                Box(
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight()
                ) {
                    if (selectedLanguageTracks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.player_no_audio_tracks),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                    } else {
                        LazyColumn(
                            state = trackListState,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            itemsIndexed(
                                items = selectedLanguageTracks,
                                key = { _, track -> track.id }
                            ) { index, track ->
                                AudioVariantListItem(
                                    track = track,
                                    selected = track.id == effectiveSelectedAudioId,
                                    leftFocusRequester = languageFocusRequesters.getOrNull(lastFocusedLanguageIndex),
                                    focusRequester = trackFocusRequesters.getOrNull(index),
                                    onClick = { requestAudioSelection(track.id) }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLanguageSectionHeading(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.68f),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun AudioLanguageListItem(
    group: AudioLanguageGroup,
    selectedLanguage: Boolean,
    activeTrackInGroup: Boolean,
    onClick: () -> Unit,
    onMoveRight: (() -> Boolean)? = null,
    rightFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val targetBackground = when {
        isFocused -> Color.White.copy(alpha = 0.95f)
        selectedLanguage -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        else -> Color.Transparent
    }

    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "audioLanguageBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black else Color.White.copy(alpha = 0.98f),
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "audioLanguageText"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selectedLanguage && !isFocused) Color.White.copy(alpha = 0.38f) else Color.Transparent,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "audioLanguageBorder"
    )

    val languageLabel = localizedTrackLanguageLabel(
        group.displayName,
        stringResource(R.string.sort_filter_unknown),
        stringResource(R.string.common_off)
    )
    val labelText = buildString {
        if (activeTrackInGroup) append("\u2022 ")
        append(languageLabel)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (rightFocusRequester != null) right = rightFocusRequester
            }
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused()
            }
            .onPreviewKeyEvent { keyEvent ->
                if (
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    onMoveRight?.invoke() == true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = labelText,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AudioVariantListItem(
    track: PlayerTrackOption,
    selected: Boolean,
    onClick: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val targetBackground = when {
        isFocused -> Color.White.copy(alpha = 0.93f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.44f)
        else -> Color.Transparent
    }

    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "audioVariantBackground"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.Black
            else -> Color.White.copy(alpha = if (selected) 1f else 0.92f)
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "audioVariantText"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
            selected -> Color.White.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "audioVariantBorder"
    )

    val formatChip = remember(track.audioFormat) {
        track.audioFormat
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    val labelText = buildString {
        if (selected) append("\u2022 ")
        append(track.label)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (formatChip != null) {
                SubtitleMetaChip(
                    text = formatChip,
                    inverted = isFocused
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.SubtitleSelectionSidePanel(
    visible: Boolean,
    title: String,
    subtitleTracks: List<PlayerTrackOption>,
    selectedSubtitleId: String?,
    onClose: () -> Unit,
    onSelectTrack: (String?) -> Unit,
    onShowOffsetBar: () -> Unit = {},
    onShowSizeBar: () -> Unit = {},
    onShowDelayBar: () -> Unit = {},
    onShowColorBar: () -> Unit = {}
) {
    if (!visible) return

    val languageGroups = remember(subtitleTracks) {
        buildSubtitleLanguageGroups(subtitleTracks)
    }
    val selectedGroupKeyFromTrack = remember(languageGroups, selectedSubtitleId) {
        resolveSelectedSubtitleLanguageKey(
            groups = languageGroups,
            selectedSubtitleId = selectedSubtitleId
        )
    }

    var selectedLanguageKey by remember(visible, selectedGroupKeyFromTrack, languageGroups) {
        mutableStateOf(
            selectedGroupKeyFromTrack ?: languageGroups.firstOrNull()?.key
        )
    }
    var optimisticSelectedSubtitleId by remember(visible) {
        mutableStateOf<String?>(null)
    }
    val effectiveSelectedSubtitleId = optimisticSelectedSubtitleId ?: selectedSubtitleId

    val selectedLanguageGroup = languageGroups.firstOrNull { it.key == selectedLanguageKey }
        ?: languageGroups.firstOrNull()
    val selectedLanguageTracks = if (selectedLanguageGroup?.isOffGroup == true) {
        emptyList()
    } else {
        selectedLanguageGroup?.tracks.orEmpty()
    }
    val selectedTrackIndex = remember(selectedLanguageTracks, effectiveSelectedSubtitleId) {
        val index = selectedLanguageTracks.indexOfFirst { track -> track.id == effectiveSelectedSubtitleId }
        if (index >= 0) index else 0
    }
    val selectedLanguageIndex = remember(languageGroups, selectedLanguageKey) {
        val index = languageGroups.indexOfFirst { group -> group.key == selectedLanguageKey }
        if (index >= 0) index else 0
    }
    val languageGroupsStructureKey = remember(languageGroups) {
        languageGroups.joinToString(separator = "|") { group ->
            "${group.key}:${group.tracks.size}"
        }
    }

    val languageFocusRequesters = remember(languageGroups.size) {
        List(languageGroups.size) { FocusRequester() }
    }
    val trackFocusRequesters = remember(selectedLanguageTracks.size) {
        List(selectedLanguageTracks.size) { FocusRequester() }
    }
    val selectedTrackFocusRequester = trackFocusRequesters.getOrNull(selectedTrackIndex)
    val panelScope = rememberCoroutineScope()
    val languageListState = rememberLazyListState()
    val trackListState = rememberLazyListState()
    val selectedTrack = remember(subtitleTracks, effectiveSelectedSubtitleId) {
        subtitleTracks.firstOrNull { track -> track.id == effectiveSelectedSubtitleId }
            ?: subtitleTracks.firstOrNull { track -> track.selected }
    }
    val offLabel = stringResource(R.string.common_off)
    val currentTrackFormat = stringResource(R.string.player_current_track)
    val unknownLanguageLabel = stringResource(R.string.sort_filter_unknown)
    val currentSelectionText = remember(
        selectedTrack,
        selectedLanguageGroup,
        offLabel,
        currentTrackFormat,
        unknownLanguageLabel
    ) {
        when {
            selectedTrack == null || isSubtitleOffTrack(selectedTrack) ->
                currentTrackFormat.format(offLabel)
            else -> {
                val language = localizedTrackLanguageLabel(
                    selectedLanguageGroup?.displayName,
                    unknownLanguageLabel,
                    offLabel
                )
                currentTrackFormat.format("$language - ${selectedTrack.label}")
            }
        }
    }
    var lastFocusedLanguageIndex by remember(visible, languageGroupsStructureKey) {
        mutableIntStateOf(selectedLanguageIndex)
    }

    LaunchedEffect(visible, languageGroupsStructureKey) {
        if (!visible || languageGroups.isEmpty()) return@LaunchedEffect

        val targetKey = selectedGroupKeyFromTrack
            ?: selectedLanguageKey
            ?: languageGroups.firstOrNull()?.key
            ?: return@LaunchedEffect
        selectedLanguageKey = targetKey

        val targetIndex = languageGroups.indexOfFirst { group -> group.key == targetKey }
            .takeIf { it >= 0 }
            ?: 0
        lastFocusedLanguageIndex = targetIndex

        runCatching { languageListState.scrollToItem(targetIndex) }
        withFrameNanos { }
        runCatching { languageFocusRequesters[targetIndex].requestFocus() }
    }

    LaunchedEffect(selectedSubtitleId, languageGroups) {
        val key = resolveSelectedSubtitleLanguageKey(languageGroups, selectedSubtitleId) ?: return@LaunchedEffect
        selectedLanguageKey = key
    }
    LaunchedEffect(selectedSubtitleId) {
        if (
            selectedSubtitleId != null &&
            optimisticSelectedSubtitleId != null &&
            selectedSubtitleId == optimisticSelectedSubtitleId
        ) {
            optimisticSelectedSubtitleId = null
        }
    }

    val moveFocusToSelectedSubtitle = remember(
        selectedLanguageTracks,
        selectedTrackIndex,
        selectedTrackFocusRequester
    ) {
        {
            if (selectedLanguageTracks.isEmpty() || selectedTrackFocusRequester == null) {
                true
            } else {
                panelScope.launch {
                    runCatching { trackListState.scrollToItem(selectedTrackIndex) }
                    withFrameNanos { }
                    runCatching { selectedTrackFocusRequester.requestFocus() }
                }
                true
            }
        }
    }
    val requestSubtitleSelection: (String?) -> Unit = remember(
        panelScope,
        effectiveSelectedSubtitleId,
        onSelectTrack
    ) {
        { targetTrackId ->
            val resolvedTargetId = targetTrackId ?: SUBTITLE_OFF_TRACK_ID
            if (resolvedTargetId != effectiveSelectedSubtitleId) {
                optimisticSelectedSubtitleId = resolvedTargetId
                panelScope.launch {
                    onSelectTrack(targetTrackId)
                }
            }
        }
    }

    GlassSidebarScaffold(
        visible = visible,
        onDismiss = onClose,
        panelWidth = 500.dp,
        panelPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        overlayAlpha = 0.45f
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onShowOffsetBar,
                    colors = playerIconButtonColors(Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerticalAlignCenter,
                        contentDescription = stringResource(R.string.player_subtitle_offset),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = onShowSizeBar,
                    colors = playerIconButtonColors(Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FormatSize,
                        contentDescription = stringResource(R.string.player_subtitle_size),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = onShowDelayBar,
                    colors = playerIconButtonColors(Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.player_subtitle_delay),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = onShowColorBar,
                    colors = playerIconButtonColors(Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.palette_icon),
                        contentDescription = stringResource(R.string.player_subtitle_color),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentSelectionText,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.playback_languages),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(0.46f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.player_subtitles_control),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(0.54f)
                )
            }

            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        state = languageListState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        itemsIndexed(
                            items = languageGroups,
                            key = { _, group -> group.key }
                        ) { index, group ->
                            SubtitleLanguageListItem(
                                group = group,
                                selectedLanguage = group.key == selectedLanguageGroup?.key,
                                activeTrackInGroup = group.tracks.any { it.id == effectiveSelectedSubtitleId },
                                rightFocusRequester = selectedTrackFocusRequester,
                                onMoveRight = moveFocusToSelectedSubtitle,
                                focusRequester = languageFocusRequesters[index],
                                onFocused = {
                                    lastFocusedLanguageIndex = index
                                },
                                onClick = {
                                    selectedLanguageKey = group.key
                                    val topTrack = group.tracks.firstOrNull() ?: return@SubtitleLanguageListItem
                                    requestSubtitleSelection(topTrack.id)
                                }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.3f))
                )

                Box(
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight()
                ) {
                    if (selectedLanguageGroup?.isOffGroup == true) {
                        Text(
                            text = stringResource(R.string.player_subtitles_off),
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                    } else if (selectedLanguageTracks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.player_no_subtitles),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                    } else {
                        LazyColumn(
                            state = trackListState,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            itemsIndexed(
                                items = selectedLanguageTracks,
                                key = { _, track -> track.id }
                            ) { index, track ->
                                SubtitleVariantListItem(
                                    track = track,
                                    selected = track.id == effectiveSelectedSubtitleId,
                                    leftFocusRequester = languageFocusRequesters.getOrNull(lastFocusedLanguageIndex),
                                    focusRequester = trackFocusRequesters.getOrNull(index),
                                    enabled = track.supported,
                                    onClick = { requestSubtitleSelection(track.id) }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleLanguageListItem(
    group: SubtitleLanguageGroup,
    selectedLanguage: Boolean,
    activeTrackInGroup: Boolean,
    onClick: () -> Unit,
    onMoveRight: (() -> Boolean)? = null,
    rightFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    val unknownLanguageLabel = stringResource(R.string.sort_filter_unknown)
    val displayName = if (group.isOffGroup) {
        stringResource(R.string.common_off)
    } else {
        localizedTrackLanguageLabel(
            group.displayName,
            unknownLanguageLabel,
            stringResource(R.string.common_off)
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val targetBackground = when {
        isFocused -> Color.White.copy(alpha = 0.95f)
        selectedLanguage -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        else -> Color.Transparent
    }

    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "subtitleLanguageBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black else Color.White.copy(alpha = 0.98f),
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "subtitleLanguageText"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selectedLanguage && !isFocused) Color.White.copy(alpha = 0.38f) else Color.Transparent,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "subtitleLanguageBorder"
    )

    val labelText = buildString {
        if (activeTrackInGroup) append("\u2022 ")
        append(displayName)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (rightFocusRequester != null) right = rightFocusRequester
            }
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused()
            }
            .onPreviewKeyEvent { keyEvent ->
                if (
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    onMoveRight?.invoke() == true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = labelText,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubtitleVariantListItem(
    track: PlayerTrackOption,
    selected: Boolean,
    onClick: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val targetBackground = when {
        !enabled -> Color.White.copy(alpha = 0.06f)
        isFocused -> Color.White.copy(alpha = 0.93f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.44f)
        else -> Color.Transparent
    }

    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "subtitleVariantBackground"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.48f)
            isFocused -> Color.Black
            else -> Color.White.copy(alpha = if (selected) 1f else 0.92f)
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "subtitleVariantText"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused && enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
            selected -> Color.White.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "subtitleVariantBorder"
    )
    val formatChip = remember(track.subtitleFormat) {
        track.subtitleFormat
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase(Locale.ROOT)
    }
    val embeddedLabel = stringResource(R.string.player_subtitle_embedded)
    val streamLabel = stringResource(R.string.player_subtitle_stream)
    val addonLabel = stringResource(R.string.player_subtitle_addon)
    val unsupportedLabel = stringResource(R.string.player_subtitle_unsupported)
    val subtitleFallbackLabel = stringResource(R.string.player_subtitle_track)
    val offLabel = stringResource(R.string.common_off)
    val chips = remember(track, embeddedLabel, streamLabel, addonLabel, unsupportedLabel) {
        buildSubtitleTrackChips(
            track,
            embeddedLabel,
            streamLabel,
            addonLabel,
            unsupportedLabel
        )
    }
    val displayLabel = remember(track.label, track.language, track.id, subtitleFallbackLabel, offLabel) {
        buildSubtitleVariantDisplayLabel(track, subtitleFallbackLabel, offLabel)
    }

    val labelText = buildString {
        if (selected) append("\u2022 ")
        append(displayLabel)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (formatChip != null) {
                    SubtitleMetaChip(
                        text = formatChip,
                        inverted = isFocused
                    )
                }
            }
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { chip ->
                        SubtitleMetaChip(
                            text = chip,
                            inverted = isFocused
                        )
                    }
                }
            }
        }
    }
}

// --- SUBTITLE COLOR PRESETS FOR PLAYER ---

private data class PlayerColorPreset(val labelResource: Int, val colorValue: Int)

private val PLAYER_TEXT_COLORS = listOf(
    PlayerColorPreset(R.string.subtitle_color_white, 0xFFFFFFFF.toInt()),
    PlayerColorPreset(R.string.subtitle_color_gray, 0xFFBDBDBD.toInt()),
    PlayerColorPreset(R.string.subtitle_color_yellow, 0xFFFFEB3B.toInt()),
    PlayerColorPreset(R.string.subtitle_color_cyan, 0xFF00BCD4.toInt()),
    PlayerColorPreset(R.string.subtitle_color_red, 0xFFF44336.toInt()),
    PlayerColorPreset(R.string.subtitle_color_orange, 0xFFFF9800.toInt()),
    PlayerColorPreset(R.string.subtitle_color_green, 0xFF8BC34A.toInt())
)

private val PLAYER_BACKGROUND_COLORS = listOf(
    PlayerColorPreset(R.string.subtitle_background_none, 0x00000000),
    PlayerColorPreset(R.string.subtitle_background_black, 0xFF000000.toInt()),
    PlayerColorPreset(R.string.subtitle_background_semi, 0x80000000.toInt()),
    PlayerColorPreset(R.string.subtitle_background_dark, 0xFF212121.toInt())
)

@Composable
internal fun PlayerSubtitleColorChip(
    color: Color,
    isSelected: Boolean,
    contentDescription: String,
    isTransparent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (isTransparent) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                } else {
                    Modifier.background(color)
                }
            )
            .then(
                if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                else if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .semantics {
                this.contentDescription = contentDescription
                selected = isSelected
            }
    )
}

@Composable
private fun SubtitleMetaChip(
    text: String,
    inverted: Boolean
) {
    val background = if (inverted) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.14f)
    val contentColor = if (inverted) Color.Black.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.9f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
internal fun BoxScope.PlayerSourceSidebar(
    visible: Boolean,
    title: String,
    sources: List<PlayerSourceOption>,
    sourceLanguagePreferences: List<String> = emptyList(),
    currentSourceId: String?,
    onClose: () -> Unit,
    onLanguageSelectSource: (String) -> Unit,
    onSelectSource: (String) -> Unit
) {
    val sourceStreams = remember(sources) { sources.map(PlayerSourceOption::toStream) }

    val sidebarState = if (visible) {
        SidebarState.Sources(
            streamTitle = title,
            streams = sourceStreams,
            selectedStreamId = currentSourceId,
            showBestLanguageOptions = true
        )
    } else {
        SidebarState.Closed
    }

    GlassSidebar(
        state = sidebarState,
        sourceLanguagePreferences = sourceLanguagePreferences,
        onEpisodeSelected = {},
        onSourceSelected = { stream ->
            val url = stream.url ?: return@GlassSidebar
            val sourceId = sourceOptionId(url, stream.fileIdx ?: -1, stream.behaviorHints?.filename.orEmpty())
            onSelectSource(sourceId)
        },
        onLanguageSourceSelected = { stream ->
            val url = stream.url ?: return@GlassSidebar
            val sourceId = sourceOptionId(url, stream.fileIdx ?: -1, stream.behaviorHints?.filename.orEmpty())
            onLanguageSelectSource(sourceId)
        },
        onBack = onClose,
        onDismiss = onClose
    )
}

@Composable
private fun BoxScope.PlayerEpisodeSidebar(
    visible: Boolean,
    episodes: List<MetaVideo>,
    currentPlaybackId: String?,
    onClose: () -> Unit,
    onEpisodeSelected: (MetaVideo) -> Unit
) {
    val sidebarState = if (visible) {
        SidebarState.Episodes(episodes)
    } else {
        SidebarState.Closed
    }

    GlassSidebar(
        state = sidebarState,
        currentEpisodeId = currentPlaybackId,
        onEpisodeSelected = onEpisodeSelected,
        onSourceSelected = {},
        onBack = onClose,
        onDismiss = onClose
    )
}

@Composable
private fun BoxScope.EpisodeSwitchSourceSidebar(
    visible: Boolean,
    title: String,
    sources: List<PlayerSourceOption>?,
    onClose: () -> Unit,
    onSelectSource: (String) -> Unit
) {
    val sourceStreams = remember(sources) { sources?.map(PlayerSourceOption::toStream) }

    val sidebarState = if (visible) {
        SidebarState.Sources(
            streamTitle = title,
            streams = sourceStreams,
            selectedStreamId = null,
            showBestLanguageOptions = true
        )
    } else {
        SidebarState.Closed
    }

    GlassSidebar(
        state = sidebarState,
        onEpisodeSelected = {},
        onSourceSelected = { stream ->
            val url = stream.url ?: return@GlassSidebar
            onSelectSource(sourceOptionId(url, stream.fileIdx ?: -1, stream.behaviorHints?.filename.orEmpty()))
        },
        onBack = onClose,
        onDismiss = onClose
    )
}

@Composable
private fun BoxScope.SelectionSidePanel(
    visible: Boolean,
    title: String,
    items: List<PanelItem>,
    selectedId: String?,
    onClose: () -> Unit,
    onSelect: (PanelItem) -> Unit
) {
    if (!visible) return

    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(visible, items) {
        if (!visible || items.isEmpty()) return@LaunchedEffect
        delay(120)
        runCatching { firstItemFocusRequester.requestFocus() }
    }

    GlassSidebarScaffold(
        visible = visible,
        onDismiss = onClose,
        panelWidth = 500.dp,
        overlayAlpha = 0.45f,
        panelAlignment = Alignment.CenterEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxHeight()
            ) {
                itemsIndexed(items) { index, item ->
                    PanelListItem(
                        item = item,
                        selected = item.id == selectedId,
                        focusRequester = if (index == 0) firstItemFocusRequester else null,
                        onClick = { onSelect(item) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun PanelListItem(
    item: PanelItem,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val targetBackground = when {
        isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        selected -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.06f)
    }

    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "panelItemBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "panelItemBorder"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        text = item.subtitle,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.PlaybackCastPanel(
    visible: Boolean,
    castMembers: List<TmdbCastInfo>,
    isLoading: Boolean,
    onClose: () -> Unit,
    backFocusRequester: FocusRequester,
    firstCastFocusRequester: FocusRequester
) {
    if (!visible) return

    val backInteraction = remember { MutableInteractionSource() }
    val backFocused by backInteraction.collectIsFocusedAsState()
    val backLabel = stringResource(R.string.player_back_to_playback)
    val listState = rememberLazyListState()
    val castFocusRequesters = remember(castMembers, firstCastFocusRequester) {
        List(castMembers.size) { index ->
            if (index == 0) firstCastFocusRequester else FocusRequester()
        }
    }
    LaunchedEffect(visible) {
        delay(120)
        runCatching { backFocusRequester.requestFocus() }
    }

    GlassSidebarScaffold(
        visible = visible,
        onDismiss = onClose,
        panelWidth = 360.dp,
        panelPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        overlayAlpha = 0.45f,
        panelAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("playback-cast-panel"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (backFocused) Color.White else Color.White.copy(alpha = 0.12f))
                        .border(
                            1.dp,
                            if (backFocused) Color.White else Color.White.copy(alpha = 0.35f),
                            RoundedCornerShape(999.dp)
                        )
                        .focusRequester(backFocusRequester)
                        .focusProperties { down = firstCastFocusRequester }
                        .clickable(
                            interactionSource = backInteraction,
                            indication = null,
                            onClick = onClose
                        )
                        .focusable(interactionSource = backInteraction)
                        .semantics {
                            contentDescription = backLabel
                            role = Role.Button
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = if (backFocused) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = backLabel,
                        color = if (backFocused) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Text(
                    text = stringResource(R.string.player_cast_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            when {
                isLoading -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(20.dp)
                )
                castMembers.isEmpty() -> Text(
                    text = stringResource(R.string.player_no_cast_available),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .focusGroup()
                        .testTag("playback-cast")
                ) {
                    itemsIndexed(castMembers, key = { index, person -> person.tmdbId ?: "cast-$index" }) { index, member ->
                        val castFocusRequester = castFocusRequesters[index]
                        val nextCastFocusRequester = castFocusRequesters.getOrNull(index + 1)
                        PlaybackCastMemberRow(
                            member = member,
                            modifier = Modifier
                                .focusRequester(castFocusRequester)
                                .focusProperties {
                                    up = if (index == 0) backFocusRequester else castFocusRequesters[index - 1]
                                    if (nextCastFocusRequester != null) down = nextCastFocusRequester
                                },
                            avatarTestTag = "playback-cast-avatar-$index"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackCastMemberRow(
    member: TmdbCastInfo,
    modifier: Modifier = Modifier,
    avatarTestTag: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clickable(interactionSource = interactionSource, indication = null) { }
            .focusable(interactionSource = interactionSource)
            .semantics(mergeDescendants = true) {
                contentDescription = member.name
                role = Role.Button
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .testTag(avatarTestTag),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = com.saab.tv.ui.components.initialsForPerson(member.name),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White.copy(alpha = 0.78f)
            )
            member.photo?.let { photo ->
                AsyncImage(
                    model = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = member.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            member.character?.takeIf(String::isNotBlank)?.let { character ->
                Text(
                    text = character,
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun buildSubtitleTrackChips(
    track: PlayerTrackOption,
    embeddedLabel: String,
    streamLabel: String,
    addonLabel: String,
    unsupportedLabel: String
): List<String> {
    if (isSubtitleOffTrack(track)) return emptyList()

    val chips = mutableListOf<String>()
    chips += when {
        !track.isExternal -> embeddedLabel
        track.subtitleSourcePriority == SubtitleSourcePriority.STREAM_PROVIDED -> streamLabel
        else -> addonLabel
    }

    if (!track.supported) {
        chips += unsupportedLabel
    }

    return chips.distinct()
}

private fun buildSubtitleVariantDisplayLabel(
    track: PlayerTrackOption,
    subtitleFallbackLabel: String,
    offLabel: String
): String {
    val baseLabel = track.label.trim().ifBlank { subtitleFallbackLabel }
    if (isSubtitleOffTrack(track)) return offLabel

    if (!isGenericSubtitleDescriptorLabel(baseLabel)) return baseLabel

    val languageName = subtitleLanguageDisplayName(
        groupKey = subtitleLanguageKey(track.language),
        rawLanguage = track.language
    ).takeIf { displayName ->
        displayName.isNotBlank() &&
            !displayName.equals("Unknown", ignoreCase = true) &&
            !displayName.equals("Off", ignoreCase = true)
    } ?: return baseLabel

    if (labelAlreadyContainsLanguage(baseLabel, languageName, track.language)) return baseLabel

    return "$languageName [$baseLabel]"
}

private fun isGenericSubtitleDescriptorLabel(rawLabel: String): Boolean {
    val normalized = rawLabel
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    return normalized in setOf(
        "cc",
        "cc1",
        "cc2",
        "cc3",
        "cc4",
        "sdh",
        "forced",
        "caption",
        "captions",
        "closed caption",
        "closed captions",
        "hearing impaired"
    )
}

private fun labelAlreadyContainsLanguage(
    label: String,
    displayLanguage: String,
    rawLanguage: String?
): Boolean {
    val normalizedLabel = label.lowercase(Locale.ROOT)
    if (normalizedLabel.contains(displayLanguage.lowercase(Locale.ROOT))) return true

    val primaryLanguageCode = rawLanguage
        ?.trim()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
        ?: return false

    return Regex("""\b$primaryLanguageCode\b""").containsMatchIn(normalizedLabel)
}

private fun buildSubtitleLanguageGroups(
    subtitleTracks: List<PlayerTrackOption>
): List<SubtitleLanguageGroup> {
    if (subtitleTracks.isEmpty()) return emptyList()

    val groups = mutableListOf<SubtitleLanguageGroup>()
    val offTrack = subtitleTracks.firstOrNull { isSubtitleOffTrack(it) }

    if (offTrack != null) {
        groups += SubtitleLanguageGroup(
            key = "__off__",
            displayName = offTrack.label.ifBlank { "Off" },
            tracks = listOf(offTrack),
            isOffGroup = true
        )
    }

    val groupedByLanguage = linkedMapOf<String, MutableList<PlayerTrackOption>>()
    subtitleTracks
        .filterNot { track -> offTrack != null && track.id == offTrack.id }
        .forEach { track ->
            val languageKey = subtitleLanguageKey(track.language)
            groupedByLanguage.getOrPut(languageKey) { mutableListOf() }.add(track)
        }

    val sortedLanguageGroups = groupedByLanguage.map { (languageKey, tracks) ->
        SubtitleLanguageGroup(
            key = languageKey,
            displayName = subtitleLanguageDisplayName(
                groupKey = languageKey,
                rawLanguage = tracks.firstOrNull()?.language
            ),
            tracks = tracks.sortedWith(
                compareBy<PlayerTrackOption> { it.subtitleSourcePriority }
                    .thenBy { track -> track.label.lowercase(Locale.ROOT) }
            )
        )
    }.let { groupsToSort ->
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        groupsToSort.sortedWith { a, b ->
            val aUnknown = a.key == "und"
            val bUnknown = b.key == "und"
            if (aUnknown != bUnknown) {
                return@sortedWith if (aUnknown) 1 else -1
            }
            val byName = collator.compare(a.displayName, b.displayName)
            if (byName != 0) byName else a.key.compareTo(b.key)
        }
    }

    groups += sortedLanguageGroups

    return groups
}

private fun resolveSelectedSubtitleLanguageKey(
    groups: List<SubtitleLanguageGroup>,
    selectedSubtitleId: String?
): String? {
    if (groups.isEmpty()) return null

    val selectedTrackIdFromOptions = groups
        .asSequence()
        .flatMap { group -> group.tracks.asSequence() }
        .firstOrNull { track -> track.selected }
        ?.id

    val resolvedSelectedId = selectedSubtitleId
        ?: selectedTrackIdFromOptions
        ?: SUBTITLE_OFF_TRACK_ID
    return groups.firstOrNull { group ->
        group.tracks.any { track -> track.id == resolvedSelectedId }
    }?.key ?: groups.firstOrNull()?.key
}

private fun subtitleLanguageKey(language: String?): String = normalizeLanguageToIso2(language)

private fun localizedTrackLanguageLabel(
    value: String?,
    unknownLabel: String,
    offLabel: String
): String = when {
    value.isNullOrBlank() || value.equals("unknown", ignoreCase = true) -> unknownLabel
    value.equals("off", ignoreCase = true) -> offLabel
    else -> value
}

private fun subtitleLanguageDisplayName(groupKey: String, rawLanguage: String?): String {
    if (groupKey == "__off__") return "Off"
    if (groupKey == "und") return "Unknown"
    if (groupKey.contains('-')) {
        val canonicalDisplay = Locale.forLanguageTag(groupKey).displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(groupKey, ignoreCase = true) }
        if (canonicalDisplay != null) return canonicalDisplay
    }

    if (groupKey.length in 2..3) {
        val canonicalDisplay = Locale.forLanguageTag(groupKey).displayLanguage
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(groupKey, ignoreCase = true) }
        if (canonicalDisplay != null) return canonicalDisplay
    }

    val normalizedTag = rawLanguage
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() }

    if (groupKey == "und" && normalizedTag == null) {
        return "Unknown"
    }

    val tag = normalizedTag ?: groupKey
    val localeLanguage = Locale.forLanguageTag(tag).displayLanguage
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    return localeLanguage ?: tag
}

private fun isSubtitleOffTrack(track: PlayerTrackOption): Boolean {
    return track.id == SUBTITLE_OFF_TRACK_ID ||
        (track.language.isNullOrBlank() && track.label.equals("off", ignoreCase = true))
}

internal fun buildAudioLanguageGroups(
    audioTracks: List<PlayerTrackOption>,
    preferredAudioLanguages: List<String> = emptyList()
): List<AudioLanguageGroup> {
    if (audioTracks.isEmpty()) return emptyList()

    val groupedByLanguage = linkedMapOf<String, MutableList<PlayerTrackOption>>()
    audioTracks.forEach { track ->
        val languageKey = subtitleLanguageKey(track.language)
        groupedByLanguage.getOrPut(languageKey) { mutableListOf() }.add(track)
    }

    return groupedByLanguage.map { (languageKey, tracks) ->
        AudioLanguageGroup(
            key = languageKey,
            displayName = subtitleLanguageDisplayName(
                groupKey = languageKey,
                rawLanguage = tracks.firstOrNull()?.language
            ),
            tracks = tracks.toList()
        )
    }.let { groupsToSort ->
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        groupsToSort.sortedWith { a, b ->
            val aPreferenceRank = AudioTrackPreferencePolicy.languageRank(a.key, preferredAudioLanguages)
            val bPreferenceRank = AudioTrackPreferencePolicy.languageRank(b.key, preferredAudioLanguages)
            if (aPreferenceRank != bPreferenceRank) {
                return@sortedWith aPreferenceRank.compareTo(bPreferenceRank)
            }
            val aUnknown = a.key == "und"
            val bUnknown = b.key == "und"
            if (aUnknown != bUnknown) {
                return@sortedWith if (aUnknown) 1 else -1
            }
            val byName = collator.compare(a.displayName, b.displayName)
            if (byName != 0) byName else a.key.compareTo(b.key)
        }
    }
}

internal fun buildSourceAudioOptions(sources: List<PlayerSourceOption>): List<PlayerTrackOption> =
    sources.flatMap { source ->
        source.audioLanguages
            .map(::subtitleLanguageKey)
            .filter { it != "und" }
            .distinct()
            .map { language ->
                PlayerTrackOption(
                    id = "source-language:${source.id}:$language",
                    label = source.label,
                    language = language,
                    selected = false,
                    audioFormat = "Source",
                    sourceId = source.id
                )
            }
    }

private fun resolveSelectedAudioLanguageKey(
    groups: List<AudioLanguageGroup>,
    selectedAudioId: String?
): String? {
    if (groups.isEmpty()) return null

    val selectedTrackIdFromOptions = groups
        .asSequence()
        .flatMap { group -> group.tracks.asSequence() }
        .firstOrNull { track -> track.selected }
        ?.id

    val resolvedSelectedId = selectedAudioId
        ?: selectedTrackIdFromOptions
        ?: return groups.firstOrNull()?.key

    return groups.firstOrNull { group ->
        group.tracks.any { track -> track.id == resolvedSelectedId }
    }?.key ?: groups.firstOrNull()?.key
}



private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "0:00"

    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private fun resolveHeaderInfo(
    title: String,
    mediaType: String,
    seriesTitle: String?
): PlayerHeaderInfo {
    val cleanTitle = title.trim().ifBlank { "Untitled" }
    val cleanSeriesTitle = seriesTitle?.trim()?.takeIf { it.isNotBlank() }
    val isSeries = mediaType.equals("series", ignoreCase = true) ||
        mediaType.equals("tv", ignoreCase = true)

    if (!isSeries) {
        return PlayerHeaderInfo(primaryText = cleanTitle, secondaryText = null)
    }

    val normalizedEpisodeLine = normalizeEpisodeLine(cleanTitle)
    val primary = cleanSeriesTitle ?: cleanTitle
    val secondary = when {
        normalizedEpisodeLine != null && cleanSeriesTitle != null -> normalizedEpisodeLine
        cleanSeriesTitle != null && !cleanTitle.equals(cleanSeriesTitle, ignoreCase = true) -> cleanTitle
        else -> null
    }?.takeUnless { candidate ->
        candidate.equals(primary, ignoreCase = true)
    }

    return PlayerHeaderInfo(
        primaryText = primary,
        secondaryText = secondary
    )
}

private fun normalizeEpisodeLine(rawTitle: String): String? {
    val match = seriesEpisodePattern.find(rawTitle.trim()) ?: return null
    val season = match.groupValues[1].toIntOrNull() ?: return null
    val episode = match.groupValues[2].toIntOrNull() ?: return null
    val episodeTitle = match.groupValues[3].trim()
    return "S$season E$episode - $episodeTitle"
}

@Composable
private fun BoxScope.SubtitleOffsetTopBar(
    visible: Boolean,
    offsetPercent: Int,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onClose: () -> Unit
) {
    if (!visible) return

    val playPauseFocus = remember { FocusRequester() }
    val decrementFocus = remember { FocusRequester() }
    val incrementFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { decrementFocus.requestFocus() }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(SaabMotion.duration(180))),
        exit = fadeOut(animationSpec = tween(SaabMotion.duration(140))),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onPlayPause,
                colors = playerIconButtonColors(Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .size(44.dp)
                    .focusRequester(playPauseFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = decrementFocus
                        up = playPauseFocus
                        down = playPauseFocus
                    }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.player_pause else R.string.player_play
                    ),
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(
                onClick = onDecrement,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(decrementFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = incrementFocus
                        up = decrementFocus
                        down = decrementFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(
                        R.string.player_decrease_control,
                        stringResource(R.string.player_offset_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = "${offsetPercent}%",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(56.dp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = onIncrement,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(incrementFocus)
                    .focusProperties {
                        left = decrementFocus
                        right = closeFocus
                        up = incrementFocus
                        down = incrementFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(
                        R.string.player_increase_control,
                        stringResource(R.string.player_offset_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(
                onClick = onClose,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(closeFocus)
                    .focusProperties {
                        left = incrementFocus
                        right = closeFocus
                        up = closeFocus
                        down = closeFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.player_close_control,
                        stringResource(R.string.player_offset_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SubtitleSizeTopBar(
    visible: Boolean,
    sizePercent: Int,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onClose: () -> Unit
) {
    if (!visible) return

    val playPauseFocus = remember { FocusRequester() }
    val decrementFocus = remember { FocusRequester() }
    val incrementFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { decrementFocus.requestFocus() }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(SaabMotion.duration(180))),
        exit = fadeOut(animationSpec = tween(SaabMotion.duration(140))),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onPlayPause,
                colors = playerIconButtonColors(Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .size(44.dp)
                    .focusRequester(playPauseFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = decrementFocus
                        up = playPauseFocus
                        down = playPauseFocus
                    }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.player_pause else R.string.player_play
                    ),
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(
                onClick = onDecrement,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(decrementFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = incrementFocus
                        up = decrementFocus
                        down = decrementFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(
                        R.string.player_decrease_control,
                        stringResource(R.string.player_size_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = "${sizePercent}%",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(56.dp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = onIncrement,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(incrementFocus)
                    .focusProperties {
                        left = decrementFocus
                        right = closeFocus
                        up = incrementFocus
                        down = incrementFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(
                        R.string.player_increase_control,
                        stringResource(R.string.player_size_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(
                onClick = onClose,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(closeFocus)
                    .focusProperties {
                        left = incrementFocus
                        right = closeFocus
                        up = closeFocus
                        down = closeFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.player_close_control,
                        stringResource(R.string.player_size_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SubtitleDelayTopBar(
    visible: Boolean,
    delayMs: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onClose: () -> Unit
) {
    if (!visible) return

    val playPauseFocus = remember { FocusRequester() }
    val decrementFocus = remember { FocusRequester() }
    val incrementFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { decrementFocus.requestFocus() }
    }

    val delayText = remember(delayMs) {
        val seconds = delayMs / 1000.0
        when {
            delayMs > 0L -> "+%.1fs".format(seconds)
            delayMs < 0L -> "%.1fs".format(seconds)
            else -> "0.0s"
        }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(SaabMotion.duration(180))),
        exit = fadeOut(animationSpec = tween(SaabMotion.duration(140))),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onPlayPause,
                colors = playerIconButtonColors(Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .size(44.dp)
                    .focusRequester(playPauseFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = decrementFocus
                        up = playPauseFocus
                        down = playPauseFocus
                    }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.player_pause else R.string.player_play
                    ),
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(
                onClick = onDecrement,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(decrementFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = incrementFocus
                        up = decrementFocus
                        down = decrementFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(
                        R.string.player_decrease_control,
                        stringResource(R.string.player_delay_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = delayText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(64.dp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = onIncrement,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(incrementFocus)
                    .focusProperties {
                        left = decrementFocus
                        right = closeFocus
                        up = incrementFocus
                        down = incrementFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(
                        R.string.player_increase_control,
                        stringResource(R.string.player_delay_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(
                onClick = onClose,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(closeFocus)
                    .focusProperties {
                        left = incrementFocus
                        right = closeFocus
                        up = closeFocus
                        down = closeFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.player_close_control,
                        stringResource(R.string.player_delay_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SubtitleColorTopBar(
    visible: Boolean,
    currentTextColor: Int,
    currentBackgroundColor: Int,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSetTextColor: (Int) -> Unit,
    onSetBackgroundColor: (Int) -> Unit,
    onClose: () -> Unit
) {
    if (!visible) return

    val playPauseFocus = remember { FocusRequester() }
    val firstTextChipFocus = remember { FocusRequester() }
    val lastTextChipFocus = remember { FocusRequester() }
    val firstBgChipFocus = remember { FocusRequester() }
    val lastBgChipFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstTextChipFocus.requestFocus() }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(SaabMotion.duration(180))),
        exit = fadeOut(animationSpec = tween(SaabMotion.duration(140))),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                colors = playerIconButtonColors(Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .size(44.dp)
                    .focusRequester(playPauseFocus)
                    .focusProperties {
                        left = playPauseFocus
                        right = firstTextChipFocus
                        up = playPauseFocus
                        down = playPauseFocus
                    }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.player_pause else R.string.player_play
                    ),
                    modifier = Modifier.size(26.dp)
                )
            }

            // Color chip columns
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val textColorCount = PLAYER_TEXT_COLORS.size
                val bgColorCount = PLAYER_BACKGROUND_COLORS.size

                // Text color row — block up to prevent escape
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.onPreviewKeyEvent {
                        it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown
                    }
                ) {
                    Text(
                        text = stringResource(R.string.player_subtitle_text_short),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(32.dp)
                    )
                    PLAYER_TEXT_COLORS.forEachIndexed { index, preset ->
                        val colorDescription = stringResource(
                            R.string.player_subtitle_text_color_option,
                            stringResource(preset.labelResource)
                        )
                        val chipModifier = when (index) {
                            0 -> Modifier
                                .focusRequester(firstTextChipFocus)
                                .focusProperties { left = playPauseFocus }
                            textColorCount - 1 -> Modifier
                                .focusRequester(lastTextChipFocus)
                                .focusProperties { right = closeFocus }
                            else -> Modifier
                        }
                        PlayerSubtitleColorChip(
                            color = Color(preset.colorValue),
                            isSelected = currentTextColor == preset.colorValue,
                            contentDescription = colorDescription,
                            onClick = { onSetTextColor(preset.colorValue) },
                            modifier = chipModifier
                        )
                    }
                }

                // Background color row — block down to prevent escape
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.onPreviewKeyEvent {
                        it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown
                    }
                ) {
                    Text(
                        text = stringResource(R.string.player_subtitle_background_short),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(32.dp)
                    )
                    PLAYER_BACKGROUND_COLORS.forEachIndexed { index, preset ->
                        val colorDescription = stringResource(
                            R.string.player_subtitle_background_color_option,
                            stringResource(preset.labelResource)
                        )
                        val chipModifier = when (index) {
                            0 -> Modifier
                                .focusRequester(firstBgChipFocus)
                                .focusProperties { left = playPauseFocus }
                            bgColorCount - 1 -> Modifier
                                .focusRequester(lastBgChipFocus)
                                .focusProperties { right = closeFocus }
                            else -> Modifier
                        }
                        PlayerSubtitleColorChip(
                            color = Color(preset.colorValue),
                            isSelected = currentBackgroundColor == preset.colorValue,
                            contentDescription = colorDescription,
                            isTransparent = preset.colorValue == 0x00000000,
                            onClick = { onSetBackgroundColor(preset.colorValue) },
                            modifier = chipModifier
                        )
                    }
                }
            }

            // Close button
            IconButton(
                onClick = onClose,
                colors = playerIconButtonColors(Color.Transparent),
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(closeFocus)
                    .focusProperties {
                        left = lastTextChipFocus
                        right = closeFocus
                        up = closeFocus
                        down = closeFocus
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.player_close_control,
                        stringResource(R.string.player_color_label)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
