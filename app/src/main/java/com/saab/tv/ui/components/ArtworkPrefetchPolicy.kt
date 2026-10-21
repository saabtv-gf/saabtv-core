package com.saab.tv.ui.components

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow

internal enum class ArtworkPrefetchRole { Poster, Landscape, Logo }

internal data class ArtworkPrefetchRequest(
    val role: ArtworkPrefetchRole,
    val url: String,
    val posterSize: DpSize? = null,
    val landscapeSize: DpSize? = null
)

/** Dispatches requests through the same image paths used by the visible card renderer. */
internal class ArtworkPrefetchDispatcher(
    private val poster: (String) -> Unit,
    private val landscape: (String) -> Unit,
    private val logo: (String) -> Unit,
    private val posterSized: ((String, DpSize) -> Unit)? = null,
    private val landscapeSized: ((String, DpSize) -> Unit)? = null
) {
    fun dispatch(request: ArtworkPrefetchRequest) {
        when (request.role) {
            ArtworkPrefetchRole.Poster -> request.posterSize?.let { size ->
                posterSized?.invoke(request.url, size) ?: poster(request.url)
            } ?: poster(request.url)
            ArtworkPrefetchRole.Landscape -> request.landscapeSize?.let { size ->
                landscapeSized?.invoke(request.url, size) ?: landscape(request.url)
            } ?: landscape(request.url)
            ArtworkPrefetchRole.Logo -> logo(request.url)
        }
    }

    companion object {
        fun forCoil(context: Context) = ArtworkPrefetchDispatcher(
            poster = { com.saab.tv.ui.utils.ImagePrefetcher.prefetch(context, it) },
            landscape = { com.saab.tv.ui.utils.ImagePrefetcher.prefetchLandscape(context, it) },
            logo = { com.saab.tv.ui.utils.ImagePrefetcher.prefetchLogo(context, it) },
            posterSized = { url, size -> com.saab.tv.ui.utils.ImagePrefetcher.prefetch(context, url, size) },
            landscapeSized = { url, size -> com.saab.tv.ui.utils.ImagePrefetcher.prefetchLandscape(context, url, size) }
        )
    }
}

internal fun dispatchArtworkPrefetchRequests(
    requests: List<ArtworkPrefetchRequest>,
    dispatchedRequests: MutableSet<ArtworkPrefetchRequest>,
    dispatcher: ArtworkPrefetchDispatcher
) {
    // Dedupe only while an asset remains in this row's visible/focus window.
    // Keeping departed requests forever can suppress prefetch after Coil evicts them.
    dispatchedRequests.retainAll(requests.toSet())
    requests.forEach { request ->
        if (dispatchedRequests.add(request)) dispatcher.dispatch(request)
    }
}

internal fun artworkPrefetchItemIndices(
    visibleIndices: List<Int>,
    itemCount: Int,
    visibleItemCount: Int,
    isInfiniteLoopEnabled: Boolean,
    isInfiniteScrollingEnabled: Boolean
): List<Int> {
    val contentCount = if (isInfiniteLoopEnabled) {
        itemCount.coerceAtMost(visibleItemCount.coerceIn(5, 50))
    } else itemCount
    val sectionSize = contentCount + if (isInfiniteLoopEnabled) 1 else 0
    return visibleIndices.map { index ->
        if (isInfiniteLoopEnabled && isInfiniteScrollingEnabled) index % sectionSize else index
    }.filter { it in 0 until contentCount }.distinct()
}

/** Canonical keys are resolved once per list instead of once per focus update. */
internal fun firstItemIndexByCanonicalKey(items: List<MetaItem>): Map<String, Int> = buildMap(items.size) {
    items.forEachIndexed { index, item ->
        val key = item.canonicalPresentationKey()
        if (key !in this) put(key, index)
    }
}

/** Prefetches only visible content and a small neighborhood around the current selection. */
@Composable
internal fun ArtworkPrefetchEffect(
    items: List<MetaItem>,
    enrichedItems: Map<String, MetaItem>,
    listState: LazyListState,
    focusedItemKey: String?,
    landscapeCards: Boolean,
    dispatcher: ArtworkPrefetchDispatcher,
    requestSnapshots: Flow<Pair<List<Int>, String?>>? = null,
    posterSize: DpSize? = null,
    landscapeSize: DpSize? = null,
    onPrepareItem: (MetaItem) -> Unit = {}
) {
    val latestFocusedItemKey = rememberUpdatedState(focusedItemKey)
    val updates = remember(listState, requestSnapshots) {
        requestSnapshots ?: snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.map { it.index }.distinct() to latestFocusedItemKey.value
        }
    }
    ArtworkPrefetchCollector(
        items, enrichedItems, listState, landscapeCards, dispatcher, updates, posterSize, landscapeSize, onPrepareItem
    )
}

