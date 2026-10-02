package com.saab.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val fallbackVariants: List<TrailerPlaybackVariant> = emptyList(),
    val qualityLabel: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val videoId: String = "",
    val formatId: Int = -1,
    val codec: String = "",
    val bitrate: Int = -1,
    val fps: Int = -1,
    val resolver: String = "",
    val width: Int = 0,
    val hardwareDecoder: String = ""
)

data class TrailerPlaybackVariant(
    val videoUrl: String,
    val audioUrl: String? = null,
    val qualityLabel: String = "",
    val height: Int = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val formatId: Int = -1,
    val codec: String = "",
    val bitrate: Int = -1,
    val fps: Int = -1,
    val width: Int = 0,
    val hardwareDecoder: String = ""
)

internal fun TrailerPlaybackVariant.diagnosticSummary(): String =
    "width=$width height=$height quality=$qualityLabel formatId=$formatId codec=$codec bitrateBps=$bitrate fps=$fps hardwareDecoder=$hardwareDecoder audioSeparate=${audioUrl != null}"
