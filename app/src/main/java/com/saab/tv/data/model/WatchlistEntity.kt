package com.saab.tv.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity

@Immutable
@Entity(
    tableName = "watchlist",
    primaryKeys = ["profileId", "id"]
)
data class WatchlistEntity(
    val profileId: Int,
    val id: String,                   // IMDb or addon ID (e.g., "tt0111161")
    val type: String,                 // "movie" or "series"
    val title: String,
    val poster: String?,
    val addedAt: Long,                // System.currentTimeMillis() when bookmarked
    val background: String? = null,
    val logo: String? = null
)