@Composable
internal fun GridArtworkPrefetchEffect(
    items: List<MetaItem>,
    enrichedItems: Map<String, MetaItem>,
    gridState: LazyGridState,
    focusedItemKey: String?,
    landscapeCards: Boolean,
    dispatcher: ArtworkPrefetchDispatcher,
    posterSize: DpSize? = null,
    landscapeSize: DpSize? = null,
    onPrepareItem: (MetaItem) -> Unit = {}
) {
    val latestFocusedItemKey = rememberUpdatedState(focusedItemKey)
    val updates = remember(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index }.distinct() to latestFocusedItemKey.value }
    }
    ArtworkPrefetchCollector(
        items, enrichedItems, gridState, landscapeCards, dispatcher, updates, posterSize, landscapeSize, onPrepareItem
    )
}

@Composable
private fun ArtworkPrefetchCollector(
    items: List<MetaItem>,
    enrichedItems: Map<String, MetaItem>,
    stateOwner: Any,
    landscapeCards: Boolean,
    dispatcher: ArtworkPrefetchDispatcher,
    updates: Flow<Pair<List<Int>, String?>>,
    posterSize: DpSize?,
    landscapeSize: DpSize?,
    onPrepareItem: (MetaItem) -> Unit
) {
    val dispatchedRequests = remember(stateOwner) { linkedSetOf<ArtworkPrefetchRequest>() }
    val latestOnPrepareItem = rememberUpdatedState(onPrepareItem)
    val itemIndexByCanonicalKey = remember(items) { firstItemIndexByCanonicalKey(items) }
    LaunchedEffect(items, enrichedItems, stateOwner, landscapeCards, dispatcher, updates, posterSize, landscapeSize) {
        updates.distinctUntilChanged().collect { (visibleIndices, selectedKey) ->
            artworkPrefetchCandidateIndices(
                items = items,
                visibleIndices = visibleIndices,
                focusedItemKey = selectedKey,
                itemIndexByCanonicalKey = itemIndexByCanonicalKey
            ).forEach { index ->
                latestOnPrepareItem.value(items[index])
            }
            artworkPrefetchRequests(
                items = items,
                enrichedItems = enrichedItems,
                visibleIndices = visibleIndices,
                focusedItemKey = selectedKey,
                landscapeCards = landscapeCards,
                posterSize = posterSize,
                landscapeSize = landscapeSize,
                itemIndexByCanonicalKey = itemIndexByCanonicalKey
            ).let { requests -> dispatchArtworkPrefetchRequests(requests, dispatchedRequests, dispatcher) }
        }
    }
}

/** Visible items plus the bounded two-card neighborhood around focus. */
internal fun artworkPrefetchCandidateIndices(
    items: List<MetaItem>,
    visibleIndices: List<Int>,
    focusedItemKey: String?,
    itemIndexByCanonicalKey: Map<String, Int>? = null
): List<Int> {
    if (items.isEmpty()) return emptyList()

    val indices = linkedSetOf<Int>()
    val focusedIndex = focusedItemKey?.let { key ->
        (itemIndexByCanonicalKey ?: firstItemIndexByCanonicalKey(items))[key] ?: -1
    } ?: -1
    if (focusedIndex >= 0) {
        // Queue the selected title and the nearest navigation targets first. The
        // visible shelf is still warmed in full below, but its left-to-right
        // layout order must not put unrelated images ahead of the active card.
        indices += focusedIndex
        for (distance in 1..2) {
            (focusedIndex - distance).takeIf { it in items.indices }?.let(indices::add)
            (focusedIndex + distance).takeIf { it in items.indices }?.let(indices::add)
        }
    }
    visibleIndices.filterTo(indices) { it in items.indices }
    return indices.toList()
}

/** Visible artwork plus a bounded two-card window around the focused result. */
internal fun artworkPrefetchRequests(
    items: List<MetaItem>,
    enrichedItems: Map<String, MetaItem>,
    visibleIndices: List<Int>,
    focusedItemKey: String?,
    landscapeCards: Boolean,
    posterSize: DpSize? = null,
    landscapeSize: DpSize? = null,
    itemIndexByCanonicalKey: Map<String, Int>? = null
): List<ArtworkPrefetchRequest> {
    if (items.isEmpty()) return emptyList()

    return artworkPrefetchCandidateIndices(
        items = items,
        visibleIndices = visibleIndices,
        focusedItemKey = focusedItemKey,
        itemIndexByCanonicalKey = itemIndexByCanonicalKey
    ).flatMap { index ->
        val item = items[index]
        val enriched = enrichedItems[item.canonicalPresentationKey()]
        buildList {
            if (landscapeCards) {
                (enriched?.background?.takeIf(String::isNotBlank) ?: item.background)
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, it, landscapeSize = landscapeSize)) }
                (enriched?.logo?.takeIf(String::isNotBlank) ?: item.logo)
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, it)) }
            } else {
                (enriched?.poster?.takeIf(String::isNotBlank) ?: item.poster)
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, it, posterSize)) }
            }
        }
    }.distinct()
}
