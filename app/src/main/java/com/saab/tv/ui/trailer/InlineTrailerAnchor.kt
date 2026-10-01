package com.saab.tv.ui.trailer

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.saab.tv.data.model.stremio.MetaItem

/** Only the focused poster is retained; no per-catalog decoder or anchor cache. */
object InlineTrailerAnchor {
    var key by mutableStateOf<String?>(null)
        private set
    var bounds by mutableStateOf<Rect?>(null)
        private set
    internal var session by mutableStateOf<InlineTrailerSession?>(null)
    fun clearSession(owner: Any) {
        if (session?.owner === owner) session = null
    }
    fun update(item: MetaItem, rect: Rect) {
        if (rect.width > 0 && rect.height > 0) {
            key = "${item.type}:${item.id}"
            bounds = rect
        }
    }
    fun clearAnchor(item: MetaItem, rect: Rect?) {
        if (key == "${item.type}:${item.id}" && bounds == rect) {
            key = null; bounds = null
        }
    }
}

@Composable
fun Modifier.trailerAnchor(item: MetaItem?): Modifier {
    if (item == null) return this
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(item.type, item.id) {
        onDispose { InlineTrailerAnchor.clearAnchor(item, bounds) }
    }
    return onGloballyPositioned {
        bounds = it.boundsInRoot()
        if (focused) bounds?.let { rect -> InlineTrailerAnchor.update(item, rect) }
    }.onFocusChanged {
        focused = it.hasFocus
        if (focused) bounds?.let { rect -> InlineTrailerAnchor.update(item, rect) }
    }
}
