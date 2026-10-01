package com.saab.tv.data.account

import com.saab.tv.data.model.WatchHistoryEntity

internal data class RemotePlaybackHistory(val items: List<WatchHistoryEntity>, val canFillMissing: Boolean, val revision: Long = 0)

/** Last event wins, not furthest position: an intentional rewind is legitimate progress. */
internal fun newestPlaybackHistory(
    local: List<WatchHistoryEntity>, remote: RemotePlaybackHistory, profileId: Int
): List<WatchHistoryEntity> {
    val rows = local.filter { it.profileId == profileId }.associateBy { it.id }.toMutableMap()
    remote.items.filter { it.profileId == profileId && it.lastWatched > 0 }.forEach { incoming ->
        val current = rows[incoming.id]
        if ((current == null && remote.canFillMissing) ||
            (current != null && incoming.lastWatched > current.lastWatched)) rows[incoming.id] = incoming
    }
    return rows.values.toList()
}
