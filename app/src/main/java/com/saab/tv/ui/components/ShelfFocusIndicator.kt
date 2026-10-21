package com.saab.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saab.tv.ui.theme.SaabVisualTokens
import kotlin.math.roundToInt

internal val ShelfFocusIndicatorActive = SemanticsPropertyKey<Boolean>("ShelfFocusIndicatorActive")

/** Draw-only state read keeps shelf movement out of the row's composition scope. */
@Composable
internal fun ShelfFocusIndicator(
    focused: MutableState<Boolean>,
    width: Dp,
    height: Dp,
    startPadding: Dp,
    rounded: Boolean,
    listState: LazyListState? = null,
    focusedItemIndex: Int? = null,
    repeatedSectionSize: Int = 0,
    modifier: Modifier = Modifier,
    tag: String = "shelf-focus-indicator"
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pivotOffsetPx = with(density) { startPadding.toPx().roundToInt() }
    val activeFocusedItemIndex = focusedItemIndex.takeIf { focused.value }
    val selectedOffsetPx = androidx.compose.runtime.remember(listState, activeFocusedItemIndex, repeatedSectionSize, pivotOffsetPx) {
        androidx.compose.runtime.derivedStateOf {
            val layoutInfo = listState?.layoutInfo
            selectedShelfItemOffsetPx(
                visibleItems = layoutInfo?.visibleItemsInfo.orEmpty(),
                focusedItemIndex = activeFocusedItemIndex,
                pivotOffsetPx = pivotOffsetPx,
                canScrollBackward = listState?.canScrollBackward == true,
                canScrollForward = listState?.canScrollForward == true,
                totalItemCount = layoutInfo?.totalItemsCount ?: 0,
                repeatedSectionSize = repeatedSectionSize,
                itemIndex = { it.index },
                // LazyListItemInfo.offset is relative to the content origin;
                // the focus frame is positioned in the row viewport, so restore
                // the row's start inset when following a clamped card.
                itemOffset = { it.offset + pivotOffsetPx }
            )
        }
    }
    val animatedOffsetPx = animateFloatAsState(
        targetValue = selectedOffsetPx.value.toFloat(),
        animationSpec = SaabMotion.focusRetargetSpec(),
        label = "shelf-focus-frame-offset"
    )
    Canvas(
        modifier = modifier
            .padding(start = startPadding)
            // Only the edge-clamped indicator needs to leave its pivot. Apply that
            // correction in the layer so scroll-frame updates don't trigger placement.
            // Short rails and clamped edges have no list scroll to carry the frame;
            // animate that offset with the same shared focus spring instead of snapping.
            .graphicsLayer {
                translationX = animatedOffsetPx.value - pivotOffsetPx
            }
            .width(width)
            .height(height)
            .semantics { this[ShelfFocusIndicatorActive] = focused.value }
            .testTag(tag)
    ) {
        drawShelfFocusIndicator(focused.value, rounded)
    }
}

internal fun DrawScope.drawShelfFocusIndicator(
    focused: Boolean,
    rounded: Boolean
) {
    if (!focused) return
    drawShelfFocusFrame(rounded)
}

internal fun DrawScope.drawShelfFocusFrame(rounded: Boolean) {
    val stroke = SaabVisualTokens.focusOutlineWidth.toPx()
    if (rounded) {
        drawRoundRect(
            color = SaabVisualTokens.focusColor,
            cornerRadius = CornerRadius(SaabVisualTokens.focusCornerRadius.toPx()),
            style = Stroke(width = stroke)
        )
    } else {
        drawRect(color = SaabVisualTokens.focusColor, style = Stroke(width = stroke))
    }
}
