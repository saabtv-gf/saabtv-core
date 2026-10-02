package com.saab.tv.ui.trailer

import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.key.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.profile.TrailerPreviewSettings
import com.saab.tv.data.trailer.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/** One persistent player owner; inline presentation is a bounded landscape overlay. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BackdropTrailerPreview(
    focusedItem: MetaItem?, catalog: List<MetaItem>, profileId: Int,
    settings: TrailerPreviewSettings, enabled: Boolean,
    resolveTrailer: suspend (MetaItem) -> Pair<String, String>?,
    onActiveChanged: (Boolean) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onOpen: (MetaItem) -> Unit, onDismiss: () -> Unit,
    activityVersion: Int = 0,
    onUnavailable: () -> Unit = {},
    onEpisodes: (() -> Unit)? = null
) {
    val homeModel: com.saab.tv.ui.home.HomeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val foreground = lifecycleState == Lifecycle.State.RESUMED
    val extractor = remember { YouTubeExtractor() }
    val owner = remember { Any() }
    val navigationScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var dismissedKey by remember { mutableStateOf<String?>(null) }
    var activeItem by remember { mutableStateOf<MetaItem?>(null) }
    var source by remember { mutableStateOf<TrailerPlaybackSource?>(null) }
    var expanded by remember(profileId, settings.presentation) { mutableStateOf(false) }
    // A disappearing inline control can briefly restore focus to a background
    // poster. Once expanded, keep the playing title until an explicit dismissal.
    val previewItem = if (expanded) activeItem ?: focusedItem else focusedItem
    val key = previewItem?.let { "${it.type}:${it.id}" }
    val previewEnabled = enabled || expanded
    val anchor = InlineTrailerAnchor.bounds.takeIf { InlineTrailerAnchor.key == key }
    val inline = InlinePreviewLayout.isInline(settings.presentation, expanded)
    val visible = activeItem != null && source != null && foreground && settings.enabled && previewEnabled
    val activeCallback by rememberUpdatedState(onActiveChanged)
    val fullscreenCallback by rememberUpdatedState(onFullscreenChanged)
    val dismissCallback by rememberUpdatedState(onDismiss)
    val unavailableCallback by rememberUpdatedState(onUnavailable)
    val latestItem by rememberUpdatedState(activeItem)
    val latestSource by rememberUpdatedState(source)
    val latestExpanded by rememberUpdatedState(expanded)
    LaunchedEffect(visible, key, previewEnabled, foreground) {
        com.saab.tv.AppDiagnostics.detailed("Trailer Preview", "Visibility",
            "titleId=${previewItem?.id} videoId=${source?.videoId} visible=$visible enabled=$enabled previewEnabled=$previewEnabled foreground=$foreground expanded=$expanded")
    }
    val episodeAction by rememberUpdatedState(onEpisodes ?: LocalTrailerEpisodesAction.current)
    val startWatching by rememberUpdatedState(LocalTrailerStartWatching.current ?: onOpen)
    LaunchedEffect(visible) { activeCallback(visible) }
    LaunchedEffect(visible, inline) { fullscreenCallback(visible && !inline) }
    DisposableEffect(owner) { onDispose {
        com.saab.tv.AppDiagnostics.event("Trailer Preview", "Owner Disposed",
            "titleId=${latestItem?.id} videoId=${latestSource?.videoId} expanded=$latestExpanded")
        InlineTrailerAnchor.clearSession(owner)
        activeCallback(false); fullscreenCallback(false)
    } }
    LaunchedEffect(key, previewEnabled, settings.enabled, settings.delaySeconds, foreground, activityVersion) {
        source = null; activeItem = null
        if (!TrailerPreviewPolicy.canStart(settings.enabled && previewEnabled, foreground, key, key,
                dismissedKey, dismissedKey) || previewItem == null) return@LaunchedEffect
        val delayMs = settings.delaySeconds * 1_000L
        // Debounce rapid card navigation, then resolve during the configured
        // hover delay rather than adding extraction latency after that delay.
        val debounceMs = minOf(delayMs, 250L)
        delay(debounceMs)
        val pendingSource = async {
            val trailer = try { resolveTrailer(previewItem) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            trailer?.let { extractor.extractPlaybackSource(it.first) }
        }
        delay(delayMs - debounceMs)
        homeModel.startTrailerSourcePrefetch(previewItem)
        val resolved = pendingSource.await()
        if (resolved != null) { source = resolved; activeItem = previewItem }
        else unavailableCallback()
    }
    if (!visible) {
        SideEffect { InlineTrailerAnchor.clearSession(owner) }
        return
    }
    val item = activeItem ?: return
    var muted by remember(key) { mutableStateOf(settings.muted) }
    var started by remember(key) { mutableStateOf(false) }
    var controlsVisible by remember(key) { mutableStateOf(true) }
    val fullscreenRootRequester = remember(key) { FocusRequester() }
    var interactionVersion by remember(key) { mutableIntStateOf(0) }
    var watchlisted by remember(key, profileId) { mutableStateOf(false) }
    val previewPlayer = remember(key) { arrayOfNulls<ExoPlayer>(1) }
    LaunchedEffect(key, profileId) { watchlisted = homeModel.isWatchlisted(profileId, item.id) }
    fun dismiss(restoreFocus: Boolean = true, reason: String = "back") {
        com.saab.tv.AppDiagnostics.event("Trailer Preview", "Dismissed",
            "titleId=${item.id} videoId=${source?.videoId} reason=$reason expanded=$expanded positionMs=${previewPlayer[0]?.currentPosition}")
        dismissedKey = key; expanded = false; activeItem = null; source = null
        InlineTrailerAnchor.clearSession(owner)
        if (restoreFocus) navigationScope.launch {
            withFrameNanos { }
            dismissCallback()
        }
    }
    BackHandler { dismiss() }
    LaunchedEffect(started, interactionVersion, inline) {
        if (inline) controlsVisible = true
        else if (started) {
            delay(5_000)
            // Move focus while the focused button is still attached. Removing
            // it first lets Android re-enter through a stale focus requester.
            runCatching { fullscreenRootRequester.requestFocus() }
            withFrameNanos { }
            controlsVisible = false
        }
    }
    val player = rememberNativeTrailerPlayer(source!!, muted, inline,
        onStarted = { started = true }, onEnded = { dismiss(reason = "ended-or-variants-exhausted") })
    SideEffect { previewPlayer[0] = player }
    val session = remember(player, item) {
        InlineTrailerSession(owner, item, player,
            onInteraction = { controlsVisible = true; interactionVersion++ },
            onMute = { muted = !muted; controlsVisible = true; interactionVersion++ },
            onWatch = { dismiss(false, "start-watching"); startWatching(item) },
            onEpisodes = episodeAction?.let { action -> { dismiss(false, "episodes"); action() } },
            onWatchlist = {
                homeModel.toggleWatchlist(profileId, item)
                watchlisted = !watchlisted; controlsVisible = true; interactionVersion++
            },
            onFullscreen = {
                com.saab.tv.AppDiagnostics.event("Trailer Preview", "Expand Requested", "titleId=${item.id} videoId=${source?.videoId} positionMs=${player.currentPosition} playing=${player.isPlaying}")
                expanded = true; controlsVisible = true; interactionVersion++
            },
            onDismiss = { dismiss() },
            onNavigate = { direction ->
                dismiss(false, "card-navigation")
                // Restore the poster before moving focus from the overlay.
                navigationScope.launch {
                    withFrameNanos { }; dismissCallback()
                    withFrameNanos { }; focusManager.moveFocus(direction)
                }
            })
    }
    SideEffect {
        session.muted = muted; session.watchlisted = watchlisted; session.controlsVisible = inline || controlsVisible
        if (inline) {
            InlineTrailerAnchor.fullscreen(owner, false)
            InlineTrailerAnchor.session = session
        } else {
            InlineTrailerAnchor.clearInlineSession(owner)
            InlineTrailerAnchor.fullscreen(owner, true)
        }
    }
    // Keep one PlayerView at one composition location. Replacing an inline
    // PlayerView with a fullscreen one lets the old onRelease detach the new
    // surface from the same ExoPlayer, leaving audio but no fullscreen picture.
    val density = LocalDensity.current
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val expansion = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { expansion.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(20f)
        .onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }) {
        val targetBounds = with(density) {
            InlinePreviewLayout.bounds(anchor?.let { InlinePreviewLayout.Bounds(
                it.left - rootOrigin.x, it.top - rootOrigin.y, it.width, it.height) },
                maxWidth.toPx(), maxHeight.toPx(), density.density)
        }
        val startBounds = anchor?.let { InlinePreviewLayout.Bounds(
            it.left - rootOrigin.x, it.top - rootOrigin.y, it.width, it.height) } ?: targetBounds
        fun interpolate(start: Float, end: Float) = start + (end - start) * expansion.value
        val stage = if (!inline) Modifier.fillMaxSize() else with(density) {
            Modifier.offset(interpolate(startBounds.left, targetBounds.left).toDp(),
                interpolate(startBounds.top, targetBounds.top).toDp())
                .size(interpolate(startBounds.width, targetBounds.width).toDp(),
                    interpolate(startBounds.height, targetBounds.height).toDp())
        }
        val decoration = if (!inline) Modifier else Modifier.shadow(16.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
        Box(stage.then(decoration).background(Color.Black)) {
            TrailerVideoSurface(player, Modifier.fillMaxSize().padding(bottom = if (inline) 72.dp else 0.dp))
            if (inline) {
                InlineTrailerCard(session, renderVideo = false)
            } else {
                val playRequester = remember { FocusRequester() }
                val rootRequester = fullscreenRootRequester
                var revealingKey by remember(key) { mutableStateOf<Key?>(null) }
                LaunchedEffect(controlsVisible) {
                    withFrameNanos { }
                    runCatching { if (controlsVisible) playRequester.requestFocus() else rootRequester.requestFocus() }
                }
                Box(Modifier.fillMaxSize().zIndex(20f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Back || event.key == Key.Escape) {
                            if (event.type == KeyEventType.KeyUp) session.onDismiss()
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == revealingKey) {
                            if (event.type == KeyEventType.KeyUp) revealingKey = null
                            return@onPreviewKeyEvent true
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            interactionVersion++
                            if (!controlsVisible) {
                                controlsVisible = true; revealingKey = event.key
                                return@onPreviewKeyEvent true
                            }
                        }
                        event.key == Key.DirectionUp || event.key == Key.DirectionDown
                    }.focusRequester(rootRequester).focusable()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter),
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut()
                    ) {
                        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
                            verticalAlignment = Alignment.CenterVertically) {
                            com.saab.tv.ui.components.DetailActionButton("Start Watching",
                                androidx.compose.material.icons.Icons.Default.PlayArrow, session.onWatch,
                                Modifier.focusRequester(playRequester).focusProperties { left = FocusRequester.Cancel })
                            session.onEpisodes?.let { onEpisodes ->
                                com.saab.tv.ui.components.DetailActionButton("Episodes",
                                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.List, onEpisodes)
                            }
                            com.saab.tv.ui.components.DetailActionButton(
                                if (watchlisted) "Remove From Watchlist" else "Add To Watchlist",
                                if (watchlisted) androidx.compose.material.icons.Icons.Default.Bookmark else androidx.compose.material.icons.Icons.Default.BookmarkBorder,
                                session.onWatchlist, isActive = watchlisted)
                            com.saab.tv.ui.components.DetailActionButton(
                                if (muted) "Unmute" else "Mute",
                                if (muted) androidx.compose.material.icons.Icons.Default.VolumeOff else androidx.compose.material.icons.Icons.Default.VolumeUp,
                                session.onMute, Modifier.focusProperties { right = FocusRequester.Cancel })
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberNativeTrailerPlayer(source: TrailerPlaybackSource, muted: Boolean, inline: Boolean,
    onStarted: () -> Unit, onEnded: () -> Unit): ExoPlayer {
    val context = LocalContext.current
    val latestEnded by rememberUpdatedState(onEnded)
    val latestStarted by rememberUpdatedState(onStarted)
    // Choose the initial rendition once. Expanding must not restart a previously
    // successful fallback (or replace its signed URL with an already failed one).
    val variants = remember(source) {
        val primaryHeight = Regex("(\\d{3,4})p").find(source.qualityLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val all = listOf(TrailerPlaybackVariant(source.videoUrl, source.audioUrl,
            source.qualityLabel, height = primaryHeight, requestHeaders = source.requestHeaders,
            formatId = source.formatId, codec = source.codec, bitrate = source.bitrate, fps = source.fps,
            width = source.width, hardwareDecoder = source.hardwareDecoder)) + source.fallbackVariants
        all
    }
    val player = remember(source) {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            .setMediaCodecSelector(TrailerHardwareCodecSupport.selector))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 250, 1_500)
                .setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .build().apply {
                volume = if (muted) 0f else 1f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints().setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true)
                    .setForceHighestSupportedBitrate(true).build()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, inline) {
        com.saab.tv.AppDiagnostics.event("Trailer Preview", "Presentation Changed",
            "videoId=${source.videoId} mode=${if (inline) "inline" else "fullscreen"} positionMs=${player.currentPosition} playing=${player.isPlaying}")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(true).build()
    }
    DisposableEffect(player) {
        val preparedAtMs = android.os.SystemClock.elapsedRealtime()
        var variantPreparedAtMs = preparedAtMs
        var resumeMs = player.currentPosition.coerceAtLeast(0)
        var index = 0
        fun play() {
            val variant = variants.getOrNull(index) ?: return latestEnded()
            variantPreparedAtMs = android.os.SystemClock.elapsedRealtime()
            com.saab.tv.AppDiagnostics.event("Trailer Preview", "Rendition Preparing",
                "videoId=${source.videoId} variant=${index + 1}/${variants.size} ${variant.diagnosticSummary()} resumeMs=$resumeMs")
            val factory = DefaultMediaSourceFactory(context).setDataSourceFactory(
                YoutubeChunkedDataSourceFactory(requestHeaders = variant.requestHeaders))
                .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        val http = generateSequence<Throwable>(info.exception) { it.cause }
                            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                        return if (http != null && com.saab.tv.data.trailer.TrailerPolicy.terminalHttpStatus(http.responseCode))
                            androidx.media3.common.C.TIME_UNSET else super.getRetryDelayMsFor(info)
                    }
                })
            val item = MediaItem.Builder().setUri(variant.videoUrl).apply {
                if (variant.videoUrl.contains("/manifest/hls/") || variant.videoUrl.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                if (variant.videoUrl.contains("/manifest/dash/") || variant.videoUrl.contains(".mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
            }.build()
            val video = factory.createMediaSource(item)
            player.setMediaSource(variant.audioUrl?.let { MergingMediaSource(video,
                factory.createMediaSource(MediaItem.fromUri(it))) } ?: video, resumeMs)
            player.prepare(); player.playWhenReady = true
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                val format = player.videoFormat
                com.saab.tv.AppDiagnostics.event("Trailer Preview", "Decoded Resolution",
                    "videoId=${source.videoId} variant=${index + 1}/${variants.size} width=${size.width} height=${size.height} mime=${format?.sampleMimeType} codecs=${format?.codecs} averageBitrateBps=${format?.averageBitrate} peakBitrateBps=${format?.peakBitrate} fps=${format?.frameRate} requested=${variants.getOrNull(index)?.qualityLabel}")
            }
            override fun onRenderedFirstFrame() {
                com.saab.tv.AppDiagnostics.event("Trailer Preview", "First Frame",
                    "videoId=${source.videoId} variant=${index + 1}/${variants.size} quality=${variants.getOrNull(index)?.qualityLabel} prepareToFrameMs=${android.os.SystemClock.elapsedRealtime() - preparedAtMs} variantToFrameMs=${android.os.SystemClock.elapsedRealtime() - variantPreparedAtMs}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) latestStarted() }
            override fun onPlaybackStateChanged(state: Int) {
                com.saab.tv.AppDiagnostics.detailed("Trailer Preview", "Playback State",
                    "videoId=${source.videoId} state=$state positionMs=${player.currentPosition} bufferedMs=${player.bufferedPosition} playWhenReady=${player.playWhenReady}")
                if (state == Player.STATE_ENDED) latestEnded()
            }
            override fun onPlayerError(error: PlaybackException) {
                com.saab.tv.AppDiagnostics.event("Trailer Preview", "Variant Failed",
                    "videoId=${source.videoId} variant=${index + 1}/${variants.size} quality=${variants.getOrNull(index)?.qualityLabel} playbackCode=${error.errorCode} positionMs=${player.currentPosition} remaining=${variants.size - index - 1}")
                com.saab.tv.AppDiagnostics.failure("Trailer Preview", "Playback Failed", error)
                resumeMs = player.currentPosition.coerceAtLeast(resumeMs)
                val failedVp9 = TrailerPolicy.isVp9(variants.getOrNull(index)?.codec.orEmpty())
                val decoderFailure = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
                index++
                if (failedVp9 && decoderFailure) {
                    while (TrailerPolicy.isVp9(variants.getOrNull(index)?.codec.orEmpty())) index++
                }
                play()
            }
        }
        val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                com.saab.tv.AppDiagnostics.detailed("Trailer Preview", "Decoder Initialized",
                    "videoId=${source.videoId} decoder=$decoderName initializationMs=$initializationDurationMs")
            }
            override fun onVideoInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                com.saab.tv.AppDiagnostics.detailed("Trailer Preview", "Decoder Input Format",
                    "videoId=${source.videoId} width=${format.width} height=${format.height} codecs=${format.codecs} mime=${format.sampleMimeType} averageBitrateBps=${format.averageBitrate} peakBitrateBps=${format.peakBitrate} fps=${format.frameRate}")
            }
            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int, elapsedMs: Long) {
                com.saab.tv.AppDiagnostics.detailed("Trailer Preview", "Dropped Frames",
                    "videoId=${source.videoId} count=$droppedFrames elapsedMs=$elapsedMs positionMs=${player.currentPosition}")
            }
            override fun onVideoCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                videoCodecError: Exception) {
                com.saab.tv.AppDiagnostics.failure("Trailer Preview", "Decoder Failure", videoCodecError)
            }
        }
        player.addAnalyticsListener(analytics)
        player.addListener(listener)
        play()
        onDispose {
            com.saab.tv.AppDiagnostics.event("Trailer Preview", "Player Detached",
                "videoId=${source.videoId} positionMs=${player.currentPosition} state=${player.playbackState}")
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    return player
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun TrailerVideoSurface(player: ExoPlayer, modifier: Modifier = Modifier.fillMaxSize()) {
    var lastSize by remember(player) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    AndroidView(
        factory = {
            (android.view.LayoutInflater.from(it).inflate(com.saab.tv.R.layout.trailer_preview_player, null) as PlayerView)
                .apply { useController = false; isFocusable = false; this.player = player }
        },
        update = { it.player = player }, modifier = modifier.onGloballyPositioned { coordinates ->
            if (coordinates.size != lastSize) {
                lastSize = coordinates.size
                com.saab.tv.AppDiagnostics.detailed("Trailer Preview", "Surface Layout",
                    "widthPx=${lastSize.width} heightPx=${lastSize.height} decodedWidth=${player.videoSize.width} decodedHeight=${player.videoSize.height} surface=texture resize=fit")
            }
        },
        onRelease = { it.player = null }
    )
}
