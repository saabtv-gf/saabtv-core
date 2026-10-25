package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.HomeRowItem
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.domain.schema.canonicalContentIdentity
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Stable enough to survive shelf insertion/reordering while keeping the row's prior address. */
internal fun homeCardFocusKey(rowIndex: Int, rowId: String, itemId: String, itemIndex: Int): String =
    "${rowIndex}_${encodeFocusToken(itemId)}_$itemIndex@${encodeFocusToken(rowId)}"

internal fun homeHubFocusKey(rowIndex: Int, groupId: String, itemId: String, itemIndex: Int): String =
    "hub_${rowIndex}@${encodeFocusToken(groupId)}@${encodeFocusToken(itemId)}@$itemIndex"

internal fun homeRowItemLazyKeys(rowId: String, itemIds: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return itemIds.map { itemId ->
        val occurrence = occurrences.getOrDefault(itemId, 0)
        occurrences[itemId] = occurrence + 1
        "row:${encodeFocusToken(rowId)}@item:${encodeFocusToken(itemId)}#$occurrence"
    }
}

internal fun firstIndexByKey(itemKeys: List<String>): Map<String, Int> = buildMap(itemKeys.size) {
    itemKeys.forEachIndexed { index, key ->
        if (key !in this) put(key, index)
    }
}

internal data class HomeFocusRestorationIndex(
    val categoryRowsById: Map<String, Pair<Int, CategoryRow>>,
    val firstCategoryRowByPresentationKey: Map<String, Pair<Int, CategoryRow>>,
    val firstCategoryRowByItemId: Map<String, Pair<Int, CategoryRow>>,
    val hubRowsById: Map<String, Pair<Int, HubGroupRow>>,
    val legacyHubRows: List<Pair<Int, HubGroupRow>>
)

/** Builds the browse lookup once per row update; focus moves then touch only their matching shelf. */
internal fun homeFocusRestorationIndex(rows: List<HomeRowItem>): HomeFocusRestorationIndex {
    val categoriesById = linkedMapOf<String, Pair<Int, CategoryRow>>()
    val firstCategoryByPresentationKey = linkedMapOf<String, Pair<Int, CategoryRow>>()
    val firstCategoryByItemId = linkedMapOf<String, Pair<Int, CategoryRow>>()
    val hubsById = linkedMapOf<String, Pair<Int, HubGroupRow>>()
    val legacyHubs = mutableListOf<Pair<Int, HubGroupRow>>()

    rows.forEachIndexed { index, row ->
        when (row) {
            is CategoryRow -> {
                val location = index to row
                categoriesById.putIfAbsent(row.id, location)
                row.items.forEach { projection ->
                    firstCategoryByPresentationKey.putIfAbsent(projection.focusIdentityKey, location)
                    firstCategoryByItemId.putIfAbsent(projection.asActionItem().id, location)
                }
            }
            is HubGroupRow -> {
                val location = index to row
                hubsById.putIfAbsent(row.id, location)
                legacyHubs += location
            }
            else -> Unit
        }
    }

    return HomeFocusRestorationIndex(
        categoryRowsById = categoriesById,
        firstCategoryRowByPresentationKey = firstCategoryByPresentationKey,
        firstCategoryRowByItemId = firstCategoryByItemId,
        hubRowsById = hubsById,
        legacyHubRows = legacyHubs.sortedByDescending { "hub_${it.second.id}_".length }
    )
}

internal fun resolveFocusedItemIndex(
    itemKeys: List<String>,
    savedItemKey: String?,
    savedIndex: Int?,
    itemIndexByKey: Map<String, Int>? = null
): Int? {
    if (itemKeys.isEmpty()) return null
    val identityIndex = savedItemKey?.let { key ->
        (itemIndexByKey ?: firstIndexByKey(itemKeys))[key]
    }?.takeIf { it in itemKeys.indices }
    return identityIndex ?: savedIndex?.coerceIn(itemKeys.indices)
}

internal fun focusKeyWithoutRowId(key: String): String = key.substringBefore('@')

internal fun shouldRestoreSavedListItem(target: Int, itemCount: Int, visibleIndices: List<Int>): Boolean =
    target in 0 until itemCount && target !in visibleIndices

