package com.saab.tv.ui.details

internal enum class SeriesResumeActionKind {
    RESUME,
    RESUME_EPISODE,
    PLAY_NEXT,
    PLAY_NEXT_EPISODE
}

internal fun seriesResumeActionKind(
    season: Int?,
    episode: Int?,
    nextEpisode: Boolean
): SeriesResumeActionKind = when {
    season != null && episode != null && nextEpisode -> SeriesResumeActionKind.PLAY_NEXT_EPISODE
    season != null && episode != null -> SeriesResumeActionKind.RESUME_EPISODE
    nextEpisode -> SeriesResumeActionKind.PLAY_NEXT
    else -> SeriesResumeActionKind.RESUME
}
