package com.saab.tv.ui.home

import com.saab.tv.ui.components.FocusPivotSpec

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.HubItem
import com.saab.tv.ui.components.FocusShelf
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.navigation.handoffNavigationFocus
import com.saab.tv.ui.theme.LocalHubRoundCorners

/**
 * ============================================================================
 * HUB ROW - Container for Hub Cards
 * ============================================================================
 * 
 * A horizontal scrolling row that displays Hub Cards for category navigation.
 * Matches the pivot/padding behavior of movie rows for consistent UX.
 * 
 * Features:
 * - LazyRow with smooth horizontal scrolling
 * - Title header above cards
 * - Configurable shape passed to all child HubCards
 * - Focus-aware scrolling with pivot alignment
 * ============================================================================
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HubRow(
    hubGroup: HubGroupRow,
    startPadding: Dp,
    onHubClick: (HubItem) -> Unit,
    onFocused: ((HubItem, String) -> Unit)? = null,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isTopNav: Boolean,
    rowIndex: Int,
    isFirstRow: Boolean = false,
    isLastRow: Boolean = false,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isGlobalFocusPresent: Boolean = false
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val density = LocalDensity.current
    val paddingPx = remember(density, startPadding) { with(density) { startPadding.toPx() } }

    // Use external state if provided, otherwise create local state
    val internalListState = rememberLazyListState()
    val listState = externalListState ?: internalListState
    val cardWidth = hubCardWidth(hubGroup.shape)
    val cardHeight = cardWidth / hubGroup.shape.aspectRatio
    val rowHasCardFocus = remember(listState) { mutableStateOf(false) }
    var focusedItemIndex by remember(listState) { mutableIntStateOf(0) }
    
    // Detect if this is a restoration (coming back from details screen)
    val isRestoration = remember(locallyFocusedItemId, rowIndex, externalListState) {
        locallyFocusedItemId != null && externalListState != null
    }
    
    // Match catalog shelves: preserve the restored position until the user moves
    // with the D-pad, then let the shared bring-into-view motion own scrolling.
    var skipBringIntoViewScroll by remember(isRestoration) { mutableStateOf(isRestoration) }
    
    // Use the shared no-bounce spring; only the hub's leading anchor differs.
    val pivotSpec = remember(paddingPx, isRestoration) {
        FocusPivotSpec(
            customOffset = paddingPx,
            skipScrollProvider = { skipBringIntoViewScroll }
        ) 
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .graphicsLayer { clip = false }
            .onFocusChanged { if (!it.hasFocus) rowHasCardFocus.value = false }
    ) {
        // Row Title
        Text(
            text = hubGroup.title,
            color = Color.White.copy(0.9f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = startPadding, bottom = 12.dp)
        )
        
        // Hub Cards Row
        FocusShelf(
            state = listState,
            focused = rowHasCardFocus,
            width = cardWidth,
            height = cardHeight,
            startPadding = startPadding,
            focusedItemIndex = focusedItemIndex,
            tag = "hub-row-focus-indicator",
            rounded = LocalHubRoundCorners.current,
            viewportHeight = cardHeight,
            indicatorAlignment = Alignment.TopStart,
            pivotSpec = pivotSpec,
            contentPadding = PaddingValues(start = startPadding),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            rowModifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusGroup()
                .graphicsLayer { clip = false }
        ) {
            itemsIndexed(
                items = hubGroup.items,
                key = { index, item -> "hub_${hubGroup.id}_${item.id}_$index" }
            ) { index, hubItem ->
                val isFirstItem = index == 0
                val isLastItem = index == hubGroup.items.lastIndex
                val uniqueKey = homeHubFocusKey(rowIndex, hubGroup.id, hubItem.id, index)
                val shouldRequestFocus = locallyFocusedItemId == uniqueKey ||
                    (!isGlobalFocusPresent && isFirstRow && isFirstItem)
                val focusModifier = Modifier
                    .then(if (shouldRequestFocus) Modifier.focusRequester(entryRequester) else Modifier)
                    .then(
                        if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) {
                            Modifier.focusRequester(pivotFocusRequester)
                        } else Modifier
                    )
                Box(
                    modifier = Modifier
                        .graphicsLayer { clip = false }
                        .onPreviewKeyEvent { keyEvent ->
                            if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                            when (keyEvent.key) {
                                Key.DirectionLeft,
                                Key.DirectionRight,
                                Key.DirectionUp,
                                Key.DirectionDown -> skipBringIntoViewScroll = false
                                else -> Unit
                            }

                            when (keyEvent.key) {
                                Key.DirectionRight -> isTopNav && isLastItem
                                Key.DirectionLeft -> {
                                    if (isFirstItem) {
                                        if (!isTopNav) handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                        else true
                                    } else false
                                }
                                Key.DirectionUp -> if (isTopNav && isFirstRow) {
                                    handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                } else false
                                else -> false
                            }
                        }
                ) {
                    HubCard(
                        hubItem = hubItem,
                        shape = hubGroup.shape,
                        onClick = { onHubClick(hubItem) },
                        onFocused = {
                            rowHasCardFocus.value = true
                            focusedItemIndex = index
                            onFocused?.invoke(hubItem, uniqueKey)
                        },
                        modifier = focusModifier,
                        showFocusBorder = false
                    )
                }
            }
        }
        
        // Bottom spacing - matches standard row height reduction (210dp -> 193dp = 17dp delta)
        // Standard rows are spaced by 15dp in LazyColumn, hub rows use internal spacer to mimic this visual gap + row height difference
        val bottomSpacerHeight = if (isTopNav && isLastRow) 0.dp else 17.dp
        Spacer(modifier = Modifier.height(bottomSpacerHeight))
    }
}
