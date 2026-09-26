package com.saab.tv.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity

@Immutable
@Entity(
    tableName = "series_next_up",
    primaryKeys = ["profileId", "seriesId"]
)
data class SeriesNextUpEntity(
    val profileId: Int = 1,
    val seriesId: String,   // IMDb ID (e.g., "tt1190634")
    val title: String,                  // Show name
    val poster: String?,
    val nextSeason: Int,
    val nextEpisode: Int,
    val nextEpisodeTitle: String?,
    val nextReleased: String? = null,   // Air date ISO string (e.g., "2025-07-10"), null = already aired
    val isComplete: Boolean = false,    // All episodes watched — don't show in continue watching
    val isNewEpisode: Boolean = false, // True when a new episode aired after user was caught up
    val updatedAt: Long
)
