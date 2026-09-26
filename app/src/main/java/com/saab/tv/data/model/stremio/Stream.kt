package com.saab.tv.data.model.stremio

data class StreamResponse(
    val streams: List<Stream>? = null
)

data class SubtitleResponse(
    val subtitles: List<StreamSubtitle> = emptyList()
)

data class Stream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    // Some addons expose availability as structured metadata instead of putting
    // it in the display title. Keeping the optional field prevents that signal
    // from being lost during JSON decoding.
    val seeders: Int? = null,
    val subtitles: List<StreamSubtitle>? = null,
    val behaviorHints: StreamBehaviorHints? = null,
    val addonTransportUrl: String? = null
)

data class StreamSubtitle(
    val id: String? = null,
    val lang: String? = null,
    val url: String? = null,
    val name: String? = null,
    val transportUrl: String? = null
)

data class StreamBehaviorHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val seeders: Int? = null
)
