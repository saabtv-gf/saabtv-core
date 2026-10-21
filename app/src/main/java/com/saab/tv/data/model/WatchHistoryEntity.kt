package com.saab.tv.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.saab.tv.domain.schema.CanonicalEntityKey

@Immutable
@Entity(
    tableName = "watch_history",
    primaryKeys = ["profileId", "id"],
    indices = [Index(value = ["profileId", "canonicalIdentityKey"])]
)
data class WatchHistoryEntity(
    val profileId: Int = 1,
    val id: String,
    val title: String,
    val poster: String?,
    val background: String? = null,
    val logo: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val type: String,
    val watched: Boolean = false,   // true = fully watched (past threshold), false = in progress
    val scrobbled: Boolean = false, // true = Trakt knows about this item (scrobble was sent successfully)
    /** Indexed provider-independent lookup key; raw ID remains the compatibility primary key. */
    @ColumnInfo(defaultValue = "''")
    val canonicalIdentityKey: String = CanonicalEntityKey.playable(id, type)
) {
    fun progress(): Float {
        if (duration == 0L) return 0f
        return position.toFloat() / duration.toFloat()
    }
}
