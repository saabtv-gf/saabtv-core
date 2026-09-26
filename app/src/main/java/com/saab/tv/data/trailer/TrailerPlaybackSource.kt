package com.saab.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val fallbackVariants: List<TrailerPlaybackVariant> = emptyList()
)

data class TrailerPlaybackVariant(
    val videoUrl: String,
    val audioUrl: String? = null,
    val qualityLabel: String = ""
)
