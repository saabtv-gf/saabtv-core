package com.saab.tv.ui.trailer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.saab.tv.data.model.stremio.MetaItem

/** The host owns the single decoder; the preview overlay attaches its view. */
internal class InlineTrailerSession(
    val owner: Any, val item: MetaItem, val player: ExoPlayer,
    val onInteraction: () -> Unit, val onMute: () -> Unit, val onWatch: () -> Unit,
    val onWatchlist: () -> Unit, val onFullscreen: () -> Unit, val onDismiss: () -> Unit,
    val onNavigate: (FocusDirection) -> Unit,
    val onEpisodes: (() -> Unit)? = null
) {
    var muted by mutableStateOf(true)
    var watchlisted by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
}

@Composable
internal fun InlineTrailerCard(session: InlineTrailerSession) {
    val rootRequester = remember(session) { FocusRequester() }
    val playRequester = remember(session) { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var revealingKey by remember { mutableStateOf<Key?>(null) }
    fun moveToTitle(direction: FocusDirection) {
        session.onNavigate(direction)
    }
    LaunchedEffect(session, session.controlsVisible) {
        withFrameNanos { }
        runCatching {
            if (session.controlsVisible) playRequester.requestFocus() else rootRequester.requestFocus()
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF151515))
        .onPreviewKeyEvent { event ->
            if (event.key == Key.Back || event.key == Key.Escape) {
                if (event.type == KeyEventType.KeyUp) session.onDismiss()
                return@onPreviewKeyEvent true
            }
            if (event.key == revealingKey) {
                if (event.type == KeyEventType.KeyUp) revealingKey = null
                return@onPreviewKeyEvent true
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val direction = when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> null
            }
            if (direction != null && (rootFocused || event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                moveToTitle(direction)
                return@onPreviewKeyEvent true
            }
            if (!session.controlsVisible || rootFocused) {
                session.onInteraction(); revealingKey = event.key
                return@onPreviewKeyEvent true
            }
            session.onInteraction()
            false // OK down/up belongs to the focused control, never the poster.
        }.focusRequester(rootRequester).onFocusChanged { rootFocused = it.isFocused }.focusable()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) { TrailerVideoSurface(session.player) }
        Column(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text(session.item.name, color = Color.White,
                style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (session.controlsVisible) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    InlineAction(if (session.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        if (session.muted) "Unmute" else "Mute", session.onMute,
                        Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                                moveToTitle(FocusDirection.Left); true
                            } else false
                        })
                    InlineAction(Icons.Default.PlayArrow, "Start Watching", session.onWatch,
                        Modifier.focusRequester(playRequester))
                    InlineAction(if (session.watchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        if (session.watchlisted) "Remove From Watchlist" else "Add To Watchlist", session.onWatchlist)
                    InlineAction(Icons.Default.Fullscreen, "Fullscreen", session.onFullscreen,
                        Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                                moveToTitle(FocusDirection.Right); true
                            } else false
                        })
                }
            }
        }
    }
}

@Composable
private fun InlineAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.saab.tv.ui.components.DetailActionButton(label, icon, onClick, modifier,
        maxExpandedWidth = 160.dp)
}
