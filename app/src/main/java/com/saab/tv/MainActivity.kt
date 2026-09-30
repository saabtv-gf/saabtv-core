package com.saab.tv

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.hilt.navigation.compose.hiltViewModel
import com.saab.tv.data.player.PlaybackTrackSelectionStore
import com.saab.tv.data.player.EpisodeStreamContinuity
import com.saab.tv.data.torrent.TorrentProgress
import com.saab.tv.data.torrent.TorrentService
import com.saab.tv.data.torrent.TorrentFallbackPolicy
import com.saab.tv.data.player.SourceSelectionStore
import com.saab.tv.ui.MainViewModel
import com.saab.tv.ui.components.SaabTvBackground
import com.saab.tv.ui.details.DetailsScreen
import com.saab.tv.ui.home.GridViewScreen
import com.saab.tv.ui.home.HomeScreen
import com.saab.tv.ui.watchlist.WatchlistScreen
import com.saab.tv.ui.home.HomeViewModel
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamSubtitle
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.repository.IntroRepository
import com.saab.tv.data.repository.SubtitleRepository
import com.saab.tv.data.stream.StreamSortingService
import com.saab.tv.data.stream.StreamScoreCalculator
import com.saab.tv.data.stream.StreamDisplayFormatter
import com.saab.tv.data.stream.StreamParser
import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.trailer.TrailerPlaybackVariant
import com.saab.tv.domain.AddonSubtitle
import com.saab.tv.domain.DashboardTab
import com.saab.tv.domain.episodeDisplayTitle
import com.saab.tv.domain.episodePlaybackId
import com.saab.tv.domain.episodeStreamId
import com.saab.tv.domain.findNextEpisode
import com.saab.tv.domain.normalizeEpisodeList
import com.saab.tv.domain.seriesIdFromPlaybackId
import com.saab.tv.ui.navigation.NavDestination
import com.saab.tv.ui.navigation.NavDrawer
import com.saab.tv.ui.navigation.TopNavigationBar
import com.saab.tv.ui.player.PlayerScreen
import com.saab.tv.ui.player.PlayerSessionResult
import com.saab.tv.ui.player.base.PlayerSourceOption
import com.saab.tv.ui.player.base.NextEpisodeInfo
import com.saab.tv.ui.player.base.PlaybackSettings
import com.saab.tv.ui.player.base.PlayerSubtitleSource
import com.saab.tv.ui.player.base.SkipSegmentInfo
import com.saab.tv.ui.profiles.ProfileScreen
import com.saab.tv.ui.profiles.ProfileViewModel
import com.saab.tv.ui.search.SearchScreen
import com.saab.tv.ui.settings.SettingsScreen
import com.saab.tv.ui.splash.SaabTvSplashView
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.addons.VoidDialog
import com.saab.tv.ui.theme.DefaultThemes
import com.saab.tv.ui.theme.LocalRoundCorners
import com.saab.tv.ui.theme.LocalHubRoundCorners
import com.saab.tv.ui.theme.SaabTvTheme
import com.saab.tv.ui.theme.ThemeManager
import com.saab.tv.ui.trailer.YouTubeTrailerActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.profile.autoSkipCountdownSeconds

import java.util.Locale
import javax.inject.Inject

private const val SOURCE_SELECTION_COMMIT_MIN_POSITION_MS = 5_000L
private const val SOURCE_SELECTION_FAILURE_RESET_MAX_POSITION_MS = 1_000L

private fun FocusRequester.requestFocusSafely(): Boolean =
    runCatching { requestFocus(); true }.getOrDefault(false)

private suspend fun FocusRequester.requestFocusWhenAttached(hasFocus: () -> Boolean = { true }): Boolean {
    repeat(14) {
        androidx.compose.runtime.withFrameNanos { }
        if (requestFocusSafely()) {
            androidx.compose.runtime.withFrameNanos { }
            if (hasFocus()) return true
        }
        delay(50)
    }
    return false
}

private fun isPlaybackSnapshotCompleted(
    positionMs: Long,
    durationMs: Long?,
    watchedThresholdPercent: Int = 95
): Boolean {
    val duration = durationMs?.takeIf { it > 0L } ?: return false
    val position = positionMs.coerceIn(0L, duration)
    if (position < com.saab.tv.ui.player.WatchProgressPolicy.MIN_WATCHED_POSITION_MS) return false
    return position.toDouble() / duration.toDouble() >= watchedThresholdPercent.coerceIn(50, 99) / 100.0 ||
        duration - position <= 30_000L
}

private data class PlayerSubtitlePayload(
    val id: String,
    val url: String,
    val name: String,
    val language: String?,
    val sourcePriority: Int
)

private data class PendingSourceSelection(
    val playbackId: String,
    val launchedStream: Stream,
    val candidateStreams: List<Stream>
)

private data class PendingEpisodeSwitch(
    val playbackId: String,
    val playbackTitle: String,
    val streams: List<Stream>?,
    val addonSubs: List<AddonSubtitle>,
    val playerCurrentSourceUrl: String?,
    val currentPositionMs: Long,
    val currentDurationMs: Long?
)

private data class PrefetchedEpisodeData(
    val streamId: String,
    val streams: List<Stream>,
    val addonSubs: List<AddonSubtitle>
)

@Stable
private class PlayerState {
    var selectedPlayerSubtitles by mutableStateOf<List<PlayerSubtitlePayload>>(emptyList())
    var selectedPlayerSources by mutableStateOf<List<PlayerSourceOption>>(emptyList())
    var pendingSourceSelection by mutableStateOf<PendingSourceSelection?>(null)
    var showPlayerChoiceDialog by mutableStateOf(false)
    var currentEpisodeList by mutableStateOf<List<MetaVideo>>(emptyList())
    var currentStream by mutableStateOf<Stream?>(null)
    var pendingEpisodeSwitch by mutableStateOf<PendingEpisodeSwitch?>(null)
    var prefetchedEpisodeData by mutableStateOf<PrefetchedEpisodeData?>(null)
    var isEpisodeSwitchLoading by mutableStateOf(false)
    var episodeSwitchRequestId by mutableLongStateOf(0L)

    fun beginEpisodeSwitch(): Long {
        episodeSwitchRequestId += 1L
        return episodeSwitchRequestId
    }

    fun cancelEpisodeSwitch() {
        episodeSwitchRequestId += 1L
        pendingEpisodeSwitch = null
        isEpisodeSwitchLoading = false
    }
}

private fun resolveSubtitleUrl(rawUrl: String, addonTransportUrl: String?): String? {
    val value = rawUrl.trim()
    if (value.isEmpty()) return null

    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (uri.isAbsolute) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return value
    }
    if (addonTransportUrl.isNullOrBlank()) return null

    val base = addonTransportUrl.trimEnd('/')
    val path = value.trimStart('/')
    if (path.isEmpty()) return null
    return "$base/$path"
}

private fun sanitizeSubtitleSourceName(rawName: String?, fallback: String): String {
    val cleaned = rawName
        ?.replace("[", "")
        ?.replace("]", "")
        ?.trim()
        .orEmpty()
    return cleaned.ifEmpty { fallback }
}

private fun subtitleNameFromUrl(rawUrl: String): String? {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
    val path = uri.path?.substringBefore('?').orEmpty()
    val rawName = path.substringAfterLast('/').ifEmpty { return null }
    val decoded = runCatching { Uri.decode(rawName) }.getOrDefault(rawName)
    val withoutExtension = decoded.substringBeforeLast('.', decoded).trim()
    return withoutExtension.ifEmpty { null }
}

private fun normalizeSubtitleLanguageTag(rawLang: String?): String? {
    val value = rawLang?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return value.replace('_', '-').lowercase(Locale.ROOT)
}

private val TORRENT_TRACKERS = listOf(
    // HTTP trackers (TCP — work even when UDP is blocked)
    "http://tracker.opentrackr.org:1337/announce",
    "http://tracker.openbittorrent.com:80/announce",
    "http://tracker1.bt.moack.co.kr:80/announce",
    "http://tracker.gbitt.info:80/announce",
    // UDP trackers (fallback)
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce"
)

private fun resolvePlayableSourceUrl(stream: Stream): String? {
    val directUrl = stream.url?.trim()?.takeIf { it.isNotEmpty() }
    if (directUrl != null) return directUrl

    val infoHash = stream.infoHash?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Combine hardcoded trackers with addon-provided tracker URLs
    val addonTrackers = stream.sources
        ?.filter { it.startsWith("tracker:") }
        ?.map { it.removePrefix("tracker:") }
        ?: emptyList()
    val allTrackers = (addonTrackers + TORRENT_TRACKERS).distinct()
    val trackerParams = allTrackers.joinToString("") {
        "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}"
    }
    return "magnet:?xt=urn:btih:${infoHash}&dn=Video${trackerParams}"
}

private fun MainActivity.startTorrentWithFallback(
    selectedStream: Stream,
    rankedStreams: List<Stream>,
    onAttempt: (stream: Stream, retryNumber: Int) -> Unit,
    onProgress: (TorrentProgress) -> Unit,
    onReady: (stream: Stream, localUrl: String) -> Unit,
    onExhausted: (String) -> Unit
) {
    val attempts = TorrentFallbackPolicy.buildAttempts(
        selected = selectedStream,
        rankedStreams = rankedStreams,
        maxRetries = 2
    )
    var attemptIndex = 0

    fun startAttempt() {
        val stream = attempts.getOrNull(attemptIndex)
        val magnetUrl = stream?.let(::resolvePlayableSourceUrl)
        if (stream == null || magnetUrl == null || !magnetUrl.startsWith("magnet:", ignoreCase = true)) {
            onExhausted("No playable torrent source was available")
            return
        }

        onAttempt(stream, attemptIndex)
        TorrentService.onStreamReady = { localUrl -> onReady(stream, localUrl) }
        TorrentService.onStreamError = { error ->
            if (attemptIndex + 1 < attempts.size) {
                attemptIndex += 1
                startAttempt()
            } else {
                onExhausted(error)
            }
        }
        TorrentService.onStreamProgress = onProgress

        val intent = Intent(this, TorrentService::class.java).apply {
            putExtra("MAGNET_LINK", magnetUrl)
            putExtra("FILE_IDX", stream.fileIdx ?: -1)
            putExtra("FILE_NAME", stream.behaviorHints?.filename ?: "")
        }
        startService(intent)
    }

    startAttempt()
}

private fun sourceDisplayLabel(contentTitle: String, stream: Stream): String {
    val display = StreamDisplayFormatter.format(contentTitle, stream)
    return "${display.title} • ${display.details}"
}

