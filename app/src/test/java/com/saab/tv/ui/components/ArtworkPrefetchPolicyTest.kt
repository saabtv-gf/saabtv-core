package com.saab.tv.ui.components

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkPrefetchPolicyTest {
    @Test fun canonicalItemIndexPreservesFirstDuplicateAndBoundsFocusWindowLookup() {
        val first = MetaItem("duplicate", "movie", "First")
        val second = MetaItem("duplicate", "movie", "Second")
        val third = MetaItem("other", "movie", "Third")
        val items = listOf(first, second, third)
        val indexByKey = firstItemIndexByCanonicalKey(items)

        assertEquals(0, indexByKey[first.canonicalPresentationKey()])
        assertEquals(2, indexByKey[third.canonicalPresentationKey()])
        assertEquals(
            listOf(0, 1, 2),
            artworkPrefetchCandidateIndices(
                items = items,
                visibleIndices = listOf(2),
                focusedItemKey = first.canonicalPresentationKey(),
                itemIndexByCanonicalKey = indexByKey
            )
        )
    }

    @Test fun focusedArtworkIsQueuedBeforeTheRestOfTheVisibleShelf() {
        val items = (0..7).map { index ->
            MetaItem(
                "priority-$index", "series", "Priority $index",
                background = "backdrop-$index", logo = "logo-$index"
            )
        }

        val requests = artworkPrefetchRequests(
            items = items,
            enrichedItems = emptyMap(),
            visibleIndices = items.indices.toList(),
            focusedItemKey = items[4].canonicalPresentationKey(),
            landscapeCards = true
        )

        assertEquals(
            listOf("backdrop-4", "logo-4", "backdrop-3", "logo-3", "backdrop-5", "logo-5"),
            requests.take(6).map(ArtworkPrefetchRequest::url)
        )
        val visibleArtwork = (0..7).flatMap { listOf("backdrop-$it", "logo-$it") }.toSet()
        assertEquals(visibleArtwork,
            requests.map(ArtworkPrefetchRequest::url).toSet())
    }

    @Test fun prefetchDedupTracksTheCurrentWindowAndWarmsRevisitedArtworkAgain() {
        val dispatchedUrls = mutableListOf<String>()
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = { dispatchedUrls += it },
            landscape = {},
            logo = {}
        )
        val dispatched = linkedSetOf<ArtworkPrefetchRequest>()
        val posterA = ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "poster-a")
        val posterB = ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "poster-b")

        dispatchArtworkPrefetchRequests(listOf(posterA), dispatched, dispatcher)
        dispatchArtworkPrefetchRequests(listOf(posterA), dispatched, dispatcher)
        assertEquals(listOf("poster-a"), dispatchedUrls)

        dispatchArtworkPrefetchRequests(listOf(posterB), dispatched, dispatcher)
        dispatchArtworkPrefetchRequests(listOf(posterA), dispatched, dispatcher)
        assertEquals(listOf("poster-a", "poster-b", "poster-a"), dispatchedUrls)
    }

    @Test fun posterModePrefersNonBlankEnrichmentAndDeduplicatesSharedAssets() {
        val first = MetaItem("movie-a", "movie", "A", poster = "shared-poster")
        val second = MetaItem("movie-b", "movie", "B", poster = "shared-poster")
        val third = MetaItem("movie-c", "movie", "C", poster = "provider-poster")
        val fourth = MetaItem("movie-d", "movie", "D", poster = " ")
        val result = artworkPrefetchRequests(
            items = listOf(first, second, third, fourth),
            enrichedItems = mapOf(
                first.canonicalPresentationKey() to MetaItem("movie-a", "movie", "A", poster = "tmdb-poster"),
                third.canonicalPresentationKey() to MetaItem("movie-c", "movie", "C", poster = "  ")
            ),
            visibleIndices = listOf(0, 1, 2, 3),
            focusedItemKey = null,
            landscapeCards = false
        )

        assertEquals(listOf(ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "tmdb-poster"),
            ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "shared-poster"),
            ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, "provider-poster")), result)
        assertEquals(ArtworkPrefetchRole.entries.toList(), listOf(
            ArtworkPrefetchRole.Poster, ArtworkPrefetchRole.Landscape, ArtworkPrefetchRole.Logo
        ))
        assertEquals(ArtworkPrefetchRole.valueOf("Poster"), result.first().role)
    }

    @Test fun posterModeUsesVisibleAndBoundedNeighborPostersOnly() {
        val items = (0..7).map { index -> MetaItem(
            id = "title-$index", type = "movie", name = "Title $index", poster = "poster-$index"
        ) }
        val result = artworkPrefetchRequests(
            items = items,
            enrichedItems = emptyMap(),
            visibleIndices = listOf(6, 6, -1, 99),
            focusedItemKey = items[3].canonicalPresentationKey(),
            landscapeCards = false
        )

        assertEquals(
            setOf("poster-1", "poster-2", "poster-3", "poster-4", "poster-5", "poster-6"),
            result.map(ArtworkPrefetchRequest::url).toSet()
        )
        assertEquals(setOf(ArtworkPrefetchRole.Poster), result.map(ArtworkPrefetchRequest::role).toSet())
    }

    @Test fun posterPrefetchKeepsTheVisibleCardsDecodeSize() {
        val item = MetaItem("search-result", "movie", "Search", poster = "poster")
        val visibleSize = DpSize(80.dp, 120.dp)

        val request = artworkPrefetchRequests(
            items = listOf(item), enrichedItems = emptyMap(), visibleIndices = listOf(0),
            focusedItemKey = item.canonicalPresentationKey(), landscapeCards = false,
            posterSize = visibleSize
        ).single()

        assertEquals(visibleSize, request.posterSize)
    }

    @Test fun focusedWindowClampsAtBothEndsOfTheResultList() {
        val items = (0..3).map { index ->
            MetaItem("edge-$index", "movie", "Edge $index", poster = "poster-$index")
        }
        fun urls(focusedIndex: Int) = artworkPrefetchRequests(
            items = items, enrichedItems = emptyMap(), visibleIndices = emptyList(),
            focusedItemKey = items[focusedIndex].canonicalPresentationKey(), landscapeCards = false
        ).map(ArtworkPrefetchRequest::url).toSet()

        assertEquals(setOf("poster-0", "poster-1", "poster-2"), urls(0))
        assertEquals(setOf("poster-1", "poster-2", "poster-3"), urls(3))
    }

    @Test fun landscapeModePrefersEnrichmentAndFallsBackToProviderArtwork() {
        val items = listOf(
            MetaItem("movie-a", "movie", "A", background = "provider-backdrop-a", logo = "provider-logo-a"),
            MetaItem("movie-b", "movie", "B", background = "provider-backdrop-b", logo = "provider-logo-b"),
            MetaItem("movie-c", "movie", "C", background = "provider-backdrop-c", logo = "provider-logo-c")
        )
        val enriched = MetaItem(
            "movie-b", "movie", "B enriched", background = "tmdb-backdrop-b", logo = "tmdb-logo-b"
        )
        val result = artworkPrefetchRequests(
            items = items,
            enrichedItems = mapOf(items[1].canonicalPresentationKey() to enriched),
            visibleIndices = listOf(1),
            focusedItemKey = items[1].canonicalPresentationKey(),
            landscapeCards = true
        )

        assertEquals(
            setOf(
                "provider-backdrop-a", "provider-logo-a", "tmdb-backdrop-b", "tmdb-logo-b",
                "provider-backdrop-c", "provider-logo-c"
            ),
            result.map(ArtworkPrefetchRequest::url).toSet()
        )
        assertEquals(3, result.count { it.role == ArtworkPrefetchRole.Landscape })
        assertEquals(3, result.count { it.role == ArtworkPrefetchRole.Logo })
    }

    @Test fun landscapePrefetchKeepsResponsiveGridDecodeSize() {
        val item = MetaItem("grid-series", "series", "Grid", background = "backdrop", logo = "logo")
        val visibleSize = DpSize(320.dp, 180.dp)

        val requests = artworkPrefetchRequests(
            items = listOf(item), enrichedItems = emptyMap(), visibleIndices = listOf(0),
            focusedItemKey = item.canonicalPresentationKey(), landscapeCards = true,
            landscapeSize = visibleSize
        )

        assertEquals(visibleSize, requests.single { it.role == ArtworkPrefetchRole.Landscape }.landscapeSize)
    }

    @Test fun emptyRowsMissingFocusAndMissingImagesDoNotScheduleRequests() {
        assertEquals(
            emptyList<ArtworkPrefetchRequest>(),
            artworkPrefetchRequests(emptyList(), emptyMap(), listOf(0), null, true)
        )
        val item = MetaItem("without-art", "series", "No Art")
        assertEquals(
            emptyList<ArtworkPrefetchRequest>(),
            artworkPrefetchRequests(
                items = listOf(item), enrichedItems = emptyMap(), visibleIndices = listOf(-1, 1),
                focusedItemKey = "missing-key", landscapeCards = false
            )
        )
    }

    @Test fun landscapeModeUsesProviderFallbackForBlankEnrichmentAndRejectsBlankProviderAssets() {
        val items = listOf(
            MetaItem("series-a", "series", "A", background = "provider-a", logo = "provider-logo-a"),
            MetaItem("series-b", "series", "B", background = " ", logo = ""),
            MetaItem("series-c", "series", "C", background = null, logo = null)
        )
        val enriched = mapOf(
            items[0].canonicalPresentationKey() to MetaItem(
                "series-a", "series", "A", background = " ", logo = ""
            ),
            items[1].canonicalPresentationKey() to MetaItem(
                "series-b", "series", "B", background = "tmdb-b", logo = "tmdb-logo-b"
            )
        )
        val result = artworkPrefetchRequests(
            items = items, enrichedItems = enriched, visibleIndices = listOf(0, 1, 2, 99),
            focusedItemKey = "not-present", landscapeCards = true
        )

        assertEquals(setOf("provider-a", "provider-logo-a", "tmdb-b", "tmdb-logo-b"),
            result.map(ArtworkPrefetchRequest::url).toSet())
        assertEquals(2, result.count { it.role == ArtworkPrefetchRole.Landscape })
        assertEquals(2, result.count { it.role == ArtworkPrefetchRole.Logo })
    }
}
