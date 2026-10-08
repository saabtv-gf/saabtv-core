package com.saab.tv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedBadgePolicyTest {
    @Test fun explicitWatchedStateShowsBadge() {
        assertTrue(shouldShowWatchedBadge(explicitWatched = true, itemId = "tt1", watchedIds = emptySet()))
    }

    @Test fun sharedWatchedIdsShowBadgeEvenWhenCardCallerOmitsFlag() {
        assertTrue(shouldShowWatchedBadge(explicitWatched = false, itemId = "tt1", watchedIds = setOf("tt1")))
    }

    @Test fun continueWatchingCanSuppressWatchedBadgeForGloballyWatchedTitle() {
        assertFalse(shouldShowWatchedBadge(
            explicitWatched = true,
            itemId = "tt1",
            watchedIds = setOf("tt1"),
            enabled = false
        ))
    }

    @Test fun unwatchedAndMissingIdsDoNotShowBadge() {
        assertFalse(shouldShowWatchedBadge(explicitWatched = false, itemId = "tt2", watchedIds = setOf("tt1")))
        assertFalse(shouldShowWatchedBadge(explicitWatched = false, itemId = null, watchedIds = setOf("tt1")))
    }

    @Test fun optimisticWatchActionImmediatelyIncludesOriginalCardId() {
        val ids = mergeWatchedIds(persistedIds = listOf("tt1", "tt2:1:4"), optimisticIds = setOf("tmdb:42"))

        assertTrue(ids.containsAll(setOf("tt1", "tt2", "tmdb:42")))
    }

    @Test fun optimisticIdsRemainProfileScopedByCaller() {
        val optimistic = mapOf(1 to setOf("tt1"))
        val profileOneIds = mergeProfileWatchedIds(1, emptyList(), optimistic)
        val profileTwoIds = mergeProfileWatchedIds(2, emptyList(), optimistic)

        assertTrue(shouldShowWatchedBadge(false, "tt1", profileOneIds))
        assertFalse(shouldShowWatchedBadge(false, "tt1", profileTwoIds))
    }
}
