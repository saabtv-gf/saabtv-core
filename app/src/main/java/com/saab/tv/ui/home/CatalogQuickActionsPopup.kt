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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A focusable, card-centered action strip shared by grid and search cards. */
@Composable
fun CatalogQuickActionsPopup(
    item: MetaItem,
    bounds: Rect,
    profileId: Int,
    onDismiss: () -> Unit,
    onTrailerClick: (String, String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val manualTrailerLauncher = com.saab.tv.ui.trailer.LocalManualTrailerLauncher.current
    val locallyWatchedIds = com.saab.tv.ui.components.LocalWatchedIds.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val widthPx = with(density) { 180.dp.roundToPx() }
    val heightPx = with(density) { 60.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val x = (bounds.center.x.toInt() - widthPx / 2)
        .coerceIn(marginPx, (screenWidthPx - widthPx - marginPx).coerceAtLeast(marginPx))
    val y = (bounds.center.y.toInt() - heightPx / 2)
        .coerceIn(marginPx, (screenHeightPx - heightPx - marginPx).coerceAtLeast(marginPx))

    var watchlisted by remember(item.id, profileId) { mutableStateOf(false) }
    var watched by remember(item.id, profileId) { mutableStateOf(item.id in locallyWatchedIds) }
    var actionsArmed by remember(item.id) { mutableStateOf(false) }
    var suppressOpeningKeyUp by remember(item.id) { mutableStateOf(true) }
    val firstActionRequester = remember(item.id) { FocusRequester() }
    LaunchedEffect(item.id, profileId, locallyWatchedIds) {
        watchlisted = try {
            viewModel.isWatchlisted(profileId, item.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        watched = item.id in locallyWatchedIds || try {
            viewModel.isTitleWatched(profileId, item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        delay(80)
        runCatching { firstActionRequester.requestFocus() }
        delay(420)
        actionsArmed = true
    }

    Popup(alignment = Alignment.TopStart, offset = IntOffset(x, y),
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Row(Modifier.width(180.dp)
            .onPreviewKeyEvent { event ->
                if (suppressOpeningKeyUp && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    suppressOpeningKeyUp = false
                    actionsArmed = true
                    true
                } else false
            }
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
            .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardActionIcon(Icons.Default.Videocam, "Watch Trailer", onClick = {
                onDismiss()
                if (manualTrailerLauncher != null) {
                    manualTrailerLauncher(item, null, null)
                } else scope.launch {
                    val trailer = try { viewModel.trailerFor(item) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (trailer != null) onTrailerClick(trailer.first, trailer.second)
                    else android.widget.Toast.makeText(context, "No trailer available", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f), enabled = actionsArmed, focusRequester = firstActionRequester)
            CardActionIcon(Icons.Default.DoneAll,
                if (watched) "Already Watched" else "Mark As Watched", onClick = {
                    if (!watched) viewModel.markTitleWatched(profileId, item)
                    onDismiss()
                }, modifier = Modifier.weight(1f), enabled = actionsArmed && !watched,
                destructive = watched)
            CardActionIcon(if (watchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                if (watchlisted) "Remove From Watchlist" else "Add To Watchlist", onClick = {
                    viewModel.toggleWatchlist(profileId, item)
                    onDismiss()
                }, modifier = Modifier.weight(1f), enabled = actionsArmed, destructive = watchlisted)
        }
    }
}
