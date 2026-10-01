package com.saab.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val fallbackVariants: List<TrailerPlaybackVariant> = emptyList(),
    val qualityLabel: String = "",
    val requestHeaders: Map<String, String> = emptyMap()
)

data class TrailerPlaybackVariant(
    val videoUrl: String,
    val audioUrl: String? = null,
    val qualityLabel: String = "",
    val height: Int = 0,
    val requestHeaders: Map<String, String> = emptyMap()
)
