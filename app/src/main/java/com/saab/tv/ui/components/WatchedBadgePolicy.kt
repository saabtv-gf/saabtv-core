package com.saab.tv.ui.components

/** Adds same-session watched IDs and normalizes episode IDs to their series ID. */
internal fun mergeWatchedIds(persistedIds: Iterable<String>, optimisticIds: Set<String>): Set<String> =
    persistedIds.map { id ->
        if (id.count { it == ':' } >= 2) id.substringBefore(':') else id
    }.toSet() + optimisticIds

internal fun mergeProfileWatchedIds(
    profileId: Int,
    persistedIds: Iterable<String>,
    optimisticIdsByProfile: Map<Int, Set<String>>
): Set<String> = mergeWatchedIds(persistedIds, optimisticIdsByProfile[profileId].orEmpty())
