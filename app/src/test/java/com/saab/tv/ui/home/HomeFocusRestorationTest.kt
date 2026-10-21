package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.HubItem
import com.saab.tv.domain.HubShape
import com.saab.tv.domain.schema.CatalogCardProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class HomeFocusRestorationTest {
    @Test
    fun shelfRouteRegistryPreservesExistingTargetsWhenLateRowsArrive() {
        val registry = ShelfFocusRouteRegistry()
        val before = registry.routesFor(listOf("history", "catalog", "providers"))

        val after = registry.routesFor(listOf("late-recommendations", "history", "catalog", "providers"))

        assertSame(before.getValue("history").entry, after.getValue("history").entry)
        assertSame(before.getValue("catalog").entry, after.getValue("catalog").entry)
        assertSame(before.getValue("providers").entry, after.getValue("providers").entry)
        assertNotSame(before.getValue("history").entry, after.getValue("late-recommendations").entry)
        assertSame(after.getValue("history").entry, after.getValue("catalog").up)
        assertSame(after.getValue("providers").entry, after.getValue("catalog").down)

        val withoutHistory = registry.routesFor(listOf("late-recommendations", "catalog", "providers"))
        assertSame(withoutHistory.getValue("late-recommendations").entry, withoutHistory.getValue("catalog").up)
        assertNotSame(before.getValue("history").entry, withoutHistory.getValue("catalog").up)
        assertSame(withoutHistory.getValue("providers").entry, withoutHistory.getValue("catalog").down)
    }

    @Test
    fun emptyAndNonCatalogAddressesAreSafe() {
        assertNull(resolveEffectiveFocusKey(null, emptyList(), emptyList()))
        assertEquals("", resolveEffectiveFocusKey("", emptyList(), emptyList()))
        assertEquals("hero_tt_one", resolveEffectiveFocusKey("hero_tt_one", emptyList(), emptyList()))
        assertNull(resolveEffectiveFocusKey("not-a-focus-address", emptyList(), emptyList()))
        assertNull(resolveEffectiveFocusKey("0_missing_not-an-index", emptyList(), emptyList()))
    }

    @Test
    fun indexedFocusRestorationMatchesFirstRowAndStableShelfSemantics() {
        val rows = listOf(
            category("first-row", "shared", "first-only"),
            category("saved-row", "shared", "moved")
        )
        val index = homeFocusRestorationIndex(rows)

        assertEquals(0, index.firstCategoryRowByItemId["shared"]?.first)
        assertEquals(1, index.categoryRowsById["saved-row"]?.first)
        assertEquals(
            "1_shared_0@saved-row",
            resolveEffectiveFocusKey("1_shared_1@saved-row", emptyList(), rows, index)
        )
        assertEquals(
            "1_moved_1@saved-row",
            resolveEffectiveFocusKey("0_moved_0@first-row", emptyList(), rows, index)
        )
    }

    @Test
    fun indexedHubFocusSupportsCurrentAndLegacyAddresses() {
        val hub = HubGroupRow(
            id = "providers",
            title = "Providers",
            order = 0,
            items = listOf(HubItem("prime", "Prime", "prime"), HubItem("netflix", "Netflix", "netflix"))
        )
        val rows = listOf(category("catalog", "movie"), hub)
        val index = homeFocusRestorationIndex(rows)

        assertEquals(
            homeHubFocusKey(1, "providers", "netflix", 1),
            resolveEffectiveFocusKey(homeHubFocusKey(1, "providers", "netflix", 0), emptyList(), rows, index)
        )
        assertEquals(
            homeHubFocusKey(1, "providers", "netflix", 1),
            resolveEffectiveFocusKey("hub_providers_1", emptyList(), rows, index)
        )
    }

    @Test
    fun continueWatchingTracksReorderAndFallsBackAfterRemoval() {
        val history = listOf(item("episode_two"), item("episode_one"))

        assertEquals(
            "-1_episode_two_0@history",
            resolveEffectiveFocusKey("-1_episode_two_0", history, emptyList())
        )
        assertEquals(
            "-1_episode_one_1@history",
            resolveEffectiveFocusKey("-1_episode_one_0@history", history, emptyList())
        )
        assertEquals(
            "-1_episode_two_0@history",
            resolveEffectiveFocusKey("-1_removed_5@history", history, emptyList())
        )
        assertEquals(
            "0_fallback_0@fallback-row",
            resolveEffectiveFocusKey("-1_removed_0@history", emptyList(), listOf(category("fallback-row", "fallback")))
        )
        assertNull(resolveEffectiveFocusKey("-1_removed_0@history", emptyList(), emptyList()))
    }

    @Test
    fun shelfInsertionAndContentReorderingKeepTheSameTitleFocused() {
        val rows = listOf(
            category("inserted", "new"),
            category("same_row", "first", "target", "last")
        )

        assertEquals(
            "1_target_1@same_row",
            resolveEffectiveFocusKey("0_target_0@same_row", emptyList(), rows)
        )
        assertEquals(
            "1_target_1@same_row",
            resolveEffectiveFocusKey("0_target_0", emptyList(), rows)
        )
    }

    @Test
    fun identityShelfWinsWhenSameTitleAppearsInSeveralShelves() {
        val rows = listOf(
            category("first", "shared", "other"),
            category("saved", "shared", "tail")
        )

        assertEquals(
            "1_shared_0@saved",
            resolveEffectiveFocusKey("1_shared_1@saved", emptyList(), rows)
        )
    }

    @Test
    fun titleMovedOutOfItsShelfFollowsContentIdentity() {
        val rows = listOf(category("old-shelf", "other"), category("new-shelf", "moved"))

        assertEquals(
            "1_moved_0@new-shelf",
            resolveEffectiveFocusKey("0_moved_0@old-shelf", emptyList(), rows)
        )
    }

    @Test
    fun providerQualifiedFocusRestorationDisambiguatesDuplicateRawIdsAndReadsLegacyKeys() {
        val firstProviderTitle = providerItem("shared-provider-id", "https://first.example/manifest.json")
        val secondProviderTitle = providerItem("shared-provider-id", "https://second.example/manifest.json")
        val secondIdentity = CatalogCardProjection.from(secondProviderTitle).focusIdentityKey
        val before = listOf(categoryItems("mixed", firstProviderTitle, secondProviderTitle))
        val originalAddress = homeCardFocusKey(0, "mixed", secondIdentity, 1)

        assertNotEquals(
            CatalogCardProjection.from(firstProviderTitle).focusIdentityKey,
            secondIdentity
        )
        assertEquals(
            homeCardFocusKey(0, "mixed", secondIdentity, 1),
            resolveEffectiveFocusKey(originalAddress, emptyList(), before)
        )

        val reordered = listOf(categoryItems("mixed", secondProviderTitle, firstProviderTitle))
        assertEquals(
            homeCardFocusKey(0, "mixed", secondIdentity, 0),
            resolveEffectiveFocusKey(originalAddress, emptyList(), reordered)
        )

        // Addresses persisted by older builds contain only the provider's raw ID;
        // when duplicates exist, preserve that address's saved position if possible.
        val legacyAddress = homeCardFocusKey(0, "mixed", "shared-provider-id", 1)
        assertEquals(
            originalAddress,
            resolveEffectiveFocusKey(legacyAddress, emptyList(), before)
        )
    }

    @Test
    fun staleTitleReturnsToNearestCardInItsStableShelf() {
        val rows = listOf(
            category("above", "a"),
            category("stable shelf", "left", "right"),
            category("below", "b")
        )

        assertEquals(
            "1_right_1@stable%20shelf",
            resolveEffectiveFocusKey("0_removed_9@stable%20shelf", emptyList(), rows)
        )
    }

    @Test
    fun removedShelfFallsBackToTheNearestNonEmptyShelfOrHub() {
        val rows = listOf(
            category("above", "a0", "a1"),
            category("empty", ""),
            category("below", "b0", "b1", "b2")
        )
        assertEquals(
            "0_a1_1@above",
            resolveEffectiveFocusKey("1_removed_1@deleted", emptyList(), rows)
        )

        val hub = HubGroupRow("services", "Services", 0, listOf(HubItem("netflix", "Netflix", "netflix")))
        assertEquals(
            homeHubFocusKey(0, "services", "netflix", 0),
            resolveEffectiveFocusKey("4_removed_0", emptyList(), listOf(hub))
        )
        assertNull(resolveEffectiveFocusKey("1_removed_0", emptyList(), listOf(category("empty", ""))))
    }

    @Test
    fun viewMoreFocusSurvivesBothGridModesAndFallsBackIfGridWasRemoved() {
        val infinite = category("infinite", "a", "b", infinite = true, infiniteScroll = true)
        val finite = category("finite", "a", "b", infinite = true, infiniteScroll = false)

        assertEquals(
            "0_viewmore_17@infinite",
            resolveEffectiveFocusKey("0_viewmore_17@infinite", emptyList(), listOf(infinite))
        )
        assertEquals(
            "0_viewmore_2@finite",
            resolveEffectiveFocusKey("0_viewmore_17@finite", emptyList(), listOf(finite))
        )
        assertEquals(
            "0_a_0@linear",
            resolveEffectiveFocusKey("0_viewmore_1@linear", emptyList(), listOf(category("linear", "a")))
        )
    }

    @Test
    fun hubFocusUsesItemIdentityAndSupportsPreviousIndexOnlyAddresses() {
        val hub = HubGroupRow(
            id = "providers",
            title = "Providers",
            order = 0,
            items = listOf(
                HubItem("prime", "Prime", "prime"),
                HubItem("netflix", "Netflix", "netflix")
            ),
            shape = HubShape.HORIZONTAL
        )

        val reorderedHub = hub.copy(items = listOf(hub.items[1], hub.items[0]))
        assertEquals(
            homeHubFocusKey(0, "providers", "netflix", 0),
            resolveEffectiveFocusKey(homeHubFocusKey(0, "providers", "netflix", 1), emptyList(), listOf(reorderedHub))
        )
        assertEquals(
            homeHubFocusKey(0, "providers", "netflix", 1),
            resolveEffectiveFocusKey("hub_providers_1", emptyList(), listOf(hub))
        )
        assertEquals(
            homeHubFocusKey(0, "providers", "prime", 0),
            resolveEffectiveFocusKey(homeHubFocusKey(0, "providers", "removed", 0), emptyList(), listOf(hub))
        )
        assertEquals(
            homeHubFocusKey(0, "providers", "netflix", 1),
            resolveEffectiveFocusKey("hub_invalid-row@providers@removed@1", emptyList(), listOf(hub))
        )
        assertNull(resolveEffectiveFocusKey("hub_missing_0", emptyList(), listOf(hub)))
    }

    @Test
    fun emptyHubFallsBackToNearestNonEmptyBrowseRow() {
        val rows = listOf(
            HubGroupRow("empty-hub", "Empty", 0, emptyList()),
            category("below", "b")
        )

        assertEquals(
            "1_b_0@below",
            resolveEffectiveFocusKey(homeHubFocusKey(1, "empty-hub", "old", 0), emptyList(), rows)
        )
    }

    @Test
    fun removedHubFallsBackToTheNearestRemainingBrowseRow() {
        val rows = listOf(category("above", "a0", "a1"), category("below", "b0"))

        assertEquals(
            "1_b0_0@below",
            resolveEffectiveFocusKey(homeHubFocusKey(1, "removed-hub", "item", 1), emptyList(), rows)
        )
        assertEquals(
            homeHubFocusKey(0, "providers", "netflix", 0),
            resolveEffectiveFocusKey(
                homeHubFocusKey(0, "providers", "netflix", 1),
                emptyList(),
                listOf(HubGroupRow("providers", "Providers", 0, listOf(HubItem("netflix", "Netflix", "netflix"))))
            )
        )
    }

    @Test
    fun focusAddressParserCoversEmptyViewMoreAndLegacyHubFallbacks() {
        assertNull(
            resolveEffectiveFocusKey(
                "0_viewmore_0@empty-infinite",
                emptyList(),
                listOf(category("empty-infinite", infinite = true))
            )
        )

        val rows = listOf(
            category("not-browseable", "ignored"),
            HubGroupRow("providers", "Providers", 1, listOf(HubItem("prime", "Prime", "prime")))
        )
        assertEquals(
            homeHubFocusKey(1, "providers", "prime", 0),
            resolveEffectiveFocusKey("hub_invalid-row@providers@removed@invalid-index", emptyList(), rows)
        )
        assertEquals(
            homeHubFocusKey(1, "providers", "prime", 0),
            resolveEffectiveFocusKey("hub_providers_0", emptyList(), rows)
        )
        assertNull(resolveEffectiveFocusKey("hub_providers_not-an-index", emptyList(), rows))
        assertNull(resolveEffectiveFocusKey("hub_providers_99", emptyList(), rows))
    }

    @Test
    fun legacyHubAddressPrefersLongestMatchingHubIdAndSurvivesNonBrowseRows() {
        val short = HubGroupRow("provider", "Short", 0, listOf(HubItem("wrong", "Wrong", "wrong")))
        val long = HubGroupRow("provider_long", "Long", 1, listOf(HubItem("right", "Right", "right")))
        val rows = listOf(
            nonCatalogRow(),
            short,
            category("middle", "middle"),
            long
        )

        assertEquals(
            homeHubFocusKey(3, "provider_long", "right", 0),
            resolveEffectiveFocusKey("hub_provider_long_0", emptyList(), rows)
        )
        assertEquals(
            "2_middle_0@middle",
            resolveEffectiveFocusKey("hub_2@deleted@deleted@0", emptyList(), rows)
        )
    }

    @Test
    fun encodedRowIdentityDoesNotBreakOpaqueIdsOrDelimiterCharacters() {
        val address = homeCardFocusKey(2, "row @ 2", "tt_id_with_underscores", 4)
        assertEquals("2_tt_id_with_underscores_4@row%20%40%202", address)
        assertEquals("2_tt_id_with_underscores_0@row%20%40%202", resolveEffectiveFocusKey(address, emptyList(), listOf(
            category("other", "other"),
            category("other-two", "other"),
            category("row @ 2", "tt_id_with_underscores")
        )))
        assertEquals("2_tt_id_with_underscores_4", focusKeyWithoutRowId(address))

        val delimiterIdAddress = homeCardFocusKey(0, "row", "part@one%two", 3)
        assertEquals("0_part%40one%25two_3@row", delimiterIdAddress)
        assertEquals("0_part%40one%25two_1@row", resolveEffectiveFocusKey(
            delimiterIdAddress,
            emptyList(),
            listOf(category("row", "other", "part@one%two"))
        ))

        val plusIdAddress = homeCardFocusKey(0, "row+one", "title+id", 0)
        assertEquals("0_title%2Bid_0@row%2Bone", plusIdAddress)
        assertEquals(plusIdAddress, resolveEffectiveFocusKey(
            plusIdAddress,
            emptyList(),
            listOf(category("row+one", "title+id"))
        ))

        assertEquals(
            "0_bad%25_0@row",
            resolveEffectiveFocusKey("0_bad%_0@row", emptyList(), listOf(category("row", "bad%")))
        )
    }

    @Test
    fun lazyItemIdentitySurvivesReorderAndDisambiguatesDuplicateTitles() {
        val before = homeRowItemLazyKeys("stable shelf", listOf("movie:a", "movie:b", "movie:a"))
        val after = homeRowItemLazyKeys("stable shelf", listOf("movie:b", "movie:a", "movie:a"))

        assertEquals(before[0], after[1])
        assertEquals(before[1], after[0])
        assertNotEquals(after[1], after[2])
        assertNotEquals(before[0], homeRowItemLazyKeys("another shelf", listOf("movie:a"))[0])
    }

    @Test
    fun gridFocusFollowsContentIdentityAndClampsOnlyWhenThatIdentityIsGone() {
        assertNull(resolveFocusedItemIndex(emptyList(), "movie:a", 1))
        assertNull(resolveFocusedItemIndex(listOf("movie:a", "movie:b"), null, null))
        assertEquals(0, resolveFocusedItemIndex(listOf("movie:a", "movie:b"), "movie:a", 1))
        assertEquals(1, resolveFocusedItemIndex(listOf("movie:b", "movie:a"), "movie:a", 0))
        assertEquals(1, resolveFocusedItemIndex(listOf("movie:a", "movie:b"), "removed", 9))
        assertEquals(0, resolveFocusedItemIndex(listOf("movie:a", "movie:b"), "removed", -4))
        assertEquals(0, resolveFocusedItemIndex(listOf("movie:a", "movie:a"), "movie:a", 1))
    }

    @Test
    fun precomputedGridFocusIndexPreservesFirstDuplicateAndSavedIndexFallback() {
        val keys = listOf("movie:a", "movie:a", "movie:b")
        val indexByKey = firstIndexByKey(keys)

        assertEquals(0, indexByKey["movie:a"])
        assertEquals(2, resolveFocusedItemIndex(keys, "movie:b", 0, indexByKey))
        assertEquals(2, resolveFocusedItemIndex(keys, "removed", 9, indexByKey))
        assertEquals(0, resolveFocusedItemIndex(keys, "movie:b", 0, mapOf("movie:b" to 99)))
        assertEquals(0, resolveFocusedItemIndex(keys, "movie:b", 0, mapOf("movie:b" to -1)))
        assertNull(resolveFocusedItemIndex(keys, "removed", null, indexByKey))
    }

    @Test
    fun legacyHistoryAddressesPreserveDuplicateSlotsAndMalformedIndicesDefaultToZero() {
        val history = listOf(item("tt1234567"), item("tt1234567"))
        val identity = history.first().homeFocusIdentityKey()

        assertEquals(
            homeCardFocusKey(-1, "history", identity, 1),
            resolveEffectiveFocusKey("-1_tt1234567_1@history", history, emptyList())
        )
        assertEquals(
            homeCardFocusKey(-1, "history", identity, 0),
            resolveEffectiveFocusKey("-1_tt1234567_bad-index@history", history, emptyList())
        )
        assertEquals(
            homeCardFocusKey(-1, "history", identity, 0),
            resolveEffectiveFocusKey("-1_tt1234567_99@history", history, emptyList())
        )
        assertEquals("raw-person-id", MetaItem("raw-person-id", "person").homeFocusIdentityKey())
        assertEquals("opaque-id", MetaItem("opaque-id", "movie").homeFocusIdentityKey())
        assertEquals("", MetaItem("", "movie").homeFocusIdentityKey())
    }

    @Test
    fun legacyCatalogIdsUseTheFirstDuplicateWhenTheirSavedSlotNoLongerExists() {
        val first = providerItem("shared-provider-id", "https://first.example/manifest.json")
        val second = providerItem("shared-provider-id", "https://second.example/manifest.json")
        val row = categoryItems("mixed", first, second)
        val identity = CatalogCardProjection.from(first).focusIdentityKey

        assertEquals(
            homeCardFocusKey(0, "mixed", identity, 0),
            resolveEffectiveFocusKey(homeCardFocusKey(0, "mixed", "shared-provider-id", 99), emptyList(), listOf(row))
        )
    }

    private fun item(id: String) = MetaItem(id = id, type = "series", name = id)

    private fun providerItem(id: String, addonBaseUrl: String) = MetaItem(
        id = id,
        type = "movie",
        name = id,
        addonBaseUrl = addonBaseUrl
    )

    private fun nonCatalogRow() = object : com.saab.tv.domain.HomeRowItem {
        override val id = "hero"
        override val title = "Hero"
        override val order = 0
    }

    private fun category(
        id: String,
        vararg itemIds: String,
        infinite: Boolean = false,
        infiniteScroll: Boolean = true
    ) = CategoryRow(
        id = id,
        title = id,
        order = 0,
        items = itemIds.filter(String::isNotEmpty).map { CatalogCardProjection.from(item(it)) },
        isInfiniteLoopEnabled = infinite,
        visibleItemCount = 15,
        isInfiniteScrollingEnabled = infiniteScroll
    )

    private fun categoryItems(id: String, vararg items: MetaItem) = CategoryRow(
        id = id,
        title = id,
        order = 0,
        items = items.map(CatalogCardProjection::from)
    )
}
