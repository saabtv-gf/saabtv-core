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
    val key = focusedItem?.let { "${it.type}:${it.id}" }
    var expanded by remember(key, settings.presentation) { mutableStateOf(false) }
    val anchor = InlineTrailerAnchor.bounds.takeIf { InlineTrailerAnchor.key == key }
    val inline = InlinePreviewLayout.isInline(settings.presentation, expanded)
    val visible = activeItem != null && source != null && foreground && settings.enabled && enabled
    val activeCallback by rememberUpdatedState(onActiveChanged)
    val fullscreenCallback by rememberUpdatedState(onFullscreenChanged)
    val dismissCallback by rememberUpdatedState(onDismiss)
    val unavailableCallback by rememberUpdatedState(onUnavailable)
    val episodeAction by rememberUpdatedState(onEpisodes ?: LocalTrailerEpisodesAction.current)
    val startWatching by rememberUpdatedState(LocalTrailerStartWatching.current ?: onOpen)
    LaunchedEffect(visible) { activeCallback(visible) }
    LaunchedEffect(visible, inline) { fullscreenCallback(visible && !inline) }
    DisposableEffect(owner) { onDispose {
        InlineTrailerAnchor.clearSession(owner)
        activeCallback(false); fullscreenCallback(false)
    } }
    LaunchedEffect(key, enabled, settings.enabled, settings.delaySeconds, foreground, activityVersion) {
        source = null; activeItem = null
        if (!TrailerPreviewPolicy.canStart(settings.enabled && enabled, foreground, key, key,
                dismissedKey, dismissedKey) || focusedItem == null) return@LaunchedEffect
        delay(settings.delaySeconds * 1_000L)
        val trailer = try { resolveTrailer(focusedItem) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val resolved = trailer?.let { extractor.extractPlaybackSource(it.first) }
        if (resolved != null) { source = resolved; activeItem = focusedItem }
        else unavailableCallback()
    }
    if (!visible) {
        SideEffect { InlineTrailerAnchor.clearSession(owner) }
        return
    }
    val item = activeItem ?: return
    LaunchedEffect(profileId, item.type, item.id) {
        try {
            homeModel.prefetchTrailerSources(item)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Playback falls back to a fresh lookup. */ }
    }
    var muted by remember(key) { mutableStateOf(settings.muted) }
    var started by remember(key) { mutableStateOf(false) }
    var controlsVisible by remember(key) { mutableStateOf(true) }
    var interactionVersion by remember(key) { mutableIntStateOf(0) }
    var watchlisted by remember(key, profileId) { mutableStateOf(false) }
    LaunchedEffect(key, profileId) { watchlisted = homeModel.isWatchlisted(profileId, item.id) }
    fun dismiss(restoreFocus: Boolean = true) {
        dismissedKey = key; activeItem = null; source = null
        InlineTrailerAnchor.clearSession(owner)
        if (restoreFocus) navigationScope.launch {
            withFrameNanos { }
            dismissCallback()
        }
    }
    BackHandler { dismiss() }
    LaunchedEffect(started, interactionVersion, inline) {
        if (inline) controlsVisible = true
        else if (started) { delay(5_000); controlsVisible = false }
    }
    val player = rememberNativeTrailerPlayer(source!!, muted, inline,
        onStarted = { started = true }, onEnded = { dismiss() })
    val session = remember(player, item) {
        InlineTrailerSession(owner, item, player,
            onInteraction = { controlsVisible = true; interactionVersion++ },
            onMute = { muted = !muted; controlsVisible = true; interactionVersion++ },
            onWatch = { dismiss(false); startWatching(item) },
            onEpisodes = episodeAction?.let { action -> { dismiss(false); action() } },
            onWatchlist = {
                homeModel.toggleWatchlist(profileId, item)
                watchlisted = !watchlisted; controlsVisible = true; interactionVersion++
            },
            onFullscreen = { expanded = true; controlsVisible = true; interactionVersion++ },
            onDismiss = { dismiss() },
            onNavigate = { direction ->
                dismiss(false)
                // Restore the poster before moving focus from the overlay.
                navigationScope.launch {
                    withFrameNanos { }; dismissCallback()
                    withFrameNanos { }; focusManager.moveFocus(direction)
                }
            })
    }
    SideEffect {
        session.muted = muted; session.watchlisted = watchlisted; session.controlsVisible = inline || controlsVisible
        if (inline) InlineTrailerAnchor.session = session else InlineTrailerAnchor.clearSession(owner)
    }
    if (inline) {
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
            val stage = with(density) {
                Modifier.offset(interpolate(startBounds.left, targetBounds.left).toDp(),
                    interpolate(startBounds.top, targetBounds.top).toDp())
                    .size(interpolate(startBounds.width, targetBounds.width).toDp(),
                        interpolate(startBounds.height, targetBounds.height).toDp())
            }
            Box(stage.shadow(16.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))) {
                InlineTrailerCard(session)
            }
        }
        return
    }
    val playRequester = remember { FocusRequester() }
    val rootRequester = remember { FocusRequester() }
    var revealingKey by remember(key) { mutableStateOf<Key?>(null) }
    LaunchedEffect(controlsVisible) {
        withFrameNanos { }
        runCatching { if (controlsVisible) playRequester.requestFocus() else rootRequester.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).zIndex(20f)
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
        TrailerVideoSurface(player)
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
            source.qualityLabel, height = primaryHeight, requestHeaders = source.requestHeaders)) + source.fallbackVariants
        if (inline) {
            val compact = all.filter { it.height in 1..720 }.sortedByDescending { it.height }
            compact + all.filter { it !in compact }
        } else all
    }
    val player = remember(source) {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 750, 1_500)
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
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .apply { if (inline) setMaxVideoSize(1280, 720) }
            .setForceHighestSupportedBitrate(!inline).build()
    }
    DisposableEffect(player) {
        val resumeMs = player.currentPosition.coerceAtLeast(0)
        var index = 0
        fun play() {
            val variant = variants.getOrNull(index) ?: return latestEnded()
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) latestStarted() }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) latestEnded() }
            override fun onPlayerError(error: PlaybackException) {
                com.saab.tv.AppDiagnostics.event("Trailer Preview", "Variant Failed",
                    "variant=${index + 1}/${variants.size} quality=${variants.getOrNull(index)?.qualityLabel} positionMs=${player.currentPosition}")
                com.saab.tv.AppDiagnostics.failure("Trailer Preview", "Playback Failed", error)
                index++; play()
            }
        }
        player.addListener(listener)
        play()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    return player
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun TrailerVideoSurface(player: ExoPlayer) {
    AndroidView(
        factory = {
            (android.view.LayoutInflater.from(it).inflate(com.saab.tv.R.layout.trailer_preview_player, null) as PlayerView)
                .apply { useController = false; isFocusable = false; this.player = player }
        },
        update = { it.player = player }, modifier = Modifier.fillMaxSize(),
        onRelease = { it.player = null }
    )
}
