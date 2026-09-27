package com.saab.tv.ui.details

internal fun seriesResumeActionLabel(season: Int?, episode: Int?, nextEpisode: Boolean): String =
    if (season != null && episode != null) {
        if (nextEpisode) "Play Next S$season E$episode" else "Resume S$season E$episode"
    } else if (nextEpisode) "Play Next Episode" else "Resume"
