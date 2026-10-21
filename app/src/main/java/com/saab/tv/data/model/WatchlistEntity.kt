package com.saab.tv.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.saab.tv.domain.schema.CanonicalEntityKey
import com.saab.tv.domain.schema.addonInstallationKey

@Immutable
@Entity(
    tableName = "watchlist",
    primaryKeys = ["profileId", "id", "sourceAddonKey"],
    indices = [Index(value = ["profileId", "canonicalIdentityKey"])]
)
data class WatchlistEntity(
    val profileId: Int,
    val id: String,                   // IMDb or addon ID (e.g., "tt0111161")
    val type: String,                 // "movie" or "series"
    val title: String,
    val poster: String?,
    val addedAt: Long,                // System.currentTimeMillis() when bookmarked
    val background: String? = null,
    val logo: String? = null,
    /** Retains the provider origin for opaque addon IDs in Library and detail resolution. */
    val sourceAddonBaseUrl: String? = null,
    @ColumnInfo(defaultValue = "''")
    val sourceAddonKey: String = sourceAddonBaseUrl?.let(::addonInstallationKey).orEmpty(),
    /** Indexed canonical key lets new joins avoid assuming provider IDs are globally unique. */
    @ColumnInfo(defaultValue = "''")
    val canonicalIdentityKey: String = CanonicalEntityKey.title(id, type, sourceAddonKey)
)
