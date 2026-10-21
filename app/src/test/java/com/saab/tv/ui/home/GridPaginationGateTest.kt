package com.saab.tv.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridPaginationGateTest {
    @Test fun requestsOnlyOnceForEachNearEndItemBatch() {
        val gate = GridPaginationGate(prefetchDistance = 3)

        assertFalse(gate.shouldLoadMore(itemCount = 10, lastVisibleIndex = 6))
        assertTrue(gate.shouldLoadMore(itemCount = 10, lastVisibleIndex = 7))
        assertFalse(gate.shouldLoadMore(itemCount = 10, lastVisibleIndex = 9))

        assertTrue(gate.shouldLoadMore(itemCount = 11, lastVisibleIndex = 9))
        assertFalse(gate.shouldLoadMore(itemCount = 11, lastVisibleIndex = 10))
    }

    @Test fun scrollingAwayAllowsRetryForTheSameBatch() {
        val gate = GridPaginationGate(prefetchDistance = 3)

        assertTrue(gate.shouldLoadMore(itemCount = 10, lastVisibleIndex = 8))
        assertFalse(gate.shouldLoadMore(itemCount = 10, lastVisibleIndex = 6))
        assertTrue(gate.shouldLoadMore(itemCount = 10, lastVisibleIndex = 8))
    }

    @Test fun emptyGridNeverRequestsMore() {
        val gate = GridPaginationGate()

        assertFalse(gate.shouldLoadMore(itemCount = 0, lastVisibleIndex = -1))
    }
}