internal fun homeShelfListIndex(key: String?, hasHistory: Boolean, leadingItems: Int = 0): Int? {
    val address = key?.takeUnless { it.startsWith("hero_") } ?: return null
    val rowIndex = if (address.startsWith("hub_")) address.removePrefix("hub_").substringBefore('@').toIntOrNull()
        else address.substringBefore('_').toIntOrNull()
    return rowIndex?.takeIf { it >= -1 }?.let {
        leadingItems + if (it == -1) 0 else it + if (hasHistory) 1 else 0
    }
}

internal fun decodeFocusToken(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
        .getOrDefault(value)

internal fun resolveEffectiveFocusKey(
    lastFocusedKey: String?,
    historyItems: List<MetaItem>,
    mixedRows: List<HomeRowItem>,
    index: HomeFocusRestorationIndex = homeFocusRestorationIndex(mixedRows)
): String? {
    if (lastFocusedKey.isNullOrEmpty()) return lastFocusedKey
    if (lastFocusedKey.startsWith("hub_")) return resolveHubFocusKey(lastFocusedKey, mixedRows, index)
    val key = focusKeyWithoutRowId(lastFocusedKey)

    if (key.startsWith("-1_")) {
        val body = key.substringAfter('_', missingDelimiterValue = "")
        val itemIdentity = decodeFocusToken(body.substringBeforeLast('_', missingDelimiterValue = ""))
        val savedIndex = body.substringAfterLast('_').toIntOrNull() ?: 0
        val currentIndex = resolveMetaItemIndex(historyItems, itemIdentity, savedIndex)
        if (currentIndex < 0) {
            val firstHistoryItem = historyItems.firstOrNull()
            return if (firstHistoryItem != null) {
                homeCardFocusKey(-1, "history", firstHistoryItem.homeFocusIdentityKey(), 0)
            } else {
                firstAvailableRowFocusKey(mixedRows, 0)
            }
        }
        return homeCardFocusKey(-1, "history", historyItems[currentIndex].homeFocusIdentityKey(), currentIndex)
    }

    if (key.startsWith("hero_")) return lastFocusedKey

    val rowIndex = key.substringBefore('_').toIntOrNull() ?: return null
    val body = key.substringAfter('_', missingDelimiterValue = "")
    val itemId = decodeFocusToken(body.substringBeforeLast('_', missingDelimiterValue = ""))
    val savedIndex = body.substringAfterLast('_').toIntOrNull() ?: return null
    val savedRowId = lastFocusedKey.substringAfter('@', missingDelimiterValue = "")
        .takeIf(String::isNotEmpty)
        ?.let(::decodeFocusToken)

    val identityRow = savedRowId?.let(index.categoryRowsById::get)
    val contentMatch = index.firstCategoryRowByPresentationKey[itemId]
        ?: index.firstCategoryRowByItemId[itemId]
    val targetRow = when {
        identityRow != null && identityRow.second.items.any { it.matchesFocusIdentity(itemId) } -> identityRow
        contentMatch != null -> contentMatch
        else -> identityRow ?: (mixedRows.getOrNull(rowIndex) as? CategoryRow)?.let { rowIndex to it }
    }

    if (targetRow != null && itemId == "viewmore" && targetRow.second.isInfiniteLoopEnabled) {
        val row = targetRow.second
        val itemCount = row.items.size.coerceAtMost(row.visibleItemCount.coerceIn(5, 50))
        if (row.items.isNotEmpty()) {
            val viewMoreIndex = if (row.isInfiniteScrollingEnabled) savedIndex else itemCount
            return "${targetRow.first}_viewmore_$viewMoreIndex@${encodeFocusToken(row.id)}"
        }
    }

    if (contentMatch != null) {
        val sameShelfMatch = identityRow?.takeIf { (_, row) ->
            row.items.any { it.matchesFocusIdentity(itemId) }
        }
        val (currentRowIndex, row) = sameShelfMatch ?: contentMatch
        val currentItemIndex = resolveProjectionIndex(row.items, itemId, savedIndex)
        return homeCardFocusKey(currentRowIndex, row.id, row.items[currentItemIndex].focusIdentityKey, currentItemIndex)
    }

    val preferredRow = targetRow?.takeIf { it.second.items.isNotEmpty() }
    if (preferredRow != null) {
        val fallbackIndex = savedIndex.coerceIn(0, preferredRow.second.items.lastIndex)
        val fallbackItem = preferredRow.second.items[fallbackIndex]
        return homeCardFocusKey(preferredRow.first, preferredRow.second.id, fallbackItem.focusIdentityKey, fallbackIndex)
    }

    return firstAvailableRowFocusKey(mixedRows, rowIndex, savedIndex)
}

private fun resolveHubFocusKey(
    key: String,
    mixedRows: List<HomeRowItem>,
    index: HomeFocusRestorationIndex
): String? {
    val address = key.removePrefix("hub_").split('@')
    if (address.size == 4) {
        val previousRowIndex = address[0].toIntOrNull() ?: 0
        val groupId = decodeFocusToken(address[1])
        val itemId = decodeFocusToken(address[2])
        val savedIndex = address[3].toIntOrNull() ?: 0
        val (rowIndex, hub) = index.hubRowsById[groupId]
            ?: return firstAvailableRowFocusKey(mixedRows, previousRowIndex, savedIndex)
        if (hub.items.isEmpty()) return firstAvailableRowFocusKey(mixedRows, rowIndex, savedIndex)
        val currentIndex = hub.items.indexOfFirst { it.id == itemId }
        val resolvedIndex = if (currentIndex >= 0) currentIndex else savedIndex.coerceIn(0, hub.items.lastIndex)
        return homeHubFocusKey(rowIndex, hub.id, hub.items[resolvedIndex].id, resolvedIndex)
    }

    // Older in-memory focus keys stored only a hub's item index.
    val legacy = index.legacyHubRows
        .firstNotNullOfOrNull { (rowIndex, hub) ->
            val itemIndex = key.removePrefix("hub_${hub.id}_").takeIf { key.startsWith("hub_${hub.id}_") }
                ?.toIntOrNull() ?: return@firstNotNullOfOrNull null
            hub.items.getOrNull(itemIndex)?.let { rowIndex to (hub to (itemIndex to it)) }
        }
        ?: return null
    val (rowIndex, legacyAddress) = legacy
    val (hub, itemAddress) = legacyAddress
    val (index, item) = itemAddress
    return homeHubFocusKey(rowIndex, hub.id, item.id, index)
}

private fun encodeFocusToken(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun firstAvailableRowFocusKey(
    rows: List<HomeRowItem>,
    preferredRowIndex: Int,
    preferredItemIndex: Int = 0
): String? {
    val entry = rows.withIndex()
        .filter { (_, row) ->
            when (row) {
                is CategoryRow -> row.items.isNotEmpty()
                is HubGroupRow -> row.items.isNotEmpty()
                else -> false
            }
        }
        .minByOrNull { kotlin.math.abs(it.index - preferredRowIndex) }
        ?: return null
    return when (val row = entry.value) {
        is CategoryRow -> {
            val index = preferredItemIndex.coerceIn(0, row.items.lastIndex)
            homeCardFocusKey(entry.index, row.id, row.items[index].focusIdentityKey, index)
        }
        else -> {
            val hub = row as HubGroupRow // The candidate filter above admits only browseable categories and hubs.
            val index = preferredItemIndex.coerceIn(0, hub.items.lastIndex)
            homeHubFocusKey(entry.index, hub.id, hub.items[index].id, index)
        }
    }
}

private fun CatalogCardProjection.matchesFocusIdentity(identity: String): Boolean =
    focusIdentityKey == identity || asActionItem().id == identity

/** Canonical addresses disambiguate providers; legacy raw IDs retain their saved index when duplicated. */
private fun resolveProjectionIndex(
    items: List<CatalogCardProjection>,
    identity: String,
    savedIndex: Int
): Int {
    val canonicalIndex = items.indexOfFirst { it.focusIdentityKey == identity }
    if (canonicalIndex >= 0) return canonicalIndex
    val legacyMatches = items.indices.filter { items[it].asActionItem().id == identity }
    // This helper is reached only after the indexed legacy-ID lookup found this identity.
    return legacyMatches.firstOrNull { it == savedIndex } ?: legacyMatches.first()
}

private fun resolveMetaItemIndex(items: List<MetaItem>, identity: String, savedIndex: Int): Int {
    val canonicalIndex = items.indexOfFirst { it.homeFocusIdentityKey() == identity }
    if (canonicalIndex >= 0) return canonicalIndex
    val legacyMatches = items.indices.filter { items[it].id == identity }
    return legacyMatches.firstOrNull { it == savedIndex } ?: legacyMatches.firstOrNull() ?: -1
}

internal fun MetaItem.homeFocusIdentityKey(): String {
    val identity = canonicalContentIdentity() ?: return id
    return identity.stableKey
}
