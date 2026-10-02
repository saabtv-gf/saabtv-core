package com.saab.tv.ui.home

import com.saab.tv.data.model.WatchHistoryEntity

internal object ContinueResumePolicy {
    fun latestEpisode(entries: List<WatchHistoryEntity>): WatchHistoryEntity? {
        val episodes = entries.filter { entry ->
            val parts = entry.id.split(':')
            parts.size >= 3 && parts[parts.lastIndex - 1].toIntOrNull() != null && parts.last().toIntOrNull() != null
        }
        return (episodes.ifEmpty { entries }).maxByOrNull { it.lastWatched }
    }
    // A series card represents one episode, not whichever episode's stream is
    // retained from an earlier player session. Resolve it from fresh history.
    fun mayReuseOpenStream(mediaType: String): Boolean = mediaType != "series"
}
