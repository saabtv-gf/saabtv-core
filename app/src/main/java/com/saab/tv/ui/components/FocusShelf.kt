package com.saab.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.saab.tv.ui.theme.LocalRoundCorners

/** One focus-pivot, row viewport and animated frame contract for title shelves. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusShelf(
    state: LazyListState,
    focused: MutableState<Boolean>,
    width: Dp,
    height: Dp,
    startPadding: Dp,
    focusedItemIndex: Int?,
    repeatedSectionSize: Int = 0,
    tag: String,
    modifier: Modifier = Modifier,
    rounded: Boolean? = null,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    contentPadding: PaddingValues = PaddingValues(start = startPadding, top = 6.dp, bottom = 6.dp),
    viewportHeight: Dp = height + 12.dp,
    indicatorAlignment: Alignment = Alignment.CenterStart,
    showIndicator: Boolean = true,
    pivotSpec: BringIntoViewSpec? = null,
    rowModifier: Modifier = Modifier.fillMaxSize().focusGroup(),
    content: LazyListScope.() -> Unit
) {
    FocusShelfFrame(
        state = state,
        focused = focused,
        width = width,
        height = height,
        startPadding = startPadding,
        focusedItemIndex = focusedItemIndex,
        repeatedSectionSize = repeatedSectionSize,
        tag = tag,
        modifier = modifier,
        rounded = rounded,
        viewportHeight = viewportHeight,
        indicatorAlignment = indicatorAlignment,
        showIndicator = showIndicator,
        pivotSpec = pivotSpec
    ) {
        LazyRow(
            state = state,
            modifier = rowModifier,
            horizontalArrangement = horizontalArrangement,
            contentPadding = contentPadding,
            content = content
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusShelfFrame(
    state: LazyListState,
    focused: MutableState<Boolean>,
    width: Dp,
    height: Dp,
    startPadding: Dp,
    focusedItemIndex: Int?,
    repeatedSectionSize: Int = 0,
    tag: String,
    modifier: Modifier = Modifier,
    rounded: Boolean? = null,
    viewportHeight: Dp = height + 12.dp,
    indicatorAlignment: Alignment = Alignment.CenterStart,
    showIndicator: Boolean = true,
    pivotSpec: BringIntoViewSpec? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val pivotOffsetPx = remember(density, startPadding) { with(density) { startPadding.toPx() } }
    val resolvedPivotSpec = pivotSpec ?: remember(pivotOffsetPx) {
        FocusPivotSpec(customOffset = pivotOffsetPx)
    }
    val resolvedRounded = rounded ?: LocalRoundCorners.current

    CompositionLocalProvider(LocalBringIntoViewSpec provides resolvedPivotSpec) {
        Box(modifier.fillMaxWidth().height(viewportHeight)) {
            content()
            if (showIndicator) {
                ShelfFocusIndicator(
                    focused = focused,
                    width = width,
                    height = height,
                    startPadding = startPadding,
                    rounded = resolvedRounded,
                    listState = state,
                    focusedItemIndex = focusedItemIndex,
                    repeatedSectionSize = repeatedSectionSize,
                    modifier = Modifier.align(indicatorAlignment).zIndex(20f),
                    tag = tag
                )
            }
        }
    }
}
