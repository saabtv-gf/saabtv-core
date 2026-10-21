package com.saab.tv.ui.watchlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.R
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.components.LocalWatchedIds
import com.saab.tv.ui.components.EpisodePositionLabel
import com.saab.tv.ui.home.CatalogQuickAction
import com.saab.tv.ui.home.CatalogQuickActionStrip
import com.saab.tv.ui.home.HomeViewModel
import kotlinx.coroutines.CancellationException

/** Continue Watching has resume/clear semantics, unlike ordinary catalog-card quick actions. */
@Composable
internal fun LibraryContinueQuickActionsPopup(
    item: MetaItem,
    positionMs: Long,
    durationMs: Long,
    bounds: Rect,
    profileId: Int,
    seekIntervalSeconds: Int,
    onDismiss: () -> Unit,
    onResume: (MetaItem) -> Unit,
    onMutationComplete: () -> Unit,
    viewModel: HomeViewModel
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthPx = with(density) { 232.dp.roundToPx() }
    val heightDp = if (positionMs > 0L) 214.dp else 148.dp
    val heightPx = with(density) { heightDp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val x = (bounds.center.x.toInt() - widthPx / 2)
        .coerceIn(marginPx, (screenWidthPx - widthPx - marginPx).coerceAtLeast(marginPx))
    val y = (bounds.center.y.toInt() - heightPx / 2)
        .coerceIn(marginPx, (screenHeightPx - heightPx - marginPx).coerceAtLeast(marginPx))
    val watchedIds = LocalWatchedIds.current
    val firstActionRequester = remember(item.id, profileId) { FocusRequester() }
    var watched by remember(item.id, profileId) { mutableStateOf(item.id in watchedIds) }
    var watchedStateLoaded by remember(item.id, profileId) { mutableStateOf(false) }
    var armed by remember(item.id, profileId) { mutableStateOf(false) }
    var confirmClear by remember(item.id, profileId) { mutableStateOf(false) }
    var pausedFrame by remember(item.id, positionMs, profileId) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }

    LaunchedEffect(item.id, profileId, watchedIds) {
        try {
            watched = item.id in watchedIds || viewModel.isTitleWatched(profileId, item)
            watchedStateLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep watched mutations disabled until the profile's authoritative state is known.
        }
    }
    LaunchedEffect(item.id, profileId) {
        armed = true
        withFrameNanos { }
        runCatching { firstActionRequester.requestFocus() }
    }
    LaunchedEffect(confirmClear, item.id, profileId) {
        // The same strip requester is attached to Resume in the regular view and
        // the destructive confirmation action while confirming. Reattach focus
        // after either transition so Cancel never strands the TV remote focus.
        withFrameNanos { }
        runCatching { firstActionRequester.requestFocus() }
    }
    LaunchedEffect(item.id, item.resumePlaybackId, positionMs, profileId) {
        if (positionMs <= 0L) return@LaunchedEffect
        pausedFrame = try {
            viewModel.cachedPauseFrame(
                profileId,
                item.resumePlaybackId ?: item.id,
                positionMs,
                seekIntervalSeconds.coerceAtLeast(1)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x, y),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier.width(232.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                .padding(8.dp)
                .testTag("library-continue-actions"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (confirmClear) {
                Text(
                    stringResource(R.string.quick_action_clear_progress_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                CatalogQuickActionStrip(
                    actions = listOf(
                        CatalogQuickAction(
                            Icons.Default.Delete,
                            stringResource(R.string.details_action_clear_progress),
                            onClick = {
                                viewModel.clearContinueProgress(profileId, item, onMutationComplete)
                                onDismiss()
                            },
                            enabled = armed,
                            destructive = true,
                            focusRequester = firstActionRequester
                        ),
                        CatalogQuickAction(
                            Icons.Default.Close,
                            stringResource(R.string.common_cancel),
                            onClick = { confirmClear = false },
                            enabled = armed
                        )
                    )
                )
            } else {
                if (item.type.equals("series", ignoreCase = true)) {
                    val parts = item.resumePlaybackId.orEmpty().split(':')
                    val season = parts.getOrNull(parts.lastIndex - 1)?.toIntOrNull()
                    val episode = parts.lastOrNull()?.toIntOrNull()
                    if (season != null && episode != null) {
                        EpisodePositionLabel(season, episode)
                    }
                }
                if (durationMs > 0L) {
                    val remainingMinutes = (durationMs - positionMs).coerceAtLeast(0L) / 60_000
                    Text(
                        stringResource(R.string.quick_action_remaining_minutes, remainingMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
                pausedFrame?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.quick_action_paused_scene),
                        modifier = Modifier.fillMaxWidth().height(70.dp),
                        alignment = Alignment.Center,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                CatalogQuickActionStrip(
                    actions = listOf(
                        CatalogQuickAction(
                            Icons.Default.PlayArrow,
                            stringResource(R.string.details_action_resume),
                            onClick = { onDismiss(); onResume(item) },
                            enabled = armed,
                            focusRequester = firstActionRequester
                        ),
                        CatalogQuickAction(
                            Icons.Default.DoneAll,
                            stringResource(if (watched) R.string.quick_action_mark_unwatched else R.string.episode_mark_watched),
                            onClick = {
                                if (watched) viewModel.unmarkTitleWatched(profileId, item, onMutationComplete)
                                else viewModel.markTitleWatched(profileId, item, onMutationComplete)
                                onDismiss()
                            },
                            enabled = armed && watchedStateLoaded
                        ),
                        CatalogQuickAction(
                            Icons.Default.Delete,
                            stringResource(R.string.details_action_clear_progress),
                            onClick = {
                                confirmClear = true
                            },
                            enabled = armed,
                            destructive = true
                        )
                    )
                )
            }
        }
    }
}
