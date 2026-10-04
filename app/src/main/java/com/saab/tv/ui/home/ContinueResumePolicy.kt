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

    /**
     * A Continue Watching card already identifies the exact episode the user
     * selected. Prefer that target over a second, possibly stale details-state
     * calculation (for example after cloud history refresh races navigation).
     */
    fun playbackId(
        seriesId: String,
        continueHint: String?,
        hintIsWatched: Boolean,
        calculatedResumeId: String?
    ): String? {
        val prefix = "$seriesId:"
        val candidate = continueHint?.takeIf { hint ->
            if (hintIsWatched || !hint.startsWith(prefix)) return@takeIf false
            val coordinates = hint.removePrefix(prefix).split(':')
            coordinates.size == 2 && coordinates.all { it.toIntOrNull() != null }
        }
        return candidate ?: calculatedResumeId
    }

    // A series card represents one episode, not whichever episode's stream is
    // retained from an earlier player session. Resolve it from fresh history.
    fun mayReuseOpenStream(mediaType: String): Boolean = mediaType != "series"
}
