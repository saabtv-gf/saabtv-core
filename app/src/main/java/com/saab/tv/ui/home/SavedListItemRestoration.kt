package com.saab.tv.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** UI lifecycle adapter; the deterministic viewport decision lives in HomeFocusRestoration. */
@Composable
internal fun RestoreSavedListItem(listState: LazyListState, savedIndex: Int?) {
    val initialIndex = remember(listState) { savedIndex }
    LaunchedEffect(listState, initialIndex) {
        val target = initialIndex?.takeIf { it >= 0 } ?: return@LaunchedEffect
        val count = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (shouldRestoreSavedListItem(target, count, listState.layoutInfo.visibleItemsInfo.map { it.index })) {
            listState.scrollToItem(target)
        }
    }
}
