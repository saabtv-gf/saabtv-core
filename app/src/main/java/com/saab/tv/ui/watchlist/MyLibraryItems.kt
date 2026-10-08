package com.saab.tv.ui.watchlist

import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.seriesIdFromPlaybackId

data class MyLibraryItems(
    val inProgress: List<MetaItem> = emptyList(),
    val watchlist: List<MetaItem> = emptyList(),
    val watched: List<MetaItem> = emptyList()
)

internal fun buildMyLibraryItems(
    history: List<WatchHistoryEntity>,
    watchlist: List<WatchlistEntity>,
    nextUp: List<SeriesNextUpEntity>
): MyLibraryItems {
    val seriesTitles = nextUp.associate { it.seriesId to it.title }

    val inProgress = history.asSequence()
        .filter { !it.watched && it.position > 0L }
        .groupBy { if (it.type == "series") seriesIdFromPlaybackId(it.id) else it.id }
        .mapNotNull { (titleId, entries) ->
            val latest = entries.maxByOrNull { it.lastWatched } ?: return@mapNotNull null
            val isSeries = latest.type == "series"
            latest.lastWatched to MetaItem(
                id = titleId,
                type = latest.type,
                name = if (isSeries) seriesTitles[titleId] ?: latest.title else latest.title,
                poster = latest.poster,
                background = latest.background,
                logo = latest.logo,
                progress = latest.progress(),
                resumePlaybackId = latest.id
            )
        }
        .sortedByDescending { it.first }
        .map { it.second }
        .distinctBy { it.type to it.id }

    val saved = watchlist.sortedByDescending { it.addedAt }.map {
        MetaItem(id = it.id, type = it.type, name = it.title, poster = it.poster)
    }.distinctBy { it.type to it.id }

    val watched = history.asSequence()
        .filter { it.watched }
        .groupBy { if (it.type == "series") seriesIdFromPlaybackId(it.id) else it.id }
        .mapNotNull { (titleId, entries) ->
            val latest = entries.maxByOrNull { it.lastWatched } ?: return@mapNotNull null
            val isSeries = latest.type == "series"
            latest.lastWatched to MetaItem(
                id = titleId,
                type = latest.type,
                name = if (isSeries) seriesTitles[titleId] ?: latest.title else latest.title,
                poster = latest.poster,
                background = latest.background,
                logo = latest.logo
            )
        }
        .sortedByDescending { it.first }
        .map { it.second }
        .distinctBy { it.type to it.id }

    return MyLibraryItems(inProgress, saved, watched)
}
