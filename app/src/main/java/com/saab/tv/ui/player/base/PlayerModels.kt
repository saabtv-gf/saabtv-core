package com.saab.tv.ui.player.base

import androidx.compose.runtime.Immutable
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints

@Immutable
data class PlayerSourceOption(
    val id: String,
    val url: String,
    val label: String,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val addonTransportUrl: String? = null,
    val infoHash: String? = null,
    val videoSize: Long? = null,
    val qualityHeight: Int? = null,
    val seeders: Int? = null,
    val torBoxChecked: Boolean = false,
    val torBoxCached: Boolean? = null,
    val torBoxSeeders: Int? = null,
    val formats: List<String> = emptyList(),
    val fileIdx: Int = -1,
    val fileName: String = "",
    val subtitles: List<PlayerSubtitleSource> = emptyList()
)

/** A season pack may expose several episode files under one magnet URL. */
internal fun sourceOptionId(url: String, fileIdx: Int, fileName: String): String = when {
    fileIdx >= 0 -> "$url#file-index=$fileIdx"
    fileName.isNotBlank() -> "$url#file-name=${fileName.trim()}"
    else -> url
}

/** Preserve availability evidence when the player rebuilds its source-selection cards. */
internal fun PlayerSourceOption.toStream(): Stream = Stream(
    name = name,
    title = title ?: label,
    description = description,
    url = url,
    infoHash = infoHash,
    fileIdx = fileIdx.takeIf { it >= 0 },
    seeders = seeders,
    torBoxChecked = torBoxChecked,
    torBoxCached = torBoxCached,
    torBoxSeeders = torBoxSeeders,
    behaviorHints = StreamBehaviorHints(filename = fileName, videoSize = videoSize),
    addonTransportUrl = addonTransportUrl
)

@Immutable
data class PlayerSubtitleSource(
    val id: String,
    val url: String,
    val label: String,
    val language: String? = null,
    val sourcePriority: Int = SubtitleSourcePriority.ADDON
)

object SubtitleSourcePriority {
    const val EMBEDDED = 0
    const val STREAM_PROVIDED = 1
    const val ADDON = 2
}

data class PlayerTrackOption(
    val id: String,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
    val supported: Boolean = true,
    val isExternal: Boolean = false,
    val subtitleSourcePriority: Int = SubtitleSourcePriority.ADDON,
    val roleFlags: Int = 0,
    val selectionFlags: Int = 0,
    val subtitleFormat: String? = null,
    val audioFormat: String? = null
)

data class PlayerLoadRequest(
    val mediaUrl: String,
    val title: String,
    val initialSourceId: String? = null,
    val startPositionMs: Long = 0L,
    val autoPlay: Boolean = true,
    val sources: List<PlayerSourceOption> = emptyList(),
    val subtitles: List<PlayerSubtitleSource> = emptyList(),
    val preferredAudioTrackId: String? = null,
    val preferredSubtitleTrackId: String? = null,
    val separateAudioUrl: String? = null,
    val diagnosticsSessionId: String = ""
)

@Immutable
data class PlaybackSettings(
    val profileId: Int = 0,
    val tunnelingEnabled: Boolean = false,
    val mapDV7ToHevc: Boolean = false,
    val decoderPriority: Int = 1,
    val frameRateMatching: Boolean = false,
    val seekTimeIntervalSeconds: Int = 30,
    val seekThumbnailsEnabled: Boolean = true,
    val seekThumbnailIntervalSeconds: Int = 30,
    val autoplayNextEpisode: Boolean = true,
    val autoSkipIntro: Boolean = true,
    val introSkipCountdownSeconds: Int = 5,
    val outroSkipCountdownSeconds: Int = 5,
    val autoSelectSource: Boolean = true,
    val autoplayThresholdMode: String = "introdb",
    val autoplayThresholdPercent: Int = 95,
    val autoplayThresholdSeconds: Int = 30,
    val watchedThresholdPercent: Int = 95,
    val preferredAudioLanguage: String = "en",
    val preferredAudioLanguageSecondary: String = "",
    val preferredSubtitleLanguage: String = "en",
    val preferredSubtitleLanguageSecondary: String = "",
    val subtitleSize: Int = 100,
    val subtitleOffset: Int = 0,
    val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackgroundColor: Int = 0x00000000,
    val assRendererEnabled: Boolean = false
)

@Immutable
data class NextEpisodeInfo(
    val title: String,
    val thumbnail: String?,
    val seasonNumber: Int,
    val episodeNumber: Int
)

@Immutable
data class SkipSegmentInfo(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val outroStartMs: Long? = null,
    val outroEndMs: Long? = null
)

@Immutable
data class PlayerUiState(
    val reliabilityPhase: String = "idle",
    val playbackUnderStress: Boolean = false,
    val isReady: Boolean = false,
    val isBuffering: Boolean = false,
    val isSeeking: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val currentSourceId: String? = null,
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null,
    val subtitleSelectionWasManual: Boolean = false,
    val subtitleVerticalOffsetPercent: Int = 0,
    val subtitleSizePercent: Int = 100,
    val subtitleDelayMs: Long = 0L,
    val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackgroundColor: Int = 0x00000000,
    val isEnded: Boolean = false,
    val errorMessage: String? = null
)