private fun launchExternalPlayer(context: android.content.Context, url: String) {
    try {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(context, "Unsupported URL scheme", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No external player found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PlayerChoiceDialog(
    onInternal: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Choose Player",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VoidButton(
                        text = "Internal Player",
                        onClick = onInternal,
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "External Player",
                        onClick = onExternal,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExitConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val stayFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { stayFocusRequester.requestFocus() }
    }

    VoidDialog(onDismissRequest = onDismiss, title = "Exit Saab TV?") {
        Text(
            "Are You Sure You Want To Exit?",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.82f)
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VoidButton(
                text = "Stay",
                onClick = onDismiss,
                isPrimary = true,
                modifier = Modifier.weight(1f).focusRequester(stayFocusRequester)
            )
            VoidButton(
                text = "Exit",
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

internal fun buildSourcePayload(
    streams: List<Stream>,
    contentTitle: String
): List<PlayerSourceOption> {
    AppDiagnostics.torBoxEvent("Player Source Picker",
        com.saab.tv.data.stream.TorBoxDiagnosticSummary.sources(streams))
    return streams.mapNotNull { stream ->
        val url = resolvePlayableSourceUrl(stream) ?: return@mapNotNull null
        val parsed = StreamParser.parse(stream)
        PlayerSourceOption(
            id = com.saab.tv.ui.player.base.sourceOptionId(url, stream.fileIdx ?: -1, stream.behaviorHints?.filename.orEmpty()),
            url = url,
            label = sourceDisplayLabel(contentTitle, stream),
            name = stream.name,
            title = stream.title,
            description = stream.description,
            addonTransportUrl = stream.addonTransportUrl,
            infoHash = stream.infoHash,
            videoSize = parsed.sizeBytes,
            qualityHeight = when (parsed.quality) {
                StreamQuality.UHD_4K -> 2160
                StreamQuality.FHD_1080P -> 1080
                StreamQuality.HD_720P -> 720
                StreamQuality.SD_480P -> 480
                StreamQuality.CAM -> 0
                StreamQuality.UNKNOWN -> null
            },
            seeders = parsed.seeds,
            torBoxChecked = stream.torBoxChecked,
            torBoxCached = stream.torBoxCached,
            torBoxSeeders = stream.torBoxSeeders,
            formats = parsed.formats.sorted(),
            fileIdx = stream.fileIdx ?: -1,
            fileName = stream.behaviorHints?.filename ?: "",
            subtitles = buildEmbeddedSubtitlePayload(stream).map {
                PlayerSubtitleSource(it.id, it.url, it.name, it.language, it.sourcePriority)
            }
        )
    }
        .distinctBy { it.id }
}

internal fun findTorrentSwitchSource(
    candidates: List<Stream>, magnetUrl: String, fileIdx: Int, fileName: String
): Stream? = candidates.firstOrNull { candidate ->
    resolvePlayableSourceUrl(candidate) == magnetUrl &&
        (fileIdx < 0 || candidate.fileIdx == fileIdx) &&
        (fileName.isBlank() || candidate.behaviorHints?.filename == fileName)
}

private fun canonicalSubtitleUrlForId(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return rawUrl

    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return trimmed
    val noQuery = trimmed.substringBefore('?').substringBefore('#')
    if (!uri.isAbsolute) return noQuery

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    val path = uri.encodedPath ?: uri.path
    if (scheme.isNullOrBlank() || host.isNullOrBlank() || path.isNullOrBlank()) {
        return noQuery
    }
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$port$path"
}

private fun buildSubtitleFallbackId(
    resolvedUrl: String,
    language: String?,
    name: String
): String {
    val canonicalUrl = canonicalSubtitleUrlForId(resolvedUrl)
    val canonicalLanguage = language.orEmpty().trim().lowercase(Locale.ROOT)
    val canonicalName = name.trim().lowercase(Locale.ROOT)
    return "saabtv-sub:$canonicalLanguage|$canonicalName|$canonicalUrl"
}

private fun buildEmbeddedSubtitlePayload(stream: Stream): List<PlayerSubtitlePayload> {
    return stream.subtitles
        .orEmpty()
        .mapNotNull { subtitle ->
            buildEmbeddedSubtitlePayloadItem(stream, subtitle)
        }
}

private fun buildEmbeddedSubtitlePayloadItem(
    stream: Stream,
    subtitle: StreamSubtitle
): PlayerSubtitlePayload? {
    val rawUrl = subtitle.url?.trim().orEmpty()
    if (rawUrl.isEmpty()) return null

    val resolvedUrl = resolveSubtitleUrl(
        rawUrl = rawUrl,
        addonTransportUrl = subtitle.transportUrl ?: stream.addonTransportUrl
    ) ?: return null

    val fallbackName = subtitleNameFromUrl(resolvedUrl) ?: "Embedded subtitle"
    val name = sanitizeSubtitleSourceName(subtitle.name, fallbackName)
    val language = normalizeSubtitleLanguageTag(subtitle.lang)
    val subtitleId = subtitle.id
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: buildSubtitleFallbackId(
            resolvedUrl = resolvedUrl,
            language = language,
            name = name
        )
    return PlayerSubtitlePayload(
        id = subtitleId,
        url = resolvedUrl,
        name = name,
        language = language,
        sourcePriority = com.saab.tv.ui.player.base.SubtitleSourcePriority.STREAM_PROVIDED
    )
}

private fun buildAddonSubtitlePayload(addonSubtitles: List<AddonSubtitle>): List<PlayerSubtitlePayload> {
    return addonSubtitles.mapNotNull { subtitle ->
        val resolvedUrl = resolveSubtitleUrl(subtitle.url, addonTransportUrl = null) ?: return@mapNotNull null
        val name = sanitizeSubtitleSourceName(subtitle.addonName, "Addon subtitle")
        val language = normalizeSubtitleLanguageTag(subtitle.lang)
        val subtitleId = subtitle.id
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: buildSubtitleFallbackId(
                resolvedUrl = resolvedUrl,
                language = language,
                name = name
            )
        PlayerSubtitlePayload(
            id = subtitleId,
            url = resolvedUrl,
            name = name,
            language = language,
            sourcePriority = com.saab.tv.ui.player.base.SubtitleSourcePriority.ADDON
        )
    }
}

private fun buildSubtitlePayload(stream: Stream, addonSubtitles: List<AddonSubtitle>): List<PlayerSubtitlePayload> {
    return (buildEmbeddedSubtitlePayload(stream) + buildAddonSubtitlePayload(addonSubtitles))
        .distinctBy { payload ->
            val url = payload.url.lowercase(Locale.ROOT)
            val lang = payload.language.orEmpty().lowercase(Locale.ROOT)
            "$url|$lang"
        }
}

private fun handlePlayerSessionEnd(
    sessionResult: PlayerSessionResult,
    selectedPlaybackId: String,
    playbackTrackSelectionStore: PlaybackTrackSelectionStore,
    sourceSelectionStore: SourceSelectionStore,
    pendingSourceSelection: PendingSourceSelection?,
    onConsumePendingSelection: () -> Unit,
    onResumeHintResolved: (String?) -> Unit,
    rememberSourceSelection: Boolean = true
) {
    val playbackId = selectedPlaybackId.trim()
    if (playbackId.isBlank()) {
        onConsumePendingSelection()
        onResumeHintResolved(null)
        return
    }

    onResumeHintResolved(
        if (!sessionResult.isCompleted && sessionResult.positionMs >= SOURCE_SELECTION_COMMIT_MIN_POSITION_MS) {
            playbackId
        } else {
            null
        }
    )

    val hasAudioTrackSelection = !sessionResult.selectedAudioTrackId.isNullOrBlank()
    val hasSubtitleTrackSelection = sessionResult.subtitleSelectionWasManual &&
        !sessionResult.selectedSubtitleTrackId.isNullOrBlank()
    val hasSubtitleDelayChange = sessionResult.subtitleDelayMs != 0L
    if (hasAudioTrackSelection || hasSubtitleTrackSelection || hasSubtitleDelayChange) {
        playbackTrackSelectionStore.updateSelection(
            playbackId = playbackId,
            audioTrackId = sessionResult.selectedAudioTrackId,
            subtitleTrackId = sessionResult.selectedSubtitleTrackId,
            subtitleDelayMs = sessionResult.subtitleDelayMs,
            updateAudio = hasAudioTrackSelection,
            updateSubtitle = hasSubtitleTrackSelection,
            updateSubtitleDelay = true
        )
    }

    pendingSourceSelection?.let { pendingSelection ->
        val pendingPlaybackId = pendingSelection.playbackId.trim()
        if (pendingPlaybackId.isNotEmpty()) {
            val selectedStream = sessionResult.selectedSourceUrl
                ?.let { selectedSourceUrl ->
                    pendingSelection.candidateStreams.firstOrNull { candidate ->
                        resolvePlayableSourceUrl(candidate) == selectedSourceUrl
                    }
                }
                ?: pendingSelection.launchedStream

            val shouldCommitSource = rememberSourceSelection
            if (shouldCommitSource) {
                sourceSelectionStore.rememberSelection(pendingPlaybackId, selectedStream)
            }
        }
    }

    onConsumePendingSelection()
}

private class GridRestoreState {
    var focusedIndex: Int? = null
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sourceSelectionStore: SourceSelectionStore
    @Inject
    lateinit var playbackTrackSelectionStore: PlaybackTrackSelectionStore
    @Inject
    lateinit var addonRepository: AddonRepository
    @Inject
    lateinit var subtitleRepository: SubtitleRepository
    @Inject
    lateinit var introRepository: IntroRepository
    @Inject
    lateinit var profileConfigurationManager: ProfileConfigurationManager
    @Inject
    lateinit var streamSortingService: StreamSortingService
    @Inject lateinit var accountSync: com.saab.tv.data.account.AccountSyncManager
    @Inject lateinit var accountAuth: com.saab.tv.data.account.AccountAuthManager

    private var splashOverlay: SaabTvSplashView? = null
    private var splashStartedAtMs = 0L
    private var splashDismissPosted = false
    private var splashAppReady = false
    private var splashIntroFinished = false
    private val _splashFinished = mutableStateOf(false)
    private var backgroundedAt = 0L
    private var allowAccountRefresh = false

    override fun onResume() {
        super.onResume()
        val checkCloud = backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt >= 5_000L && allowAccountRefresh
        backgroundedAt = 0L
        if (checkCloud && accountAuth.hasSession) lifecycleScope.launch {
            try {
                if (accountSync.newerCloudBackupAvailable() && allowAccountRefresh &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    accountSync.stop()
                    com.saab.tv.ui.account.AccountRestart.restart(this@MainActivity)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                AppDiagnostics.failure(this@MainActivity, "Cloud Sync", "Foreground Check Failed", failure)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
        if (!accountAuth.hasSession) return
        accountSync.flushAfterBackground()
    }

    override fun onDestroy() {
        dismissSplash()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (splashOverlay == null) {
            outState.putBoolean(KEY_SPLASH_SHOWN, true)
        }
    }

    private fun onSplashAppReady() {
        if (splashAppReady) return
        splashAppReady = true
        scheduleSplashDismiss()
    }

    private fun onSplashIntroFinished() {
        if (splashIntroFinished) return
        splashIntroFinished = true
        scheduleSplashDismiss()
    }

    private fun scheduleSplashDismiss() {
        val overlay = splashOverlay ?: return
        if (!splashAppReady || !splashIntroFinished || splashDismissPosted) return
        splashDismissPosted = true
        val elapsed = SystemClock.uptimeMillis() - splashStartedAtMs
        val remaining = (SPLASH_MIN_DURATION_MS - elapsed).coerceAtLeast(0L)
        overlay.postDelayed({
            if (splashOverlay !== overlay) return@postDelayed
            overlay.finish {
                if (splashOverlay === overlay) dismissSplash()
            }
        }, remaining)
    }

    private fun dismissSplash() {
        splashOverlay?.let { overlay ->
            overlay.stop()
            (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        }
        splashOverlay = null
        splashDismissPosted = false
        splashIntroFinished = false
        _splashFinished.value = true
    }

    private fun attachSplashOverlay() {
        if (splashOverlay != null) return
        val overlay = SaabTvSplashView(this, ::onSplashIntroFinished)
        splashStartedAtMs = SystemClock.uptimeMillis()
        addContentView(overlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        splashOverlay = overlay
        overlay.start()
        overlay.postDelayed({
            if (splashOverlay === overlay) onSplashIntroFinished()
        }, SPLASH_FAILSAFE_DURATION_MS)
        scheduleSplashDismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sanitize saved state: R8 can obfuscate Parcelable class names, causing
        // BadParcelableException on process-death restore. Clear the bundle if corrupt.
        val safeState = savedInstanceState?.let { bundle ->
            try {
                bundle.keySet() // forces unparcel — throws if any class is missing
                bundle
            } catch (_: android.os.BadParcelableException) {
                null
            }
        }
        super.onCreate(safeState)
        if (!accountAuth.hasSession) {
            startActivity(Intent(this, com.saab.tv.ui.account.AccountEntryActivity::class.java))
            finish()
            return
        }
        com.saab.tv.data.account.AccountStorage.bindRunningAccount(this)
        (application as SaabTvApplication).warmupForAccount()
        accountSync.start()
        window.setFormat(android.graphics.PixelFormat.RGBA_8888)

        // Fix sideload launch bug: pressing Home and returning re-creates the activity
        // instead of resuming it when the APK was installed via adb/sideload.
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            && Intent.ACTION_MAIN == intent.action) {
            finish()
            return
        }

        val splashEnabledInProfile = profileConfigurationManager.getLastActiveProfileId()?.let {
            profileConfigurationManager.getCachedSplashEnabled(it)
        } ?: true
        val showSplash = splashEnabledInProfile && safeState?.getBoolean(KEY_SPLASH_SHOWN) != true
        if (!showSplash) _splashFinished.value = true

        setContent {

            val mainViewModel = hiltViewModel<MainViewModel>()
            val themeManager = hiltViewModel<ThemeManager>()
            val currentProfile by mainViewModel.activeProfile.collectAsStateWithLifecycle()
            var sessionProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
            var sessionRestoreAttemptedProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
            var activeView by rememberSaveable { mutableStateOf("menu") }
            var selectedMovieId by rememberSaveable { mutableStateOf("") }
            var selectedMovieType by rememberSaveable { mutableStateOf("movie") }
            var selectedVideoUrl by rememberSaveable { mutableStateOf("") }
            var selectedTrailerAudioUrl by rememberSaveable { mutableStateOf("") }
            var selectedTrailerVariants by remember {
                mutableStateOf<List<TrailerPlaybackVariant>>(emptyList())
            }
            var torrentProgress by remember { mutableStateOf<TorrentProgress?>(null) }
            var selectedMovieTitle by rememberSaveable { mutableStateOf("") }
            var selectedMoviePoster by rememberSaveable { mutableStateOf("") }
            var selectedMovieBackground by rememberSaveable { mutableStateOf("") }
            var selectedMovieLogo by rememberSaveable { mutableStateOf("") }
            var selectedAddonBaseUrl by rememberSaveable { mutableStateOf<String?>(null) }
            var detailsResumePlaybackHint by rememberSaveable { mutableStateOf<String?>(null) }
            var autoResumeFromContinue by rememberSaveable { mutableStateOf(false) }
            var trailerReturnToken by rememberSaveable { mutableStateOf(0) }
            var showTrailerError by remember { mutableStateOf(false) }
            var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
            var searchSessionId by rememberSaveable { mutableStateOf(0L) }
            var selectedPlaybackId by rememberSaveable { mutableStateOf("") }
            var selectedPlaybackType by rememberSaveable { mutableStateOf("movie") }
            var selectedPlaybackTitle by rememberSaveable { mutableStateOf("") }
            var selectedPlaybackPoster by rememberSaveable { mutableStateOf("") }
            var previousView by rememberSaveable { mutableStateOf("menu") }
            val playerState = remember { PlayerState() }


            LaunchedEffect(currentProfile?.id) {
                val profileId = currentProfile?.id
                if (profileId != null) {
                    sessionProfileId = profileId
                    sessionRestoreAttemptedProfileId = null
                }
            }

            LaunchedEffect(currentProfile, sessionProfileId, sessionRestoreAttemptedProfileId) {
                if (currentProfile != null) return@LaunchedEffect
                val profileIdToRestore = sessionProfileId ?: return@LaunchedEffect
                if (sessionRestoreAttemptedProfileId == profileIdToRestore) return@LaunchedEffect

                sessionRestoreAttemptedProfileId = profileIdToRestore
                mainViewModel.login(profileIdToRestore)
            }

            // Resolve theme from profile's themeId
            val currentTheme by themeManager.currentTheme.collectAsStateWithLifecycle()

            // Get round corners setting from profile (default true)
            val roundCorners = currentProfile?.roundCorners ?: true
            val hubRoundCorners = currentProfile?.hubRoundCorners ?: true
            
            // Update theme when profile changes
            LaunchedEffect(currentProfile) {
                currentProfile?.let { profile ->
                    themeManager.setCurrentProfile(profile.id, profile.themeId)
                }
            }

            // Signal native splash to resume once first composition is done
            LaunchedEffect(Unit) { onSplashAppReady() }

            SaabTvTheme(theme = currentTheme) {
                CompositionLocalProvider(
                    LocalRoundCorners provides roundCorners,
                    LocalHubRoundCorners provides hubRoundCorners
                ) {
                SaabTvBackground {
                    // Last-resort guard for every route. Screen/dialog handlers
                    // registered below still handle ordinary back navigation first.
                    BackHandler { showExitConfirmation = true }
                    if (currentProfile == null) {
                        BackHandler {
                            showExitConfirmation = true
                        }

                        val isRestoringSession = sessionProfileId != null
                        if (!isRestoringSession) {
                            // PROFILE SELECTION / CREATION
                            // Always use VOID theme for profile selection (black & white)
                            SaabTvTheme(theme = DefaultThemes.VOID) {
                                val profileViewModel = hiltViewModel<ProfileViewModel>()
                                val profiles by profileViewModel.profiles.collectAsStateWithLifecycle()

                                ProfileScreen(
                                    profiles = profiles,
                                    onProfileSelected = {
                                        sessionProfileId = it.id
                                        sessionRestoreAttemptedProfileId = null
                                        mainViewModel.login(it.id)
                                    }
                                )
                            }
                        }
                    } else {
                        // MAIN APP CONTENT
                        var currentNav by remember { mutableStateOf(NavDestination.Home) }
                        SideEffect { allowAccountRefresh = activeView in listOf("menu", "details", "grid") && currentNav != NavDestination.Settings && currentNav != NavDestination.Profile }
                        LaunchedEffect(currentNav) {
                            AppDiagnostics.event(this@MainActivity, "Navigation", "Main Section", "section=$currentNav")
                        }
                        
                        // Grid view state
                        var gridViewTitle by rememberSaveable { mutableStateOf("") }
                        var gridViewItems by remember { mutableStateOf<List<MetaItem>>(emptyList()) }
                        var gridViewConfigId by rememberSaveable { mutableStateOf("") }
                        val gridRestoreState = remember { GridRestoreState() }

                        // Search focus restoration
                        val searchMoviesViewMoreRequester = remember { FocusRequester() }
                        val searchSeriesViewMoreRequester = remember { FocusRequester() }
                        val searchResultsRequester = remember { FocusRequester() }
                        var searchFocusTarget by remember { mutableStateOf<String?>(null) }
                        var searchLastFocusedId by remember { mutableStateOf<String?>(null) }

                        // Track where we came from for proper back navigation
                        val uiScope = rememberCoroutineScope()

                        // Focus Traffic Control
                        val drawerRequesters = remember { NavDestination.values().associateWith { FocusRequester() } }
                        val homeEntryRequester = remember { FocusRequester() }
                        val searchEntryRequester = remember { FocusRequester() }
                        val settingsEntryRequester = remember { FocusRequester() }
                        var settingsScreenFocused by remember { mutableStateOf(false) }
                        val watchlistEntryRequester = remember { FocusRequester() }

                        // STATE CHANGE TRIGGER:
                        LaunchedEffect(currentNav, activeView) {
                            if (activeView != "menu") return@LaunchedEffect
                            when(currentNav) {
                                // HomeScreen requests focus itself once data is ready.
                                // Avoid requesting early into the loading placeholder, which can
                                // cause a brief nav -> content -> nav -> content flicker.
                                NavDestination.Home, NavDestination.Movies, NavDestination.Series, NavDestination.Ott -> Unit
                                NavDestination.Search -> {
                                    delay(200) // Increased for stability
                                    val target = searchFocusTarget
                                    if (target != null) {
                                        searchFocusTarget = null
                                        when (target) {
                                            "movies" -> searchMoviesViewMoreRequester.requestFocusSafely()
                                            "series" -> searchSeriesViewMoreRequester.requestFocusSafely()
                                            "poster" -> searchResultsRequester.requestFocusSafely()
                                        }
                                    } else {
                                        searchEntryRequester.requestFocusWhenAttached()
                                    }
                                }
                                NavDestination.Settings -> {
                                    delay(200) // Increased for stability
                                    settingsEntryRequester.requestFocusWhenAttached { settingsScreenFocused }
                                }
                                NavDestination.Watchlist -> {
                                    delay(200)
                                    watchlistEntryRequester.requestFocusWhenAttached()
                                }
                                else -> Unit
                            }
                        }

                        // Focus restoration after navPosition change (Crossfade animation)
                        val navPosition = currentProfile?.navPosition ?: "left"
                        LaunchedEffect(navPosition) {
                            if (activeView == "menu" && currentNav == NavDestination.Settings) {
                                delay(450) // Wait for Crossfade (400ms) + buffer
                                settingsEntryRequester.requestFocusWhenAttached { settingsScreenFocused }
                            }
                        }


                            // CONDITIONAL NAVIGATION RENDERING (no animation)
                            val view = activeView
                            if (view == "menu") {
                                var settingsContentFocused by remember { mutableStateOf(false) }

                                // Shared content composable
                                // Shared navigation handler
                                val handleNavigate: (NavDestination) -> Unit = { destination ->
                                    if (destination == NavDestination.Exit) {
                                        showExitConfirmation = true
                                    } else if (currentNav == destination) {
                                        // Already here - just focus content
                                        when(destination) {
                                            NavDestination.Home, NavDestination.Movies, NavDestination.Series, NavDestination.Ott -> homeEntryRequester.requestFocusSafely()
                                            NavDestination.Search -> searchEntryRequester.requestFocusSafely()
                                            NavDestination.Settings -> uiScope.launch { settingsEntryRequester.requestFocusWhenAttached { settingsScreenFocused } }
                                            NavDestination.Watchlist -> watchlistEntryRequester.requestFocusSafely()
                                            else -> {}
                                        }
                                    } else {
                                        if (currentNav == NavDestination.Search) searchFocusTarget = null
                                        if (currentNav == NavDestination.Settings) settingsContentFocused = false
                                        if (destination == NavDestination.Search) searchSessionId++
                                        currentNav = destination
                                    }
                                }

                                // Shared enter content handler
                                val handleEnterContent: () -> Unit = {
                                    when(currentNav) {
                                        NavDestination.Home, NavDestination.Movies, NavDestination.Series, NavDestination.Ott -> homeEntryRequester.requestFocusSafely()
                                        NavDestination.Search -> searchEntryRequester.requestFocusSafely()
                                        NavDestination.Settings -> uiScope.launch { settingsEntryRequester.requestFocusWhenAttached { settingsScreenFocused } }
                                        NavDestination.Watchlist -> watchlistEntryRequester.requestFocusSafely()
                                        else -> {}
                                    }
                                }

                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                Crossfade(targetState = navPosition, animationSpec = tween(400), label = "NavSwitcher") { position ->
                                if (position == "top") {
                                    TopNavigationBar(
                                        currentDestination = currentNav,
                                        currentProfile = currentProfile,
                                        topNavRequesters = drawerRequesters,
                                        onNavigate = handleNavigate,
                                        onEnterContent = handleEnterContent,
                                        onLogout = {
                                            sessionProfileId = null
                                            sessionRestoreAttemptedProfileId = null

                                            activeView = "menu"
                                            themeManager.resetTheme()
                                            mainViewModel.logout()
                                        },
                                        onExit = { showExitConfirmation = true },
                                        content = {
                                            when (currentNav) {
                                                NavDestination.Home, NavDestination.Movies, NavDestination.Series, NavDestination.Ott -> {
                                                    val vm = hiltViewModel<HomeViewModel>()
                                                    val tab = when (currentNav) {
                                                        NavDestination.Home -> "home"
                                                        NavDestination.Movies -> "movies"
                                                        NavDestination.Series -> "series"
                                                        NavDestination.Ott -> "ott"
                                                        else -> "home"
                                                    }
                                                    val dashboardTab = DashboardTab.fromString(tab)

                                                    key(tab) {
                                                        LaunchedEffect(tab, currentProfile?.id) { vm.loadScreen(tab, currentProfile) }
                                                        HomeScreen(
                                                            tab = dashboardTab,
                                                            screenNameOverride = tab.takeIf { it == "ott" },
                                                            viewModel = vm,
                                                            currentProfile = currentProfile,
                                                            entryRequester = homeEntryRequester,
                                                            drawerRequester = drawerRequesters[currentNav]!!,
                                                            onMovieClick = { movie ->
                                                                autoResumeFromContinue = false
                                                                selectedMovieId = movie.id
                                                                selectedMovieType = movie.type
                                                                selectedMovieTitle = movie.name
                                                                selectedMoviePoster = movie.poster ?: ""
                                                                selectedMovieBackground = movie.background ?: ""
                                                                selectedMovieLogo = movie.logo ?: ""
                                                                selectedAddonBaseUrl = movie.addonBaseUrl
                                                                detailsResumePlaybackHint = null
                                                                selectedPlaybackId = movie.id
                                                                selectedPlaybackType = movie.type
                                                                selectedPlaybackTitle = movie.name
                                                                selectedPlaybackPoster = movie.poster ?: ""
                                                                previousView = "menu"
                                                                activeView = "details"
                                                            },
                                                            onContinueClick = { movie ->
                                                                selectedMovieId = movie.id
                                                                selectedMovieType = movie.type
                                                                selectedMovieTitle = movie.name
                                                                selectedMoviePoster = movie.poster ?: ""
                                                                selectedMovieBackground = movie.background ?: ""
                                                                selectedMovieLogo = movie.logo ?: ""
                                                                selectedAddonBaseUrl = movie.addonBaseUrl
                                                                detailsResumePlaybackHint = null
                                                                selectedPlaybackId = movie.id
                                                                selectedPlaybackType = movie.type
                                                                selectedPlaybackTitle = movie.name
                                                                autoResumeFromContinue = true
                                                                previousView = "menu"
                                                                activeView = "resume"
                                                            },
                                                            onTrailerClick = { youtubeKey, trailerName ->
                                                                YouTubeTrailerActivity.createIntent(this@MainActivity, youtubeKey, trailerName)?.let(::startActivity)
                                                            },
                                                            onViewMore = { title, items, configId ->
                                                                gridViewTitle = title
                                                                gridViewItems = items
                                                                gridViewConfigId = configId
                                                                activeView = "grid"
                                                            }
                                                        )
                                                    }
                                                }
                                                NavDestination.Search -> {
                                                    val searchHomeVm = hiltViewModel<HomeViewModel>()
                                                    SearchScreen(
                                                        searchSessionId = searchSessionId,
                                                        currentProfile = currentProfile,
                                                        watchedIds = searchHomeVm.state.collectAsStateWithLifecycle().value.watchedIds,
                                                        onMovieClick = { movie ->
                                                            selectedMovieId = movie.id
                                                            selectedMovieType = movie.type
                                                            selectedMovieTitle = movie.name
                                                            selectedMoviePoster = movie.poster ?: ""
                                                            selectedMovieBackground = movie.background ?: ""
                                                            selectedMovieLogo = movie.logo ?: ""
                                                            selectedAddonBaseUrl = movie.addonBaseUrl
                                                            detailsResumePlaybackHint = null
                                                            selectedPlaybackId = movie.id
                                                            selectedPlaybackType = movie.type
                                                            selectedPlaybackTitle = movie.name
                                                            selectedPlaybackPoster = movie.poster ?: ""
                                                            searchFocusTarget = "poster"
                                                            previousView = "menu"
                                                            activeView = "details"
                                                        },
                                                        onViewMore = { title, items ->
                                                            searchFocusTarget = if (title == "Movies") "movies" else "series"
                                                            gridViewTitle = title
                                                            gridViewItems = items
                                                            gridViewConfigId = ""
                                                            activeView = "grid"
                                                        },
                                                        moviesViewMoreRequester = searchMoviesViewMoreRequester,
                                                        seriesViewMoreRequester = searchSeriesViewMoreRequester,
                                                        resultsRequester = searchResultsRequester,
                                                        lastFocusedId = searchLastFocusedId,
                                                        onFocusedIdChange = { searchLastFocusedId = it },
                                                        entryRequester = searchEntryRequester,
                                                        drawerRequester = drawerRequesters[NavDestination.Search]!!
                                                    )
                                                }
                                                NavDestination.Profile -> {
                                                    sessionProfileId = null
                                                    sessionRestoreAttemptedProfileId = null
                                                    activeView = "menu"
                                                    themeManager.resetTheme()
                                                    mainViewModel.logout()
                                                }
                                                NavDestination.Watchlist -> {
                                                    val watchlistHomeVm = hiltViewModel<HomeViewModel>()
                                                    WatchlistScreen(
                                                        currentProfile = currentProfile,
                                                        entryRequester = watchlistEntryRequester,
                                                        drawerRequester = drawerRequesters[NavDestination.Watchlist]!!,
                                                        watchedIds = watchlistHomeVm.state.collectAsStateWithLifecycle().value.watchedIds,
                                                        onMovieClick = { movie ->
                                                            selectedMovieId = movie.id
                                                            selectedMovieType = movie.type
                                                            selectedMovieTitle = movie.name
                                                            selectedMoviePoster = movie.poster ?: ""
                                                            selectedMovieBackground = movie.background ?: ""
                                                            selectedMovieLogo = movie.logo ?: ""
                                                            selectedAddonBaseUrl = movie.addonBaseUrl
                                                            detailsResumePlaybackHint = null
                                                            selectedPlaybackId = movie.id
                                                            selectedPlaybackType = movie.type
                                                            selectedPlaybackTitle = movie.name
                                                            selectedPlaybackPoster = movie.poster ?: ""
                                                            previousView = "menu"
                                                            activeView = "details"
                                                        }
                                                    )
                                                }
                                                NavDestination.Settings -> {
                                                    val homeVm = hiltViewModel<HomeViewModel>()
                                                    SettingsScreen(
                                                        currentProfile = currentProfile,
                                                        onBack = {
                                                            currentNav = NavDestination.Home
                                                            drawerRequesters[NavDestination.Home]?.requestFocusSafely()
                                                        },
                                                        entryRequester = settingsEntryRequester,
                                                        drawerRequester = drawerRequesters[NavDestination.Settings]!!,
                                                        onDashboardChanged = { homeVm.invalidate() },
                                                        onScreenFocusChanged = { settingsScreenFocused = it },
                                                        onContentFocusChanged = { settingsContentFocused = it }
                                                    )
                                                }
                                                NavDestination.Exit -> { /* App closes */ }
                                            }
                                        }
                                    )
                                } else { // position == "left"
                                    NavDrawer(
                                        currentDestination = currentNav,
                                        currentProfile = currentProfile,
                                        drawerRequesters = drawerRequesters,
                                        onNavigate = handleNavigate,
                                        onClose = handleEnterContent,
                                        content = {
                                            when (currentNav) {
                                                NavDestination.Home, NavDestination.Movies, NavDestination.Series, NavDestination.Ott -> {
                                                    val vm = hiltViewModel<HomeViewModel>()
                                                    val tab = when (currentNav) {
                                                        NavDestination.Home -> "home"
                                                        NavDestination.Movies -> "movies"
                                                        NavDestination.Series -> "series"
                                                        NavDestination.Ott -> "ott"
                                                        else -> "home"
                                                    }
                                                    val dashboardTab = DashboardTab.fromString(tab)

                                                    key(tab) {
                                                        LaunchedEffect(tab, currentProfile?.id) { vm.loadScreen(tab, currentProfile) }
                                                        HomeScreen(
                                                            tab = dashboardTab,
                                                            screenNameOverride = tab.takeIf { it == "ott" },
                                                            viewModel = vm,
                                                            currentProfile = currentProfile,
                                                            entryRequester = homeEntryRequester,
                                                            drawerRequester = drawerRequesters[currentNav]!!,
                                                            onMovieClick = { movie ->
                                                                autoResumeFromContinue = false
                                                                selectedMovieId = movie.id
                                                                selectedMovieType = movie.type
                                                                selectedMovieTitle = movie.name
                                                                selectedMoviePoster = movie.poster ?: ""
                                                                selectedMovieBackground = movie.background ?: ""
                                                                selectedMovieLogo = movie.logo ?: ""
                                                                selectedAddonBaseUrl = movie.addonBaseUrl
                                                                detailsResumePlaybackHint = null
                                                                selectedPlaybackId = movie.id
                                                                selectedPlaybackType = movie.type
                                                                selectedPlaybackTitle = movie.name
                                                                selectedPlaybackPoster = movie.poster ?: ""
                                                                previousView = "menu"
                                                                activeView = "details"
                                                            },
                                                            onContinueClick = { movie ->
                                                                selectedMovieId = movie.id
                                                                selectedMovieType = movie.type
                                                                selectedMovieTitle = movie.name
                                                                selectedMoviePoster = movie.poster ?: ""
                                                                selectedMovieBackground = movie.background ?: ""
                                                                selectedMovieLogo = movie.logo ?: ""
                                                                selectedAddonBaseUrl = movie.addonBaseUrl
                                                                detailsResumePlaybackHint = null
                                                                selectedPlaybackId = movie.id
                                                                selectedPlaybackType = movie.type
                                                                selectedPlaybackTitle = movie.name
                                                                autoResumeFromContinue = true
                                                                previousView = "menu"
                                                                activeView = "resume"
                                                            },
                                                            onTrailerClick = { youtubeKey, trailerName ->
                                                                YouTubeTrailerActivity.createIntent(this@MainActivity, youtubeKey, trailerName)?.let(::startActivity)
                                                            },
                                                            onViewMore = { title, items, configId ->
                                                                gridViewTitle = title
                                                                gridViewItems = items
                                                                gridViewConfigId = configId
                                                                activeView = "grid"
                                                            }
                                                        )
                                                    }
                                                }
                                                NavDestination.Search -> {
                                                    val searchHomeVm = hiltViewModel<HomeViewModel>()
                                                    SearchScreen(
                                                        searchSessionId = searchSessionId,
                                                        currentProfile = currentProfile,
                                                        watchedIds = searchHomeVm.state.collectAsStateWithLifecycle().value.watchedIds,
                                                        onMovieClick = { movie ->
                                                            selectedMovieId = movie.id
                                                            selectedMovieType = movie.type
                                                            selectedMovieTitle = movie.name
                                                            selectedMoviePoster = movie.poster ?: ""
                                                            selectedMovieBackground = movie.background ?: ""
                                                            selectedMovieLogo = movie.logo ?: ""
                                                            selectedAddonBaseUrl = movie.addonBaseUrl
                                                            detailsResumePlaybackHint = null
                                                            selectedPlaybackId = movie.id
                                                            selectedPlaybackType = movie.type
                                                            selectedPlaybackTitle = movie.name
                                                            selectedPlaybackPoster = movie.poster ?: ""
                                                            searchFocusTarget = "poster"
                                                            previousView = "menu"
                                                            activeView = "details"
                                                        },
                                                        onViewMore = { title, items ->
                                                            searchFocusTarget = if (title == "Movies") "movies" else "series"
                                                            gridViewTitle = title
                                                            gridViewItems = items
                                                            gridViewConfigId = ""
                                                            activeView = "grid"
                                                        },
                                                        moviesViewMoreRequester = searchMoviesViewMoreRequester,
                                                        seriesViewMoreRequester = searchSeriesViewMoreRequester,
                                                        resultsRequester = searchResultsRequester,
                                                        lastFocusedId = searchLastFocusedId,
                                                        onFocusedIdChange = { searchLastFocusedId = it },
                                                        entryRequester = searchEntryRequester,
                                                        drawerRequester = drawerRequesters[NavDestination.Search]!!
                                                    )
                                                }
                                                NavDestination.Profile -> {
                                                    sessionProfileId = null
                                                    sessionRestoreAttemptedProfileId = null
                                                    activeView = "menu"
                                                    themeManager.resetTheme()
                                                    mainViewModel.logout()
                                                }
                                                NavDestination.Watchlist -> {
                                                    val watchlistHomeVm = hiltViewModel<HomeViewModel>()
                                                    WatchlistScreen(
                                                        currentProfile = currentProfile,
                                                        entryRequester = watchlistEntryRequester,
                                                        drawerRequester = drawerRequesters[NavDestination.Watchlist]!!,
                                                        watchedIds = watchlistHomeVm.state.collectAsStateWithLifecycle().value.watchedIds,
                                                        onMovieClick = { movie ->
                                                            selectedMovieId = movie.id
                                                            selectedMovieType = movie.type
                                                            selectedMovieTitle = movie.name
                                                            selectedMoviePoster = movie.poster ?: ""
                                                            selectedMovieBackground = movie.background ?: ""
                                                            selectedMovieLogo = movie.logo ?: ""
                                                            selectedAddonBaseUrl = movie.addonBaseUrl
                                                            detailsResumePlaybackHint = null
                                                            selectedPlaybackId = movie.id
                                                            selectedPlaybackType = movie.type
                                                            selectedPlaybackTitle = movie.name
                                                            selectedPlaybackPoster = movie.poster ?: ""
                                                            previousView = "menu"
                                                            activeView = "details"
                                                        }
                                                    )
                                                }
                                                NavDestination.Settings -> {
                                                    val homeVm = hiltViewModel<HomeViewModel>()
                                                    SettingsScreen(
                                                        currentProfile = currentProfile,
                                                        onBack = {
                                                            currentNav = NavDestination.Home
                                                            drawerRequesters[NavDestination.Home]?.requestFocusSafely()
                                                        },
                                                        entryRequester = settingsEntryRequester,
                                                        drawerRequester = drawerRequesters[NavDestination.Settings]!!,
                                                        onDashboardChanged = { homeVm.invalidate() },
                                                        onScreenFocusChanged = { settingsScreenFocused = it },
                                                        onContentFocusChanged = { settingsContentFocused = it }
                                                    )
                                                }
                                                NavDestination.Exit -> { /* App closes */ }
                                            }
                                        }
                                    )
                                }
                                } // Crossfade end
                                } // Double-back Box end
                        } else if (view == "grid") {
                            val gridVm = hiltViewModel<HomeViewModel>()
                            GridViewScreen(
                                title = gridViewTitle,
                                items = gridViewItems,
                                lastFocusedIndex = gridRestoreState.focusedIndex,
                                onFocusChange = { gridRestoreState.focusedIndex = it },
                                onMovieClick = { movie ->
                                    selectedMovieId = movie.id
                                    selectedMovieType = movie.type
                                    selectedMovieTitle = movie.name
                                    selectedMoviePoster = movie.poster ?: ""
                                    selectedMovieBackground = movie.background ?: ""
                                    selectedMovieLogo = movie.logo ?: ""
                                    selectedAddonBaseUrl = movie.addonBaseUrl
                                    detailsResumePlaybackHint = null
                                    selectedPlaybackId = movie.id
                                    selectedPlaybackType = movie.type
                                    selectedPlaybackTitle = movie.name
                                    selectedPlaybackPoster = movie.poster ?: ""
                                    previousView = "grid"
                                    activeView = "details"
                                },
                                onBack = { 
                                    gridRestoreState.focusedIndex = null  // Reset for next time
                                    gridRestoreState.scrollIndex = 0  // Reset scroll position
                                    gridRestoreState.scrollOffset = 0
                                    activeView = "menu"
                                },
                                onLoadMore = {
                                    if (gridViewConfigId.isNotEmpty()) {
                                        gridVm.loadMoreItems(gridViewConfigId)
                                    }
                                },
                                initialScrollIndex = gridRestoreState.scrollIndex,
                                initialScrollOffset = gridRestoreState.scrollOffset,
                                onScrollPositionChange = { index, offset ->
                                    gridRestoreState.scrollIndex = index
                                    gridRestoreState.scrollOffset = offset
                                },
                                watchedIds = gridVm.state.collectAsStateWithLifecycle().value.watchedIds
                            )
                            // Sync gridViewItems when ViewModel state updates (after loadMoreItems)
                            val vmState by gridVm.state.collectAsStateWithLifecycle()
                            LaunchedEffect(vmState.rows) {
                                if (gridViewConfigId.isNotEmpty()) {
                                    val updatedRow = vmState.rows.find { it.configId == gridViewConfigId }
                                    if (updatedRow != null && updatedRow.items.size > gridViewItems.size) {
                                        gridViewItems = updatedRow.items
                                    }
                                }
                            }
                        } else if (view == "details" || view == "resume" || (view == "player" && selectedPlaybackId.startsWith("trailer_"))) {
                            val detailsNavController = rememberNavController()
                            val startRoute = "detail/${java.net.URLEncoder.encode(selectedMovieType, "UTF-8")}/${java.net.URLEncoder.encode(selectedMovieId, "UTF-8")}?addon=${java.net.URLEncoder.encode(selectedAddonBaseUrl ?: "", "UTF-8")}&resume=${java.net.URLEncoder.encode(detailsResumePlaybackHint ?: "", "UTF-8")}"

                            // Navigate to initial details when first entering
                            LaunchedEffect(selectedMovieType, selectedMovieId) {
                                val currentRoute = detailsNavController.currentBackStackEntry?.destination?.route
                                if (currentRoute == null || currentRoute == "detail_start") {
                                    detailsNavController.navigate(startRoute) {
                                        popUpTo("detail_start") { inclusive = true }
                                    }
                                }
                            }

                            BackHandler {
                                if (!detailsNavController.popBackStack()) {
                                    autoResumeFromContinue = false
                                    activeView = previousView
                                }
                            }

                            // Shared onPlayClick lambda for all detail screens
                            val onPlayClick: (String, String, String, String, String, String, com.saab.tv.data.model.stremio.Stream, List<com.saab.tv.domain.AddonSubtitle>, List<com.saab.tv.data.model.stremio.Stream>, List<com.saab.tv.data.model.stremio.MetaVideo>) -> Unit = { url, playbackId, playbackType, playbackTitle, seriesTitle, logo, stream, addonSubtitles, availableStreams, episodes ->
                                val resolvedPlaybackTitle = playbackTitle.ifBlank { selectedMovieTitle }
                                val resolvedSeriesTitle = seriesTitle.ifBlank { selectedMovieTitle }
                                val isSeriesPlayback = playbackType.equals("series", ignoreCase = true) ||
                                    playbackType.equals("tv", ignoreCase = true)
                                if (isSeriesPlayback && resolvedSeriesTitle.isNotBlank()) {
                                    selectedMovieTitle = resolvedSeriesTitle
                                }
                                if (isSeriesPlayback) {
                                    selectedMovieId = seriesIdFromPlaybackId(playbackId)
                                    selectedMovieType = "series"
                                }
                                if (logo.isNotBlank()) selectedMovieLogo = logo
                                playerState.currentEpisodeList = normalizeEpisodeList(episodes)
                                playerState.currentStream = stream
                                val subtitlePayload = buildSubtitlePayload(stream, addonSubtitles)
                                val sourcePayloadInput = if (availableStreams.isNotEmpty()) availableStreams else listOf(stream)
                                val sourcePayload = buildSourcePayload(
                                    streams = sourcePayloadInput,
                                    contentTitle = resolvedPlaybackTitle
                                )
                                playerState.pendingSourceSelection = PendingSourceSelection(
                                    playbackId = playbackId,
                                    launchedStream = stream,
                                    candidateStreams = sourcePayloadInput
                                )
                                if (url.startsWith("magnet:")) {
                                    uiScope.launch {
                                        mainViewModel.persistActiveProfileState()
                                        selectedPlaybackId = playbackId
                                        selectedPlaybackType = playbackType
                                        selectedPlaybackTitle = resolvedPlaybackTitle
                                        selectedPlaybackPoster = selectedMoviePoster
                                        selectedTrailerAudioUrl = ""
                                        selectedTrailerVariants = emptyList()
                                        selectedVideoUrl = ""
                                        torrentProgress = TorrentProgress("Connecting to peers...")
                                        autoResumeFromContinue = false
                                        activeView = "player"
                                        startTorrentWithFallback(
                                            selectedStream = stream,
                                            rankedStreams = sourcePayloadInput,
                                            onAttempt = { attemptedStream, retryNumber ->
                                                playerState.currentStream = attemptedStream
                                                playerState.pendingSourceSelection = PendingSourceSelection(
                                                    playbackId = playbackId,
                                                    launchedStream = attemptedStream,
                                                    candidateStreams = sourcePayloadInput
                                                )
                                                playerState.selectedPlayerSubtitles = buildSubtitlePayload(
                                                    attemptedStream,
                                                    addonSubtitles
                                                )
                                                playerState.selectedPlayerSources = buildSourcePayload(
                                                    sourcePayloadInput,
                                                    resolvedPlaybackTitle
                                                )
                                                selectedVideoUrl = ""
                                                torrentProgress = TorrentProgress(
                                                    if (retryNumber == 0) "Connecting to peers..."
                                                    else "Trying backup torrent $retryNumber of 2..."
                                                )
                                            },
                                            onProgress = { progress -> torrentProgress = progress },
                                            onReady = { _, localUrl ->
                                                torrentProgress = null
                                                selectedVideoUrl = localUrl
                                            },
                                            onExhausted = { error ->
                                                torrentProgress = null
                                                selectedVideoUrl = ""
                                                if (BuildConfig.DEBUG) Log.e("SaabTvTorrent", "Stream error: $error")
                                            }
                                        )
                                    }
                                } else {
                                    stopService(Intent(this@MainActivity, TorrentService::class.java))
                                    uiScope.launch {
                                        mainViewModel.persistActiveProfileState()
                                        selectedPlaybackId = playbackId
                                        selectedPlaybackType = playbackType
                                        selectedPlaybackTitle = resolvedPlaybackTitle
                                        selectedPlaybackPoster = selectedMoviePoster
                                        selectedTrailerAudioUrl = ""
                                        selectedTrailerVariants = emptyList()
                                        playerState.selectedPlayerSubtitles = subtitlePayload
                                        playerState.selectedPlayerSources = sourcePayload
                                        selectedVideoUrl = url
                                        when (currentProfile?.playerPreference) {
                                            "external" -> {
                                                autoResumeFromContinue = false
                                                launchExternalPlayer(this@MainActivity, url)
                                            }
                                            "ask" -> {
                                                autoResumeFromContinue = false
                                                playerState.showPlayerChoiceDialog = true
                                            }
                                            else -> {
                                                autoResumeFromContinue = false
                                                activeView = "player"
                                            }
                                        }
                                    }
                                }
                            }

                            NavHost(
                                navController = detailsNavController,
                                startDestination = "detail_start",
                                modifier = Modifier.alpha(if (view == "resume") 0f else 1f),
                            ) {
                                composable("detail_start") { }
                                composable(
                                    "detail/{type}/{id}?addon={addon}&resume={resume}",
                                    arguments = listOf(
                                        navArgument("type") { type = NavType.StringType },
                                        navArgument("id") { type = NavType.StringType },
                                        navArgument("addon") { type = NavType.StringType; defaultValue = "" },
                                        navArgument("resume") { type = NavType.StringType; defaultValue = "" }
                                    )
                                ) { backStackEntry ->
                                    val detailType = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("type") ?: "movie", "UTF-8")
                                    val detailId = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("id") ?: "", "UTF-8")
                                    val detailAddon = backStackEntry.arguments?.getString("addon")?.takeIf { it.isNotEmpty() }
                                    val detailResume = backStackEntry.arguments?.getString("resume")?.takeIf { it.isNotEmpty() }

                                    DetailsScreen(
                                        type = detailType,
                                        id = detailId,
                                        addonBaseUrl = detailAddon,
                                        resumePlaybackHint = detailResume,
                                        autoStartPlayback = autoResumeFromContinue,
                                        onAutoResumeNeedsSelection = {
                                            autoResumeFromContinue = false
                                            activeView = "details"
                                        },
                                        autoSelectSource = currentProfile?.autoSelectSource ?: false,
                                        rememberSourceSelection = currentProfile?.rememberSourceSelection ?: true,
                                        onPosterResolved = { selectedMoviePoster = it },
                                        onPlayClick = onPlayClick,
                                        onNavigateToDetails = { navType, navId ->
                                            autoResumeFromContinue = false
                                            val route = "detail/${java.net.URLEncoder.encode(navType, "UTF-8")}/${java.net.URLEncoder.encode(navId, "UTF-8")}"
                                            detailsNavController.navigate(route)
                                        },
                                        onNavigateToCastDetail = { castPersonId, castPersonName ->
                                            val route = "cast_detail/$castPersonId/${java.net.URLEncoder.encode(castPersonName, "UTF-8")}"
                                            detailsNavController.navigate(route)
                                        },
                                        onNavigateToStudioDetail = { entityId, entityKind, entityName, sourceType ->
                                            val route = "studio_detail/$entityId/$entityKind/${java.net.URLEncoder.encode(entityName, "UTF-8")}/$sourceType"
                                            detailsNavController.navigate(route)
                                        },
                                        trailerReturnToken = trailerReturnToken,
                                        isTrailerLoading = false,
                                        onTrailerClick = { youtubeKey, trailerName ->
                                            val trailerIntent = YouTubeTrailerActivity.createIntent(
                                                this@MainActivity,
                                                youtubeKey,
                                                trailerName
                                            )
                                            if (trailerIntent != null) {
                                                startActivity(trailerIntent)
                                            } else {
                                                showTrailerError = true
                                            }
                                        }
                                    )
                                }
                                composable(
                                    "cast_detail/{personId}/{personName}",
                                    arguments = listOf(
                                        navArgument("personId") { type = NavType.StringType },
                                        navArgument("personName") { type = NavType.StringType }
                                    )
                                ) { backStackEntry ->
                                    val castPersonId = (backStackEntry.arguments?.getString("personId") ?: "0").toIntOrNull() ?: 0
                                    val castPersonName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("personName") ?: "", "UTF-8")

                                    com.saab.tv.ui.cast.CastDetailScreen(
                                        personId = castPersonId,
                                        personName = castPersonName,
                                        onBackPress = { detailsNavController.popBackStack() },
                                        onNavigateToDetails = { navType, navId ->
                                            val route = "detail/${java.net.URLEncoder.encode(navType, "UTF-8")}/${java.net.URLEncoder.encode(navId, "UTF-8")}"
                                            detailsNavController.navigate(route)
                                        }
                                    )
                                }
                                composable(
                                    "studio_detail/{entityId}/{entityKind}/{entityName}/{sourceType}",
                                    arguments = listOf(
                                        navArgument("entityId") { type = NavType.StringType },
                                        navArgument("entityKind") { type = NavType.StringType },
                                        navArgument("entityName") { type = NavType.StringType },
                                        navArgument("sourceType") { type = NavType.StringType }
                                    )
                                ) { backStackEntry ->
                                    val studioEntityId = (backStackEntry.arguments?.getString("entityId") ?: "0").toIntOrNull() ?: 0
                                    val studioEntityKind = backStackEntry.arguments?.getString("entityKind") ?: "company"
                                    val studioEntityName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("entityName") ?: "", "UTF-8")
                                    val studioSourceType = backStackEntry.arguments?.getString("sourceType") ?: "movie"

                                    com.saab.tv.ui.studio.StudioDetailScreen(
                                        entityId = studioEntityId,
                                        entityKind = studioEntityKind,
                                        entityName = studioEntityName,
                                        sourceType = studioSourceType,
                                        onBackPress = { detailsNavController.popBackStack() },
                                        onNavigateToDetails = { navType, navId ->
                                            val route = "detail/${java.net.URLEncoder.encode(navType, "UTF-8")}/${java.net.URLEncoder.encode(navId, "UTF-8")}"
                                            detailsNavController.navigate(route)
                                        }
                                    )
                                }
                            }
                            if (view == "resume") {
                                Box(Modifier.fillMaxSize().background(Color.Black)) {
                                    val artwork = selectedMovieBackground.ifBlank { selectedMoviePoster }
                                    if (artwork.isNotBlank()) AsyncImage(
                                        model = artwork,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().alpha(0.28f)
                                    )
                                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                        CircularProgressIndicator(Modifier.size(42.dp))
                                        Text("Opening ${selectedMovieTitle}", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }
                        if (view == "player") {
                            if (selectedVideoUrl.isBlank() && torrentProgress == null) {
                                LaunchedEffect(Unit) { activeView = "details" }
                            } else {
                            val rememberedTrackSelection = remember(selectedPlaybackId) {
                                playbackTrackSelectionStore.getSelection(selectedPlaybackId)
                            }
                            val playerSources = remember(playerState.selectedPlayerSources) { playerState.selectedPlayerSources }
                            val playerSubtitles = remember(playerState.selectedPlayerSubtitles) {
                                playerState.selectedPlayerSubtitles.map { subtitle ->
                                    PlayerSubtitleSource(
                                        id = subtitle.id,
                                        url = subtitle.url,
                                        label = subtitle.name,
                                        language = subtitle.language,
                                        sourcePriority = subtitle.sourcePriority
                                    )
                                }
                            }

                            // Compute next episode
                            val isSeries = selectedPlaybackType.equals("series", ignoreCase = true)
                            val nextEpisode = remember(selectedPlaybackId, selectedMovieId, playerState.currentEpisodeList, isSeries) {
                                if (isSeries && playerState.currentEpisodeList.isNotEmpty()) {
                                    findNextEpisode(selectedMovieId, selectedPlaybackId, playerState.currentEpisodeList)
                                } else null
                            }
                            val nextEpisodeInfo = remember(nextEpisode) {
                                nextEpisode?.let { ep ->
                                    NextEpisodeInfo(
                                        title = episodeDisplayTitle(ep),
                                        thumbnail = ep.thumbnail,
                                        seasonNumber = ep.season,
                                        episodeNumber = ep.episode
                                    )
                                }
                            }

                            // Fetch the next episode while the current one is playing. This removes
                            // addon and subtitle network latency from autoplay/Next Episode.
                            LaunchedEffect(selectedMovieId, selectedPlaybackId, nextEpisode?.id) {
                                val episode = nextEpisode ?: run {
                                    playerState.prefetchedEpisodeData = null
                                    return@LaunchedEffect
                                }
                                val streamId = episodeStreamId(selectedMovieId, episode)
                                if (playerState.prefetchedEpisodeData?.streamId == streamId) {
                                    return@LaunchedEffect
                                }
                                playerState.prefetchedEpisodeData = null
                                val streamsDeferred = async {
                                    try { addonRepository.getStreams("series", streamId) }
                                    catch (_: Exception) { emptyList() }
                                }
                                val subtitlesDeferred = async {
                                    try { subtitleRepository.getSubtitles("series", streamId) }
                                    catch (_: Exception) { emptyList() }
                                }
                                val prefetchedStreams = streamsDeferred.await()
                                val prefetched = PrefetchedEpisodeData(
                                    streamId = streamId,
                                    streams = prefetchedStreams,
                                    addonSubs = subtitlesDeferred.await()
                                )
                                // Do not cache a transient addon failure; the switch path
                                // will retry normally if prefetch returned no sources.
                                if (prefetchedStreams.isNotEmpty() &&
                                    episodeStreamId(selectedMovieId, episode) == streamId
                                ) {
                                    playerState.prefetchedEpisodeData = prefetched
                                }
                            }

                            // Fetch skip intro/outro segments from IntroDB
                            val skipIntroEnabled = currentProfile?.skipIntro == true
                            val autoplayEnabled = currentProfile?.autoplayNextEpisode == true
                            val needIntroDB = isSeries && (
                                skipIntroEnabled || (autoplayEnabled && nextEpisode != null)
                            )
                            var skipSegmentInfo by remember { mutableStateOf<SkipSegmentInfo?>(null) }
                            LaunchedEffect(selectedPlaybackId, needIntroDB, skipIntroEnabled) {
                                skipSegmentInfo = null
                                if (!needIntroDB) return@LaunchedEffect
                                if (!isSeries || selectedPlaybackId.isBlank()) return@LaunchedEffect
                                val parts = selectedPlaybackId.split(":")
                                if (parts.size < 3) return@LaunchedEffect
                                val imdbId = parts.dropLast(2).joinToString(":")
                                val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return@LaunchedEffect
                                val episode = parts.last().toIntOrNull() ?: return@LaunchedEffect
                                val response = introRepository.getSegments(imdbId, season, episode)
                                if (response != null) {
                                    skipSegmentInfo = SkipSegmentInfo(
                                        introStartMs = if (skipIntroEnabled) response.intro?.start_ms else null,
                                        introEndMs = if (skipIntroEnabled) response.intro?.end_ms else null,
                                        outroStartMs = response.outro?.start_ms,
                                        outroEndMs = response.outro?.end_ms
                                    )
                                }
                            }

                            PlayerScreen(
                                videoUrl = selectedVideoUrl,
                                trailerAudioUrl = selectedTrailerAudioUrl.takeIf { it.isNotBlank() },
                                trailerVariants = selectedTrailerVariants,
                                title = selectedPlaybackTitle.ifBlank { selectedMovieTitle },
                                seriesTitle = selectedMovieTitle.takeIf {
                                    selectedPlaybackType.equals("series", ignoreCase = true)
                                },
                                logoUrl = selectedMovieLogo.takeIf { it.isNotBlank() },
                                poster = selectedPlaybackPoster,
                                movieId = selectedPlaybackId,
                                mediaType = selectedPlaybackType,
                                sources = playerSources,
                                initialSourceId = playerState.currentStream?.let { current ->
                                    resolvePlayableSourceUrl(current)?.let { url ->
                                        com.saab.tv.ui.player.base.sourceOptionId(url, current.fileIdx ?: -1,
                                            current.behaviorHints?.filename.orEmpty())
                                    }
                                },
                                onActiveSourceChanged = { source ->
                                    val chosen = playerState.pendingSourceSelection?.candidateStreams
                                        ?.firstOrNull { candidate ->
                                            resolvePlayableSourceUrl(candidate)?.let { url ->
                                                com.saab.tv.ui.player.base.sourceOptionId(url, candidate.fileIdx ?: -1,
                                                    candidate.behaviorHints?.filename.orEmpty()) == source.id
                                            } == true
                                        }
                                    if (chosen != null) {
                                        playerState.currentStream = chosen
                                        playerState.pendingSourceSelection = playerState.pendingSourceSelection
                                            ?.copy(launchedStream = chosen)
                                        if (currentProfile?.rememberSourceSelection != false) {
                                            sourceSelectionStore.rememberSelection(selectedPlaybackId, chosen)
                                        }
                                    }
                                },
                                subtitles = playerSubtitles,
                                preferredAudioTrackId = rememberedTrackSelection?.audioTrackId,
                                preferredSubtitleTrackId = rememberedTrackSelection?.subtitleTrackId,
                                initialSubtitleDelayMs = rememberedTrackSelection?.subtitleDelayMs ?: 0L,
                                playbackSettings = PlaybackSettings(
                                    profileId = currentProfile?.id ?: 0,
                                    tunnelingEnabled = currentProfile?.tunnelingEnabled ?: false,
                                    mapDV7ToHevc = currentProfile?.mapDV7ToHevc ?: false,
                                    decoderPriority = currentProfile?.decoderPriority ?: 1,
                                    frameRateMatching = currentProfile?.frameRateMatching ?: false,
                                    seekTimeIntervalSeconds = currentProfile?.seekTimeIntervalSeconds ?: 10,
                                    seekThumbnailsEnabled = currentProfile?.seekThumbnailsEnabled ?: true,
                                    seekThumbnailIntervalSeconds = currentProfile?.seekTimeIntervalSeconds ?: 10,
                                    autoplayNextEpisode = currentProfile?.autoplayNextEpisode ?: false,
                                    autoSkipIntro = currentProfile?.autoSkipIntro ?: true,
                                    introSkipCountdownSeconds = currentProfile?.autoSkipCountdownSeconds ?: 5,
                                    outroSkipCountdownSeconds = currentProfile?.autoSkipCountdownSeconds ?: 5,
                                    autoSelectSource = currentProfile?.autoSelectSource ?: false,
                                    autoplayThresholdMode = currentProfile?.autoplayThresholdMode ?: "percentage",
                                    autoplayThresholdPercent = currentProfile?.autoplayThresholdPercent ?: 95,
                                    autoplayThresholdSeconds = currentProfile?.autoplayThresholdSeconds ?: 30,
                                    watchedThresholdPercent = currentProfile?.watchedThreshold ?: 95,
                                    preferredAudioLanguage = currentProfile?.preferredAudioLanguage ?: "",
                                    preferredAudioLanguageSecondary = currentProfile?.preferredAudioLanguageSecondary ?: "",
                                    preferredSubtitleLanguage = currentProfile?.preferredSubtitleLanguage ?: "",
                                    preferredSubtitleLanguageSecondary = currentProfile?.preferredSubtitleLanguageSecondary ?: "",
                                    subtitleSize = currentProfile?.subtitleSize ?: 100,
                                    subtitleOffset = currentProfile?.subtitleOffset ?: 0,
                                    subtitleTextColor = currentProfile?.subtitleTextColor?.toInt() ?: 0xFFFFFFFF.toInt(),
                                    subtitleBackgroundColor = currentProfile?.subtitleBackgroundColor?.toInt() ?: 0x00000000,
                                    assRendererEnabled = currentProfile?.assRendererEnabled ?: false
                                ),
                                skipSegmentInfo = skipSegmentInfo,
                                nextEpisodeInfo = if (nextEpisode != null) nextEpisodeInfo else null,
                                onAutoplayNextEpisode = if (nextEpisode != null) {
                                    { playerCurrentSourceUrl, currentPositionMs, currentDurationMs ->
                                        handlePlayerSessionEnd(
                                            sessionResult = PlayerSessionResult(
                                                positionMs = currentPositionMs,
                                                durationMs = currentDurationMs,
                                                isCompleted = isPlaybackSnapshotCompleted(
                                                    currentPositionMs,
                                                    currentDurationMs,
                                                    currentProfile?.watchedThreshold ?: 95
                                                ),
                                                selectedSourceUrl = playerCurrentSourceUrl ?: selectedVideoUrl,
                                                selectedAudioTrackId = null,
                                                selectedSubtitleTrackId = null
                                            ),
                                            selectedPlaybackId = selectedPlaybackId,
                                            playbackTrackSelectionStore = playbackTrackSelectionStore,
                                            sourceSelectionStore = sourceSelectionStore,
                                            pendingSourceSelection = playerState.pendingSourceSelection,
                                            onConsumePendingSelection = { playerState.pendingSourceSelection = null },
                                            onResumeHintResolved = { detailsResumePlaybackHint = it },
                                            rememberSourceSelection = currentProfile?.rememberSourceSelection ?: true
                                        )

                                        val nextPlaybackId = episodePlaybackId(selectedMovieId, nextEpisode)
                                        val nextStreamId = episodeStreamId(selectedMovieId, nextEpisode)
                                        val nextPlaybackTitle = episodeDisplayTitle(nextEpisode)
                                        val switchRequestId = playerState.beginEpisodeSwitch()

                                        uiScope.launch {
                                            // Show loading feedback immediately
                                            val autoplay = currentProfile?.autoplayNextEpisode == true
                                            val autoSelect = currentProfile?.autoSelectSource == true
                                            val rememberSource = currentProfile?.rememberSourceSelection ?: true
                                            val hasRememberedSource = rememberSource &&
                                                sourceSelectionStore.hasRememberedSelection(nextPlaybackId)
                                            val currentContinuityStream = playerState.currentStream
                                                ?: playerState.pendingSourceSelection?.launchedStream
                                            val hasTorrentContinuity = currentContinuityStream != null &&
                                                EpisodeStreamContinuity.torrentIdentity(currentContinuityStream) != null
                                            val willAutoResolve = autoplay || autoSelect || hasRememberedSource || hasTorrentContinuity

                                            if (willAutoResolve) {
                                                playerState.isEpisodeSwitchLoading = true
                                            } else {
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = nextPlaybackId,
                                                    playbackTitle = nextPlaybackTitle,
                                                    streams = null,
                                                    addonSubs = emptyList(),
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                            }

                                            val prefetched = playerState.prefetchedEpisodeData
                                                ?.takeIf { it.streamId == nextStreamId }
                                            val rawStreams: List<Stream>
                                            val addonSubs: List<AddonSubtitle>
                                            if (prefetched != null) {
                                                rawStreams = prefetched.streams
                                                addonSubs = prefetched.addonSubs
                                            } else {
                                                val streamsDeferred = async { try { addonRepository.getStreams("series", nextStreamId) } catch (_: Exception) { emptyList() } }
                                                val subtitlesDeferred = async { try { subtitleRepository.getSubtitles("series", nextStreamId) } catch (_: Exception) { emptyList() } }
                                                rawStreams = streamsDeferred.await()
                                                addonSubs = subtitlesDeferred.await()
                                            }
                                            val episodeStreams = if (rawStreams.isEmpty() && nextStreamId != nextPlaybackId) {
                                                addonRepository.getStreams("series", nextPlaybackId)
                                            } else rawStreams
                                            if (switchRequestId != playerState.episodeSwitchRequestId || activeView != "player") {
                                                return@launch
                                            }

                                            val filteredStreams = if (currentProfile?.sourceSortingEnabled == true) {
                                                val enabledQ = StreamSortingService.parseEnabledQualities(currentProfile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown")
                                                val excludeP = StreamSortingService.parseExcludePhrases(currentProfile?.sourceExcludePhrases ?: "")
                                                val addonOrders = addonRepository.getAddonSortOrders()
                                                val excludedF = StreamSortingService.parseExcludedFormats(currentProfile?.sourceExcludedFormats ?: "")
                                                streamSortingService.sortAndFilter(
                                                    streams = episodeStreams,
                                                    enabledQualities = enabledQ,
                                                    excludePhrases = excludeP,
                                                    addonSortOrders = addonOrders,
                                                    sortBy = currentProfile?.sourceSortPrimary ?: "quality",
                                                    maxSizeGb = currentProfile?.sourceMaxSizeGb ?: 0,
                                                    excludedFormats = excludedF,
                                                    seasonPackTorrentsOnly = currentProfile?.sourceSeasonPacksOnly == true,
                                                    hideZeroSeeders = currentProfile?.sourceHideZeroSeeders == true,
                                                    preferredAudioLanguages = StreamSortingService.smartLanguagePreferences(
                                                        currentProfile?.sourceLanguagePriority1,
                                                        currentProfile?.sourceLanguagePriority2,
                                                        currentProfile?.sourceLanguagePriority3
                                                    )
                                                )
                                            } else episodeStreams.filter { stream ->
                                                currentProfile?.sourceHideZeroSeeders != true || stream.torBoxCached == true ||
                                                    com.saab.tv.data.stream.StreamParser.parse(stream).seeds != 0
                                            }
                                            val streams = StreamScoreCalculator.sortDescending(
                                                filteredStreams,
                                                StreamSortingService.smartLanguagePreferences(
                                                    currentProfile?.sourceLanguagePriority1,
                                                    currentProfile?.sourceLanguagePriority2,
                                                    currentProfile?.sourceLanguagePriority3
                                                )
                                            )

                                            if (streams.isEmpty()) {
                                                playerState.isEpisodeSwitchLoading = false
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = nextPlaybackId,
                                                    playbackTitle = nextPlaybackTitle,
                                                    streams = emptyList(),
                                                    addonSubs = emptyList(),
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                                return@launch
                                            }

                                            // Resolve the actual stream the user was watching (may differ from initial if they switched sources)
                                            val actualStream = if (playerCurrentSourceUrl != null) {
                                                playerState.pendingSourceSelection?.candidateStreams?.firstOrNull { candidate ->
                                                    resolvePlayableSourceUrl(candidate) == playerCurrentSourceUrl
                                                } ?: playerState.currentStream
                                            } else playerState.currentStream

                                            // Keep the exact torrent when its next-episode file is available.
                                            val continuityMatch = EpisodeStreamContinuity.findContinuation(actualStream, streams)
                                            // Priority 2: Remembered source
                                            val preferred = if (rememberSource) sourceSelectionStore.findPreferredStream(nextPlaybackId, streams) else null
                                            // Priority 3: First playable (only when autoSelectSource is on)
                                            val streamToPlay = continuityMatch
                                                ?: preferred
                                                ?: if (autoSelect) streams.firstOrNull { !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank() } else null

                                            if (streamToPlay == null) {
                                                playerState.isEpisodeSwitchLoading = false
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = nextPlaybackId,
                                                    playbackTitle = nextPlaybackTitle,
                                                    streams = streams,
                                                    addonSubs = addonSubs,
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                                return@launch
                                            }

                                            val nextUrl = resolvePlayableSourceUrl(streamToPlay)
                                            if (nextUrl == null) {
                                                playerState.isEpisodeSwitchLoading = false
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = nextPlaybackId,
                                                    playbackTitle = nextPlaybackTitle,
                                                    streams = streams,
                                                    addonSubs = addonSubs,
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                                return@launch
                                            }

                                            // Auto-resolved: clear loading + switch
                                            playerState.isEpisodeSwitchLoading = false
                                            playerState.pendingEpisodeSwitch = null

                                            val subtitlePayload = buildSubtitlePayload(streamToPlay, addonSubs)
                                            val sourcePayload = buildSourcePayload(streams, nextPlaybackTitle)

                                            playerState.pendingSourceSelection = PendingSourceSelection(
                                                playbackId = nextPlaybackId,
                                                launchedStream = streamToPlay,
                                                candidateStreams = streams
                                            )
                                            playerState.currentStream = streamToPlay

                                            if (nextUrl.startsWith("magnet:")) {
                                                selectedPlaybackId = nextPlaybackId
                                                selectedPlaybackType = "series"
                                                selectedPlaybackTitle = nextPlaybackTitle
                                                selectedVideoUrl = ""
                                                startTorrentWithFallback(
                                                    selectedStream = streamToPlay,
                                                    rankedStreams = streams,
                                                    onAttempt = { attemptedStream, retryNumber ->
                                                        playerState.currentStream = attemptedStream
                                                        playerState.pendingSourceSelection = PendingSourceSelection(
                                                            nextPlaybackId,
                                                            attemptedStream,
                                                            streams
                                                        )
                                                        playerState.selectedPlayerSubtitles = buildSubtitlePayload(
                                                            attemptedStream,
                                                            addonSubs
                                                        )
                                                        playerState.selectedPlayerSources = buildSourcePayload(
                                                            streams,
                                                            nextPlaybackTitle
                                                        )
                                                        torrentProgress = TorrentProgress(
                                                            if (retryNumber == 0) "Connecting to peers..."
                                                            else "Trying backup torrent $retryNumber of 2..."
                                                        )
                                                    },
                                                    onProgress = { torrentProgress = it },
                                                    onReady = { _, localUrl ->
                                                        torrentProgress = null
                                                        selectedVideoUrl = localUrl
                                                    },
                                                    onExhausted = { error ->
                                                        torrentProgress = null
                                                        selectedVideoUrl = ""
                                                        if (BuildConfig.DEBUG) Log.e("SaabTvTorrent", "Stream error: $error")
                                                    }
                                                )
                                            } else {
                                                stopService(Intent(this@MainActivity, TorrentService::class.java))
                                                selectedPlaybackId = nextPlaybackId
                                                selectedPlaybackType = "series"
                                                selectedPlaybackTitle = nextPlaybackTitle
                                                playerState.selectedPlayerSubtitles = subtitlePayload
                                                playerState.selectedPlayerSources = sourcePayload
                                                selectedVideoUrl = nextUrl
                                                // PlayerScreen will recompose due to movieId/videoUrl key change
                                            }
                                        }
                                    }
                                } else null,
                                episodes = playerState.currentEpisodeList,
                                currentPlaybackId = selectedPlaybackId,
                                onEpisodeSelected = if (playerState.currentEpisodeList.isNotEmpty()) {
                                    { episode, playerCurrentSourceUrl, currentPositionMs, currentDurationMs ->
                                        val epPlaybackId = episodePlaybackId(selectedMovieId, episode)
                                        val epStreamId = episodeStreamId(selectedMovieId, episode)
                                        val epTitle = episodeDisplayTitle(episode)
                                        AppDiagnostics.event("Episodes", "Player Selection",
                                            "season=${episode.season} episode=${episode.episode} nativeIdMatches=${epStreamId == epPlaybackId}")
                                        val switchRequestId = playerState.beginEpisodeSwitch()

                                        uiScope.launch {
                                            // Show loading feedback immediately
                                            val autoplay = currentProfile?.autoplayNextEpisode == true
                                            val autoSelect = currentProfile?.autoSelectSource == true
                                            val rememberSource = currentProfile?.rememberSourceSelection ?: true
                                            val hasRememberedSource = rememberSource &&
                                                sourceSelectionStore.hasRememberedSelection(epPlaybackId)
                                            val currentContinuityStream = playerState.currentStream
                                                ?: playerState.pendingSourceSelection?.launchedStream
                                            val hasTorrentContinuity = currentContinuityStream != null &&
                                                EpisodeStreamContinuity.torrentIdentity(currentContinuityStream) != null
                                            val willAutoResolve = autoplay || autoSelect || hasRememberedSource || hasTorrentContinuity

                                            if (willAutoResolve) {
                                                playerState.isEpisodeSwitchLoading = true
                                            } else {
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = epPlaybackId,
                                                    playbackTitle = epTitle,
                                                    streams = null,
                                                    addonSubs = emptyList(),
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                            }

                                            val prefetched = playerState.prefetchedEpisodeData
                                                ?.takeIf { it.streamId == epStreamId }
                                            val rawStreams2: List<Stream>
                                            val addonSubs: List<AddonSubtitle>
                                            if (prefetched != null) {
                                                rawStreams2 = prefetched.streams
                                                addonSubs = prefetched.addonSubs
                                            } else {
                                                val streamsDeferred = async { try { addonRepository.getStreams("series", epStreamId) } catch (_: Exception) { emptyList() } }
                                                val subtitlesDeferred = async { try { subtitleRepository.getSubtitles("series", epStreamId) } catch (_: Exception) { emptyList() } }
                                                rawStreams2 = streamsDeferred.await()
                                                addonSubs = subtitlesDeferred.await()
                                            }
                                            val episodeStreams = if (rawStreams2.isEmpty() && epStreamId != epPlaybackId) {
                                                addonRepository.getStreams("series", epPlaybackId)
                                            } else rawStreams2
                                            if (switchRequestId != playerState.episodeSwitchRequestId || activeView != "player") {
                                                return@launch
                                            }

                                            val filteredStreams = if (currentProfile?.sourceSortingEnabled == true) {
                                                val enabledQ = StreamSortingService.parseEnabledQualities(currentProfile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown")
                                                val excludeP = StreamSortingService.parseExcludePhrases(currentProfile?.sourceExcludePhrases ?: "")
                                                val addonOrders = addonRepository.getAddonSortOrders()
                                                val excludedF = StreamSortingService.parseExcludedFormats(currentProfile?.sourceExcludedFormats ?: "")
                                                streamSortingService.sortAndFilter(
                                                    streams = episodeStreams,
                                                    enabledQualities = enabledQ,
                                                    excludePhrases = excludeP,
                                                    addonSortOrders = addonOrders,
                                                    sortBy = currentProfile?.sourceSortPrimary ?: "quality",
                                                    maxSizeGb = currentProfile?.sourceMaxSizeGb ?: 0,
                                                    excludedFormats = excludedF,
                                                    seasonPackTorrentsOnly = currentProfile?.sourceSeasonPacksOnly == true,
                                                    hideZeroSeeders = currentProfile?.sourceHideZeroSeeders == true,
                                                    preferredAudioLanguages = StreamSortingService.smartLanguagePreferences(
                                                        currentProfile?.sourceLanguagePriority1,
                                                        currentProfile?.sourceLanguagePriority2,
                                                        currentProfile?.sourceLanguagePriority3
                                                    )
                                                )
                                            } else episodeStreams.filter { stream ->
                                                currentProfile?.sourceHideZeroSeeders != true || stream.torBoxCached == true ||
                                                    com.saab.tv.data.stream.StreamParser.parse(stream).seeds != 0
                                            }
                                            val streams = StreamScoreCalculator.sortDescending(
                                                filteredStreams,
                                                StreamSortingService.smartLanguagePreferences(
                                                    currentProfile?.sourceLanguagePriority1,
                                                    currentProfile?.sourceLanguagePriority2,
                                                    currentProfile?.sourceLanguagePriority3
                                                )
                                            )

                                            if (streams.isEmpty()) {
                                                playerState.isEpisodeSwitchLoading = false
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = epPlaybackId,
                                                    playbackTitle = epTitle,
                                                    streams = emptyList(),
                                                    addonSubs = emptyList(),
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                                return@launch
                                            }

                                            // Resolve the actual stream the user was watching
                                            val actualStream = if (playerCurrentSourceUrl != null) {
                                                playerState.pendingSourceSelection?.candidateStreams?.firstOrNull { candidate ->
                                                    resolvePlayableSourceUrl(candidate) == playerCurrentSourceUrl
                                                } ?: playerState.currentStream
                                            } else playerState.currentStream

                                            // Keep the exact torrent when its selected pack contains this episode.
                                            val continuityMatch = EpisodeStreamContinuity.findContinuation(actualStream, streams)

                                            // Priority 2: source remembered for this movie/series.
                                            val preferred = if (rememberSource) {
                                                sourceSelectionStore.findPreferredStream(epPlaybackId, streams)
                                            } else null
                                            // Priority 3: Auto-select first available (only when autoSelectSource is on)
                                            val streamToPlay = continuityMatch
                                                ?: preferred
                                                ?: if (autoSelect) streams.firstOrNull { !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank() } else null

                                            if (streamToPlay == null) {
                                                playerState.isEpisodeSwitchLoading = false
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = epPlaybackId,
                                                    playbackTitle = epTitle,
                                                    streams = streams,
                                                    addonSubs = addonSubs,
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                                return@launch
                                            }

                                            val epUrl = resolvePlayableSourceUrl(streamToPlay)
                                            if (epUrl == null) {
                                                playerState.isEpisodeSwitchLoading = false
                                                playerState.pendingEpisodeSwitch = PendingEpisodeSwitch(
                                                    playbackId = epPlaybackId,
                                                    playbackTitle = epTitle,
                                                    streams = streams,
                                                    addonSubs = addonSubs,
                                                    playerCurrentSourceUrl = playerCurrentSourceUrl,
                                                    currentPositionMs = currentPositionMs,
                                                    currentDurationMs = currentDurationMs
                                                )
                                                return@launch
                                            }

                                            // Auto-resolved: clear loading + switch
                                            playerState.isEpisodeSwitchLoading = false
                                            playerState.pendingEpisodeSwitch = null
                                            handlePlayerSessionEnd(
                                                sessionResult = PlayerSessionResult(
                                                    positionMs = currentPositionMs,
                                                    durationMs = currentDurationMs,
                                                    isCompleted = isPlaybackSnapshotCompleted(
                                                        currentPositionMs,
                                                        currentDurationMs,
                                                        currentProfile?.watchedThreshold ?: 95
                                                    ),
                                                    selectedSourceUrl = playerCurrentSourceUrl ?: selectedVideoUrl,
                                                    selectedAudioTrackId = null,
                                                    selectedSubtitleTrackId = null
                                                ),
                                                selectedPlaybackId = selectedPlaybackId,
                                                playbackTrackSelectionStore = playbackTrackSelectionStore,
                                                sourceSelectionStore = sourceSelectionStore,
                                                pendingSourceSelection = playerState.pendingSourceSelection,
                                                onConsumePendingSelection = { playerState.pendingSourceSelection = null },
                                                onResumeHintResolved = { detailsResumePlaybackHint = it },
                                                rememberSourceSelection = currentProfile?.rememberSourceSelection ?: true
                                            )

                                            val subtitlePayload = buildSubtitlePayload(streamToPlay, addonSubs)
                                            val sourcePayload = buildSourcePayload(streams, epTitle)

                                            playerState.pendingSourceSelection = PendingSourceSelection(
                                                playbackId = epPlaybackId,
                                                launchedStream = streamToPlay,
                                                candidateStreams = streams
                                            )
                                            playerState.currentStream = streamToPlay

                                            if (epUrl.startsWith("magnet:")) {
                                                selectedPlaybackId = epPlaybackId
                                                selectedPlaybackType = "series"
                                                selectedPlaybackTitle = epTitle
                                                selectedVideoUrl = ""
                                                startTorrentWithFallback(
                                                    selectedStream = streamToPlay,
                                                    rankedStreams = streams,
                                                    onAttempt = { attemptedStream, retryNumber ->
                                                        playerState.currentStream = attemptedStream
                                                        playerState.pendingSourceSelection = PendingSourceSelection(
                                                            epPlaybackId,
                                                            attemptedStream,
                                                            streams
                                                        )
                                                        playerState.selectedPlayerSubtitles = buildSubtitlePayload(
                                                            attemptedStream,
                                                            addonSubs
                                                        )
                                                        playerState.selectedPlayerSources = buildSourcePayload(
                                                            streams,
                                                            epTitle
                                                        )
                                                        torrentProgress = TorrentProgress(
                                                            if (retryNumber == 0) "Connecting to peers..."
                                                            else "Trying backup torrent $retryNumber of 2..."
                                                        )
                                                    },
                                                    onProgress = { torrentProgress = it },
                                                    onReady = { _, localUrl ->
                                                        torrentProgress = null
                                                        selectedVideoUrl = localUrl
                                                    },
                                                    onExhausted = { error ->
                                                        torrentProgress = null
                                                        selectedVideoUrl = ""
                                                        if (BuildConfig.DEBUG) Log.e("SaabTvTorrent", "Stream error: $error")
                                                    }
                                                )
                                            } else {
                                                stopService(Intent(this@MainActivity, TorrentService::class.java))
                                                selectedPlaybackId = epPlaybackId
                                                selectedPlaybackType = "series"
                                                selectedPlaybackTitle = epTitle
                                                playerState.selectedPlayerSubtitles = subtitlePayload
                                                playerState.selectedPlayerSources = sourcePayload
                                                selectedVideoUrl = epUrl
                                            }
                                        }
                                    }
                                } else null,
                                episodeSwitchSources = playerState.pendingEpisodeSwitch?.let { pending ->
                                    pending.streams?.let { buildSourcePayload(it, pending.playbackTitle) }
                                },
                                isEpisodeSwitchLoading = playerState.isEpisodeSwitchLoading,
                                episodeSwitchTitle = playerState.pendingEpisodeSwitch?.playbackTitle,
                                onEpisodeSwitchSourceSelected = playerState.pendingEpisodeSwitch?.let { pending ->
                                    { sourceId: String ->
                                        val streamToPlay = pending.streams?.firstOrNull { candidate ->
                                            resolvePlayableSourceUrl(candidate)?.let { url ->
                                                com.saab.tv.ui.player.base.sourceOptionId(url, candidate.fileIdx ?: -1,
                                                    candidate.behaviorHints?.filename.orEmpty()) == sourceId
                                            } == true
                                        }
                                        if (streamToPlay == null) {
                                            playerState.pendingEpisodeSwitch = null
                                            return@let
                                        }

                                        // Now save progress for current episode
                                        handlePlayerSessionEnd(
                                            sessionResult = PlayerSessionResult(
                                                positionMs = pending.currentPositionMs,
                                                durationMs = pending.currentDurationMs,
                                                isCompleted = isPlaybackSnapshotCompleted(
                                                    pending.currentPositionMs,
                                                    pending.currentDurationMs,
                                                    currentProfile?.watchedThreshold ?: 95
                                                ),
                                                selectedSourceUrl = pending.playerCurrentSourceUrl ?: selectedVideoUrl,
                                                selectedAudioTrackId = null,
                                                selectedSubtitleTrackId = null
                                            ),
                                            selectedPlaybackId = selectedPlaybackId,
                                            playbackTrackSelectionStore = playbackTrackSelectionStore,
                                            sourceSelectionStore = sourceSelectionStore,
                                            pendingSourceSelection = playerState.pendingSourceSelection,
                                            onConsumePendingSelection = { playerState.pendingSourceSelection = null },
                                            onResumeHintResolved = { detailsResumePlaybackHint = it },
                                            rememberSourceSelection = currentProfile?.rememberSourceSelection ?: true
                                        )

                                        val subtitlePayload = buildSubtitlePayload(streamToPlay, pending.addonSubs)
                                        val sourcePayload = buildSourcePayload(
                                            pending.streams,
                                            pending.playbackTitle
                                        )

                                        playerState.pendingSourceSelection = PendingSourceSelection(
                                            playbackId = pending.playbackId,
                                            launchedStream = streamToPlay,
                                            candidateStreams = pending.streams
                                        )
                                        playerState.currentStream = streamToPlay
                                        playerState.pendingEpisodeSwitch = null

                                        val sourceUrl = resolvePlayableSourceUrl(streamToPlay) ?: return@let
                                        if (sourceUrl.startsWith("magnet:")) {
                                            selectedPlaybackId = pending.playbackId
                                            selectedPlaybackType = "series"
                                            selectedPlaybackTitle = pending.playbackTitle
                                            selectedVideoUrl = ""
                                            startTorrentWithFallback(
                                                selectedStream = streamToPlay,
                                                rankedStreams = pending.streams,
                                                onAttempt = { attemptedStream, retryNumber ->
                                                    playerState.currentStream = attemptedStream
                                                    playerState.pendingSourceSelection = PendingSourceSelection(
                                                        pending.playbackId,
                                                        attemptedStream,
                                                        pending.streams
                                                    )
                                                    playerState.selectedPlayerSubtitles = buildSubtitlePayload(
                                                        attemptedStream,
                                                        pending.addonSubs
                                                    )
                                                    playerState.selectedPlayerSources = buildSourcePayload(
                                                        pending.streams,
                                                        pending.playbackTitle
                                                    )
                                                    torrentProgress = TorrentProgress(
                                                        if (retryNumber == 0) "Connecting to peers..."
                                                        else "Trying backup torrent $retryNumber of 2..."
                                                    )
                                                },
                                                onProgress = { torrentProgress = it },
                                                onReady = { _, localUrl ->
                                                    torrentProgress = null
                                                    selectedVideoUrl = localUrl
                                                },
                                                onExhausted = { error ->
                                                    torrentProgress = null
                                                    selectedVideoUrl = ""
                                                    if (BuildConfig.DEBUG) Log.e("SaabTvTorrent", "Stream error: $error")
                                                }
                                            )
                                        } else {
                                            stopService(Intent(this@MainActivity, TorrentService::class.java))
                                            selectedPlaybackId = pending.playbackId
                                            selectedPlaybackType = "series"
                                            selectedPlaybackTitle = pending.playbackTitle
                                            playerState.selectedPlayerSubtitles = subtitlePayload
                                            playerState.selectedPlayerSources = sourcePayload
                                            selectedVideoUrl = sourceUrl
                                        }
                                    }
                                },
                                onEpisodeSwitchDismissed = { playerState.cancelEpisodeSwitch() },
                                onMagnetSourceSelected = { magnetUrl, sourceFileIdx, sourceFileName, onReady, onError ->
                                    val rankedCandidates = playerState.pendingSourceSelection
                                        ?.candidateStreams.orEmpty()
                                    val selectedCandidate = findTorrentSwitchSource(
                                        rankedCandidates, magnetUrl, sourceFileIdx, sourceFileName
                                    ) ?: Stream(
                                        url = magnetUrl,
                                        fileIdx = sourceFileIdx,
                                        behaviorHints = com.saab.tv.data.model.stremio.StreamBehaviorHints(
                                            filename = sourceFileName
                                        )
                                    )
                                    startTorrentWithFallback(
                                        selectedStream = selectedCandidate,
                                        rankedStreams = rankedCandidates,
                                        onAttempt = { attemptedStream, retryNumber ->
                                            playerState.currentStream = attemptedStream
                                            playerState.pendingSourceSelection = PendingSourceSelection(
                                                playbackId = selectedPlaybackId,
                                                launchedStream = attemptedStream,
                                                candidateStreams = rankedCandidates.ifEmpty { listOf(attemptedStream) }
                                            )
                                            torrentProgress = TorrentProgress(
                                                if (retryNumber == 0) "Connecting to peers..."
                                                else "Trying backup torrent $retryNumber of 2..."
                                            )
                                        },
                                        onProgress = { progress -> torrentProgress = progress },
                                        onReady = { resolvedStream, localUrl ->
                                            torrentProgress = null
                                            onReady(resolvePlayableSourceUrl(resolvedStream) ?: magnetUrl, localUrl)
                                        },
                                        onExhausted = { error ->
                                            torrentProgress = null
                                            onError(error)
                                            if (BuildConfig.DEBUG) {
                                                Log.e("SaabTvTorrent", "Source switch error: $error")
                                            }
                                        }
                                    )
                                },
                                torrentProgress = torrentProgress,
                                onBack = { sessionResult ->
                                    torrentProgress = null
                                    playerState.cancelEpisodeSwitch()
                                    handlePlayerSessionEnd(
                                        sessionResult = sessionResult,
                                        selectedPlaybackId = selectedPlaybackId,
                                        playbackTrackSelectionStore = playbackTrackSelectionStore,
                                        sourceSelectionStore = sourceSelectionStore,
                                        pendingSourceSelection = playerState.pendingSourceSelection,
                                        onConsumePendingSelection = { playerState.pendingSourceSelection = null },
                                        onResumeHintResolved = { detailsResumePlaybackHint = it },
                                        rememberSourceSelection = currentProfile?.rememberSourceSelection ?: true
                                    )
                                    stopService(Intent(this@MainActivity, TorrentService::class.java))
                                    if (selectedPlaybackId.startsWith("trailer_")) {
                                        trailerReturnToken++
                                    }
                                    activeView = "details"
                                }
                            )
                            }
                        }
                    // ViewSwitcher end
                    }

                    // Player choice dialog (shown when playerPreference == "ask")
                    if (playerState.showPlayerChoiceDialog && selectedVideoUrl.isNotBlank()) {
                        PlayerChoiceDialog(
                            onInternal = {
                                playerState.showPlayerChoiceDialog = false
                                activeView = "player"
                            },
                            onExternal = {
                                playerState.showPlayerChoiceDialog = false
                                launchExternalPlayer(this@MainActivity, selectedVideoUrl)
                            },
                            onDismiss = {
                                playerState.showPlayerChoiceDialog = false
                            }
                        )
                    }

                    if (showTrailerError) {
                        Dialog(onDismissRequest = { showTrailerError = false }) {
                            Box(
                                modifier = Modifier
                                    .width(380.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                                    .padding(24.dp)
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Trailer Unavailable",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(24.dp))
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        VoidButton(
                                            text = "Dismiss",
                                            onClick = { showTrailerError = false },
                                            isPrimary = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                }
                if (showExitConfirmation) {
                    ExitConfirmationDialog(
                        onConfirm = { finishAffinity() },
                        onDismiss = { showExitConfirmation = false }
                    )
                }
                }
            }
        }

        // Attach native splash overlay on top of Compose content — renders immediately
        if (showSplash) {
            attachSplashOverlay()
        }
    }

    companion object {
        private const val SPLASH_MIN_DURATION_MS = 1_650L
        private const val SPLASH_FAILSAFE_DURATION_MS = 8_000L
        private const val KEY_SPLASH_SHOWN = "splash_shown"
    }
}
