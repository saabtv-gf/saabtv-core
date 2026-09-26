package com.saab.tv.domain

import com.saab.tv.data.model.stremio.MetaVideo

/**
 * Tracking ID for watch history. Always uses seriesId:season:episode format
 * so that LIKE prefix queries ("seriesId:%") reliably find episode progress
 * regardless of what format the addon uses for episode.id.
 */
fun episodePlaybackId(seriesId: String, episode: MetaVideo): String {
    return "$seriesId:${episode.season}:${episode.episode}"
}

/**
 * Stream fetch ID. Uses the addon's original episode.id for stream endpoint
 * compatibility, falling back to the constructed format if episode.id is empty.
 */
fun episodeStreamId(seriesId: String, episode: MetaVideo): String {
    return episode.id.ifBlank { "$seriesId:${episode.season}:${episode.episode}" }
}

fun episodeDisplayTitle(episode: MetaVideo): String {
    val season = episode.season.takeIf { it > 0 } ?: 1
    val number = episode.episode.takeIf { it > 0 } ?: 1
    return "S${season}:E${number} - ${episode.title}"
}

/** Returns only regular episodes, de-duplicated and in playback order. */
fun normalizeEpisodeList(episodes: List<MetaVideo>): List<MetaVideo> {
    return episodes
        .asSequence()
        .filter { it.season > 0 && it.episode > 0 }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
        .toList()
}

/** Matches both canonical series:season:episode IDs and addon-native episode IDs. */
fun episodeMatchesPlaybackId(
    seriesId: String?,
    playbackId: String?,
    episode: MetaVideo
): Boolean {
    val currentId = playbackId?.trim().orEmpty()
    if (currentId.isEmpty()) return false
    if (episode.id.isNotBlank() && episode.id == currentId) return true
    if (!seriesId.isNullOrBlank() && episodePlaybackId(seriesId, episode) == currentId) return true

    val parts = currentId.split(":")
    if (parts.size < 3) return false
    val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return false
    val number = parts.last().toIntOrNull() ?: return false
    return episode.season == season && episode.episode == number
}

/** Removes a trailing season/episode suffix while preserving IDs that contain colons. */
fun seriesIdFromPlaybackId(playbackId: String): String {
    val trimmed = playbackId.trim()
    val parts = trimmed.split(":")
    if (parts.size < 3) return trimmed
    val hasEpisodeSuffix = parts[parts.lastIndex - 1].toIntOrNull() != null &&
        parts.last().toIntOrNull() != null
    return if (hasEpisodeSuffix) parts.dropLast(2).joinToString(":") else trimmed
}

fun findNextEpisode(
    seriesId: String,
    currentPlaybackId: String,
    episodes: List<MetaVideo>
): MetaVideo? {
    if (episodes.isEmpty()) return null
    // Only consider regular episodes (season > 0 and episode > 0)
    val regular = normalizeEpisodeList(episodes)
    if (regular.isEmpty()) return null
    val currentIndex = regular.indexOfFirst {
        episodeMatchesPlaybackId(seriesId, currentPlaybackId, it)
    }
    if (currentIndex < 0 || currentIndex >= regular.lastIndex) return null
    return regular[currentIndex + 1]
}
