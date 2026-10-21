package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.search.SearchResultRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w800dp-h600dp-land")
class ArtworkPrefetchEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test fun effectPrefetchesVisibleItemsAndTracksHorizontalScrolling() {
        val items = (0..8).map { index ->
            MetaItem("title-$index", "movie", "Title $index", poster = "poster-$index")
        }
        val focusedKey = mutableStateOf(items[1].canonicalPresentationKey())
        val scheduled = CopyOnWriteArrayList<String>()
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = { scheduled += it }, landscape = { scheduled += it }, logo = { scheduled += it }
        )
        lateinit var moveToIndex: (Int) -> Unit
        compose.setContent {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            moveToIndex = { index -> scope.launch { listState.scrollToItem(index) } }
            Box(Modifier.width(300.dp).height(100.dp)) {
                ArtworkPrefetchEffect(
                    items = items,
                    enrichedItems = emptyMap(),
                    listState = listState,
                    focusedItemKey = focusedKey.value,
                    landscapeCards = false,
                    dispatcher = dispatcher
                )
                LazyRow(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(items, key = { _, item -> item.id }) { _, _ ->
                        Box(Modifier.width(100.dp).fillMaxHeight())
                    }
                }
            }
        }

        compose.waitUntil(5_000) { "poster-0" in scheduled }
        compose.runOnIdle { moveToIndex(6) }
        compose.waitUntil(5_000) { (6..8).all { "poster-$it" in scheduled } }
        assertTrue(scheduled.size <= 10)
        assertEquals(scheduled.distinct(), scheduled.toList())
    }

    @Test fun finiteSnapshotSourceCompletesTheEffectForDeterministicCoverage() {
        val item = MetaItem("finite-snapshot", "movie", "Finite", poster = "finite-poster")
        val scheduled = CopyOnWriteArrayList<String>()
        val dispatcher = ArtworkPrefetchDispatcher({ scheduled += it }, {}, {})

        compose.setContent {
            ArtworkPrefetchEffect(
                items = listOf(item), enrichedItems = emptyMap(), listState = rememberLazyListState(),
                focusedItemKey = item.canonicalPresentationKey(), landscapeCards = false,
                dispatcher = dispatcher, requestSnapshots = flowOf(listOf(0) to item.canonicalPresentationKey())
            )
        }

        compose.waitUntil(5_000) { "finite-poster" in scheduled }
    }

    @Test fun focusChangesUpdatePrefetchWithoutRestartingTheCollector() {
        val items = (0..4).map { index ->
            MetaItem("focus-$index", "movie", "Focus $index", poster = "focus-poster-$index")
        }
        val focusedKey = MutableStateFlow(items.first().canonicalPresentationKey())
        val collectorStarts = AtomicInteger()
        val snapshots: Flow<Pair<List<Int>, String?>> = flow {
            collectorStarts.incrementAndGet()
            focusedKey.collect { emit(listOf(0) to it) }
        }
        val scheduled = CopyOnWriteArrayList<String>()
        val dispatcher = ArtworkPrefetchDispatcher({ scheduled += it }, {}, {})

        compose.setContent {
            ArtworkPrefetchEffect(
                items = items,
                enrichedItems = emptyMap(),
                listState = rememberLazyListState(),
                focusedItemKey = focusedKey.value,
                landscapeCards = false,
                dispatcher = dispatcher,
                requestSnapshots = snapshots
            )
        }

        compose.waitUntil(5_000) { "focus-poster-0" in scheduled }
        compose.runOnIdle { focusedKey.value = items.last().canonicalPresentationKey() }
        compose.waitUntil(5_000) { "focus-poster-4" in scheduled }

        assertEquals(1, collectorStarts.get())
        assertTrue("the new focused neighborhood is prefetched", "focus-poster-3" in scheduled)
    }

    @Test fun preparesArtworkMetadataForVisibleAndUpcomingLandscapeCards() {
        val items = (0..6).map { index ->
            MetaItem("prepare-$index", "series", "Prepare $index", background = "backdrop-$index")
        }
        val prepared = CopyOnWriteArrayList<String>()
        val dispatcher = ArtworkPrefetchDispatcher({}, {}, {})

        compose.setContent {
            ArtworkPrefetchEffect(
                items = items,
                enrichedItems = emptyMap(),
                listState = rememberLazyListState(),
                focusedItemKey = items[2].canonicalPresentationKey(),
                landscapeCards = true,
                dispatcher = dispatcher,
                requestSnapshots = flowOf(listOf(4) to items[2].canonicalPresentationKey()),
                onPrepareItem = { prepared += it.id }
            )
        }

        compose.waitUntil(5_000) { prepared.containsAll(listOf("prepare-0", "prepare-1", "prepare-2", "prepare-3", "prepare-4")) }
        assertEquals(setOf("prepare-0", "prepare-1", "prepare-2", "prepare-3", "prepare-4"), prepared.toSet())
    }

    @Test fun dispatcherRoutesEachArtworkRoleToItsMatchingLoader() {
        val posters = mutableListOf<String>()
        val landscapes = mutableListOf<String>()
        val logos = mutableListOf<String>()
        val dispatcher = ArtworkPrefetchDispatcher(posters::add, landscapes::add, logos::add)

        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "poster"))
        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, "landscape"))
        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, "logo"))

        assertEquals(listOf("poster"), posters)
        assertEquals(listOf("landscape"), landscapes)
        assertEquals(listOf("logo"), logos)
    }

    @Test fun sizedPosterRequestUsesVisibleCardDimensionsWhenSupported() {
        val posters = mutableListOf<Pair<String, DpSize>>()
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = { error("Sized request unexpectedly used the default poster path") },
            landscape = {}, logo = {}, posterSized = { url, size -> posters += url to size }
        )
        val size = DpSize(80.dp, 120.dp)

        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "poster", size))

        assertEquals(listOf("poster" to size), posters)
    }

    @Test fun sizedLandscapeRequestUsesVisibleCardDimensionsWhenSupported() {
        val backdrops = mutableListOf<Pair<String, DpSize>>()
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = {}, landscape = { error("Sized request unexpectedly used the default landscape path") },
            logo = {}, landscapeSized = { url, size -> backdrops += url to size }
        )
        val size = DpSize(320.dp, 180.dp)

        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, "backdrop", landscapeSize = size))

        assertEquals(listOf("backdrop" to size), backdrops)
    }

    @Test fun rowIndicesRespectLinearFiniteAndRepeatedGridWindows() {
        assertEquals(
            listOf(0, 2),
            artworkPrefetchItemIndices(
                visibleIndices = listOf(-1, 0, 2, 2, 8), itemCount = 4, visibleItemCount = 3,
                isInfiniteLoopEnabled = false, isInfiniteScrollingEnabled = true
            )
        )
        assertEquals(
            listOf(1, 4),
            artworkPrefetchItemIndices(
                visibleIndices = listOf(1, 5, 8, 4), itemCount = 8, visibleItemCount = 5,
                isInfiniteLoopEnabled = true, isInfiniteScrollingEnabled = false
            )
        )
        assertEquals(
            listOf(4, 0),
            artworkPrefetchItemIndices(
                visibleIndices = listOf(4, 5, 6, 11, 12, -1), itemCount = 8, visibleItemCount = 5,
                isInfiniteLoopEnabled = true, isInfiniteScrollingEnabled = true
            )
        )
        assertEquals(
            listOf(0, 49),
            artworkPrefetchItemIndices(
                visibleIndices = listOf(50, 51, 100), itemCount = 60, visibleItemCount = 100,
                isInfiniteLoopEnabled = true, isInfiniteScrollingEnabled = true
            )
        )
        assertEquals(
            emptyList<Int>(),
            artworkPrefetchItemIndices(
                visibleIndices = listOf(0, 1), itemCount = 0, visibleItemCount = 0,
                isInfiniteLoopEnabled = true, isInfiniteScrollingEnabled = true
            )
        )
    }

    @Test fun dispatchWindowDeduplicatesOnlyWhileRequestsRemainInTheActiveWindow() {
        val posters = mutableListOf<String>()
        val dispatcher = ArtworkPrefetchDispatcher(posters::add, {}, {})
        val requests = (0..64).map { ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "p$it") }
        val remembered = linkedSetOf<ArtworkPrefetchRequest>()

        dispatchArtworkPrefetchRequests(requests, remembered, dispatcher)
        dispatchArtworkPrefetchRequests(listOf(requests.last(), requests.first()), remembered, dispatcher)

        assertEquals(setOf(requests.first(), requests.last()), remembered)
        assertEquals(65, posters.size)

        dispatchArtworkPrefetchRequests(listOf(requests[1]), remembered, dispatcher)
        assertEquals(1, remembered.size)
        assertEquals("p1", posters.last())
    }

    @Test fun productionDispatcherSafelyRoutesBlankCoilRequests() {
        val dispatcher = ArtworkPrefetchDispatcher.forCoil(org.robolectric.RuntimeEnvironment.getApplication())
        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, ""))
        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, ""))
        dispatcher.dispatch(ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, ""))
    }

    @Test fun searchResultRowConnectsVisibleCardsToTheSharedPrefetchEffect() {
        val item = MetaItem("search-art", "movie", "Search Art", poster = "search-poster")
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, it) },
            landscape = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, it) },
            logo = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, it) },
            posterSized = { url, size -> requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, url, size) }
        )

        compose.setContent {
            MaterialTheme {
                SearchResultRow(
                    title = "Movies", items = listOf(item), posterHeight = 120.dp, targetKey = null,
                    targetRequester = FocusRequester(), keyboardRequester = FocusRequester(),
                    drawerRequester = FocusRequester(), moreRequester = FocusRequester(), lastFocusedId = null,
                    watchedIds = emptySet(), enrichedItems = emptyMap(), onClick = {},
                    onLongClick = { _, _ -> }, onMore = { _, _ -> }, onFocused = {},
                    modifier = Modifier.width(500.dp), rowRequester = FocusRequester(),
                    upRequester = FocusRequester(), downRequester = FocusRequester(), landscapeCards = false,
                    pivotOffset = 0.dp, artworkPrefetchDispatcher = dispatcher
                )
            }
        }

        compose.waitUntil(5_000) {
            requests.any {
                it.role == ArtworkPrefetchRole.Poster && it.url == "search-poster" &&
                    it.posterSize == DpSize(80.dp, 120.dp)
            }
        }
    }

    @Test fun searchResultRowUsesProductionDispatcherWhenNoTestDispatcherIsProvided() {
        val item = MetaItem("search-no-art", "movie", "No Search Artwork")

        compose.setContent {
            MaterialTheme {
                SearchResultRow(
                    title = "Movies", items = listOf(item), posterHeight = 120.dp, targetKey = null,
                    targetRequester = FocusRequester(), keyboardRequester = FocusRequester(),
                    drawerRequester = FocusRequester(), moreRequester = FocusRequester(), lastFocusedId = null,
                    watchedIds = emptySet(), enrichedItems = emptyMap(), onClick = {},
                    onLongClick = { _, _ -> }, onMore = { _, _ -> }, onFocused = {},
                    modifier = Modifier.width(500.dp), rowRequester = FocusRequester(),
                    upRequester = FocusRequester(), downRequester = FocusRequester(), landscapeCards = false,
                    pivotOffset = 0.dp
                )
            }
        }

        compose.waitForIdle()
    }
}
