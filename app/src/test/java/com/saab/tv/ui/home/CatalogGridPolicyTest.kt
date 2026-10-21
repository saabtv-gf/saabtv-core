package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGridPolicyTest {
    private val movie = MetaItem("movie", "movie", "Zeta", releaseInfo = "2022-03-01", imdbRating = "7.5")
    private val series = MetaItem("series", "series", "Alpha", releaseInfo = "2024", imdbRating = "9.1")
    private val tv = MetaItem("tv", "tv", "Gamma", releaseInfo = "2023", imdbRating = "8.0")
    private val other = MetaItem("other", "movie", "Beta", releaseInfo = null, imdbRating = null)

    @Test fun featuredOrderIsUnchangedAndAllMediaKindsAreKept() {
        assertEquals(listOf(movie, series, other), applyCatalogGridOptions(
            listOf(movie, series, other), CatalogMediaFilter.ALL, CatalogGridSort.FEATURED
        ))
    }

    @Test fun mediaFilterAcceptsOnlyTheRequestedKind() {
        assertEquals(listOf(movie, other), applyCatalogGridOptions(
            listOf(movie, series, other), CatalogMediaFilter.MOVIES, CatalogGridSort.FEATURED
        ))
        assertEquals(listOf(series), applyCatalogGridOptions(
            listOf(movie, series, other), CatalogMediaFilter.SERIES, CatalogGridSort.FEATURED
        ))
        assertEquals(listOf(series, tv), applyCatalogGridOptions(
            listOf(movie, series, tv), CatalogMediaFilter.SERIES, CatalogGridSort.FEATURED
        ))
    }

    @Test fun titleNewestAndRatingSortsAreStableAndPutMissingValuesLast() {
        val items = listOf(movie, series, other)
        assertEquals(listOf(series, other, movie), applyCatalogGridOptions(items, CatalogMediaFilter.ALL, CatalogGridSort.TITLE))
        assertEquals(listOf(series, movie, other), applyCatalogGridOptions(items, CatalogMediaFilter.ALL, CatalogGridSort.NEWEST))
        assertEquals(listOf(series, movie, other), applyCatalogGridOptions(items, CatalogMediaFilter.ALL, CatalogGridSort.RATING))
    }
}

class CatalogGridPaginationGatePolicyTest {
    @Test fun requestsOnlyOncePerSourceBatchAndAllowsRetryOrAnotherPage() {
        val gate = GridPaginationGate(prefetchDistance = 4)

        assertFalse(gate.shouldLoadMore(itemCount = 20, lastVisibleIndex = 14, sourceItemCount = 40))
        assertTrue(gate.shouldLoadMore(itemCount = 20, lastVisibleIndex = 16, sourceItemCount = 40))
        assertFalse(gate.shouldLoadMore(itemCount = 20, lastVisibleIndex = 19, sourceItemCount = 40))
        // Local filter counts can stay constant while the source count grows.
        assertTrue(gate.shouldLoadMore(itemCount = 20, lastVisibleIndex = 19, sourceItemCount = 60))

        gate.reset()
        assertTrue(gate.shouldLoadMore(itemCount = 20, lastVisibleIndex = 19, sourceItemCount = 60))
    }
}
