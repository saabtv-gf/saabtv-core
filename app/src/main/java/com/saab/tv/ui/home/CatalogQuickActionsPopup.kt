package com.saab.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import com.saab.tv.R
import com.saab.tv.data.model.stremio.MetaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

/** A focusable, card-centered action strip shared by grid and search cards. */
@Composable
fun CatalogQuickActionsPopup(
    item: MetaItem,
    bounds: Rect,
    profileId: Int,
    onDismiss: () -> Unit,
    onTrailerClick: (String, String) -> Unit,
    onLibraryMutationComplete: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val manualTrailerLauncher = com.saab.tv.ui.trailer.LocalManualTrailerLauncher.current
    val locallyWatchedIds = com.saab.tv.ui.components.LocalWatchedIds.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val watchTrailerLabel = stringResource(R.string.quick_action_watch_trailer)
    val markWatchedLabel = stringResource(R.string.episode_mark_watched)
    val markUnwatchedLabel = stringResource(R.string.quick_action_mark_unwatched)
    val addWatchlistLabel = stringResource(R.string.details_action_add_watchlist)
    val removeWatchlistLabel = stringResource(R.string.quick_action_remove_watchlist)
    val noTrailerMessage = stringResource(R.string.quick_action_no_trailer)
    val scope = rememberCoroutineScope()
    val localWatchlistIds = com.saab.tv.ui.components.LocalWatchlistIds.current
    val widthPx = with(density) { 180.dp.roundToPx() }
    val heightPx = with(density) { 60.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val x = (bounds.center.x.toInt() - widthPx / 2)
        .coerceIn(marginPx, (screenWidthPx - widthPx - marginPx).coerceAtLeast(marginPx))
    val y = (bounds.center.y.toInt() - heightPx / 2)
        .coerceIn(marginPx, (screenHeightPx - heightPx - marginPx).coerceAtLeast(marginPx))

    var watchlisted by remember(item.id, profileId) {
        mutableStateOf(item.id in localWatchlistIds)
    }
    var watched by remember(item.id, profileId) { mutableStateOf(item.id in locallyWatchedIds) }
    var watchlistStateLoaded by remember(item.id, profileId) { mutableStateOf(false) }
    var watchedStateLoaded by remember(item.id, profileId) { mutableStateOf(false) }
    val firstActionRequester = remember(item.id) { FocusRequester() }
    var actionsArmed by remember(item.id, profileId) { mutableStateOf(false) }
    LaunchedEffect(item.id, profileId) {
        actionsArmed = true
        withFrameNanos { }
        runCatching { firstActionRequester.requestFocus() }
    }
    LaunchedEffect(item.id in localWatchlistIds) {
        // Positive local membership is safe to render immediately. Absence is not
        // authoritative until the profile-scoped database query has completed.
        if (item.id in localWatchlistIds) watchlisted = true
    }
    LaunchedEffect(item.id, profileId) {
        try {
            watchlisted = viewModel.isWatchlisted(profileId, item.id)
            watchlistStateLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the mutating action disabled rather than risk toggling the
            // wrong state when membership could not be read.
        }
    }
    LaunchedEffect(item.id, profileId, locallyWatchedIds) {
        try {
            watched = item.id in locallyWatchedIds || viewModel.isTitleWatched(profileId, item)
            watchedStateLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Marking/unmarking is stateful too; fail closed until a later retry.
        }
    }

    Popup(alignment = Alignment.TopStart, offset = IntOffset(x, y),
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        androidx.compose.foundation.layout.Column(Modifier.width(180.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        ) {
            CatalogQuickActionStrip(
                actions = listOf(
                    CatalogQuickAction(Icons.Default.Videocam, watchTrailerLabel, onClick = {
                        onDismiss()
                        if (manualTrailerLauncher != null) {
                            manualTrailerLauncher(item, null, null, null)
                        } else scope.launch {
                            val trailer = try { viewModel.trailerFor(item) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            if (trailer != null) onTrailerClick(trailer.first, trailer.second)
                            else android.widget.Toast.makeText(context, noTrailerMessage, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, enabled = actionsArmed, focusRequester = firstActionRequester),
                    CatalogQuickAction(
                        Icons.Default.DoneAll,
                        if (watched) markUnwatchedLabel else markWatchedLabel,
                        onClick = {
                            if (watched) viewModel.unmarkTitleWatched(
                                profileId, item, onLocalMutationCommitted = onLibraryMutationComplete
                            ) else viewModel.markTitleWatched(
                                profileId, item, onLocalMutationCommitted = onLibraryMutationComplete
                            )
                            onDismiss()
                        },
                        enabled = actionsArmed && watchedStateLoaded
                    ),
                    CatalogQuickAction(
                        if (watchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        if (watchlisted) removeWatchlistLabel else addWatchlistLabel,
                        onClick = {
                            viewModel.toggleWatchlist(profileId, item, onLibraryMutationComplete)
                            onDismiss()
                        },
                        enabled = actionsArmed && watchlistStateLoaded,
                        destructive = watchlisted
                    )
                )
            )
        }
    }
}
