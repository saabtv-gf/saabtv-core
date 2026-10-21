package com.saab.tv.ui.watchlist

import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.seriesIdFromPlaybackId
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.R
import com.saab.tv.ui.home.CatalogGridSort
import com.saab.tv.ui.home.CatalogMediaFilter
import com.saab.tv.ui.home.applyCatalogGridOptions

data class MyLibraryItems(
    val inProgress: List<LibraryCardProjection> = emptyList(),
    val watchlist: List<LibraryCardProjection> = emptyList(),
    val watched: List<LibraryCardProjection> = emptyList()
)

enum class MyLibraryStatusFilter(val labelRes: Int) {
    ALL(R.string.library_filter_all),
    WATCHLIST(R.string.library_filter_watchlist),
    IN_PROGRESS(R.string.library_filter_in_progress),
    WATCHED(R.string.library_filter_watched)
}

enum class MyLibraryMediaFilter(val labelRes: Int) {
    ALL(R.string.library_media_all),
    MOVIES(R.string.library_media_movies),
    SERIES(R.string.library_media_series)
}

data class MyLibrarySection(val title: String, val items: List<LibraryCardProjection>)

internal const val LIBRARY_CONTINUE_WATCHING = "Continue Watching"
internal const val LIBRARY_WATCHLIST = "Watchlist"
internal const val LIBRARY_WATCHED = "Watched"

internal fun librarySectionTitleResource(title: String): Int = when (title) {
    LIBRARY_CONTINUE_WATCHING -> R.string.library_continue_watching
    LIBRARY_WATCHLIST -> R.string.library_filter_watchlist
    LIBRARY_WATCHED -> R.string.library_filter_watched
    else -> error("Unknown My Library section: $title")
}

/** Keep Library filtering as a cheap projection; Room remains the single source of truth. */
internal fun filterMyLibrary(
    library: MyLibraryItems,
    status: MyLibraryStatusFilter,
    media: MyLibraryMediaFilter
): List<MyLibrarySection> {
    val candidates = when (status) {
        MyLibraryStatusFilter.ALL -> listOf(
            MyLibrarySection(LIBRARY_CONTINUE_WATCHING, library.inProgress),
            MyLibrarySection(LIBRARY_WATCHLIST, library.watchlist),
            MyLibrarySection(LIBRARY_WATCHED, library.watched)
        )
        MyLibraryStatusFilter.WATCHLIST -> listOf(MyLibrarySection(LIBRARY_WATCHLIST, library.watchlist))
        MyLibraryStatusFilter.IN_PROGRESS -> listOf(MyLibrarySection(LIBRARY_CONTINUE_WATCHING, library.inProgress))
        MyLibraryStatusFilter.WATCHED -> listOf(MyLibrarySection(LIBRARY_WATCHED, library.watched))
    }

    return candidates.mapNotNull { section ->
        val filtered = when (media) {
            MyLibraryMediaFilter.ALL -> section.items
            MyLibraryMediaFilter.MOVIES -> section.items.filter { it.asActionItem().type == "movie" }
            MyLibraryMediaFilter.SERIES -> section.items.filter { it.asActionItem().type == "series" }
        }
        section.copy(items = filtered).takeIf { filtered.isNotEmpty() }
    }
}

/** Reuse the catalog's sort semantics while preserving the section's default order for ties. */
internal fun sortMyLibrarySections(
    sections: List<MyLibrarySection>,
    sort: CatalogGridSort
): List<MyLibrarySection> {
    if (sort == CatalogGridSort.FEATURED) return sections
    return sections.map { section ->
        val byIdentity = section.items.associateBy { it.asActionItem().canonicalPresentationKey() }
        val sorted = applyCatalogGridOptions(
            section.items.map(LibraryCardProjection::asActionItem),
            CatalogMediaFilter.ALL,
            sort
        ).mapNotNull { byIdentity[it.canonicalPresentationKey()] }
        section.copy(items = sorted)
    }
}

/**
 * Canonical card data plus the small playback state needed by Library. Opaque legacy IDs
 * remain available only as an action-boundary fallback until their addon origin is known.
 */
data class LibraryCardProjection(
    val content: TitleCardProjection?,
    val progress: Float = 0f,
    val resumePlaybackId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNewEpisode: Boolean = false,
    private val opaqueCompatibilityItem: MetaItem? = null
) {
    fun asActionItem(): MetaItem = (content?.asMetaItem() ?: opaqueCompatibilityItem
        ?: error("Library card has no usable canonical or compatibility identity")).copy(
        progress = progress,
        resumePlaybackId = resumePlaybackId,
        hasNewEpisode = hasNewEpisode
    )

    companion object {
        fun from(item: MetaItem, positionMs: Long = 0L, durationMs: Long = 0L): LibraryCardProjection {
            val projection = item.toCanonicalProjection()
            return LibraryCardProjection(
                content = projection,
                progress = item.progress,
                resumePlaybackId = item.resumePlaybackId,
                positionMs = positionMs,
                durationMs = durationMs,
                hasNewEpisode = item.hasNewEpisode,
                opaqueCompatibilityItem = item.takeIf { projection == null }
            )
        }
    }
}

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
            latest.lastWatched to LibraryCardProjection.from(
                MetaItem(
                    id = titleId,
                    type = latest.type,
                    name = if (isSeries) seriesTitles[titleId] ?: latest.title else latest.title,
                    poster = latest.poster,
                    background = latest.background,
                    logo = latest.logo,
                    progress = latest.progress(),
                    resumePlaybackId = latest.id
                ),
                positionMs = latest.position,
                durationMs = latest.duration
            )
        }
        .sortedByDescending { it.first }
        .map { it.second }
        .distinctBy { it.canonicalPresentationKey() }

    val saved = watchlist.sortedByDescending { it.addedAt }.map {
        MetaItem(
            id = it.id,
            type = it.type,
            name = it.title,
            poster = it.poster,
            background = it.background,
            logo = it.logo,
            addonBaseUrl = it.sourceAddonBaseUrl
        )
    }.map(LibraryCardProjection::from)
        .distinctBy { it.canonicalPresentationKey() }

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
        .map { LibraryCardProjection.from(it.second) }
        .distinctBy { it.canonicalPresentationKey() }

    return MyLibraryItems(inProgress, saved, watched)
}

private fun LibraryCardProjection.canonicalPresentationKey(): String =
    content?.presentationKey ?: asActionItem().canonicalPresentationKey()
