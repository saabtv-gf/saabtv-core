package com.saab.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NavigationFocusGuard(
    private val scope: CoroutineScope,
    private val blocked: () -> Boolean,
    private val returnToContent: () -> Unit,
    private val incomingDirection: Key
) {
    private val policy = NavigationFocusEntryPolicy(android.os.SystemClock::elapsedRealtime)
    private val areas = mutableSetOf<String>()
    fun key(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown) policy.input(event.key == incomingDirection || event.key == Key.Back || event.key == Key.Escape)
        return false
    }
    fun focus(area: String, focused: Boolean): Boolean {
        if (focused) {
            if (!blocked() && policy.enter()) { areas.add(area); return true }
            com.saab.tv.AppDiagnostics.event("Navigation", "Automatic Menu Focus Rejected", "area=$area blocked=${blocked()}")
            scope.launch {
                withFrameNanos { }
                if (!blocked()) returnToContent()
            }
        } else {
            areas.remove(area)
            // Centre-to-dropdown transfers may report loss before gain.
            scope.launch { withFrameNanos { }; if (areas.isEmpty()) policy.leave() }
        }
        return false
    }
}

@Composable
internal fun rememberNavigationFocusGuard(blocked: Boolean, direction: Key, onReturn: () -> Unit): NavigationFocusGuard {
    val scope = rememberCoroutineScope()
    val latestBlocked by rememberUpdatedState(blocked)
    val latestReturn by rememberUpdatedState(onReturn)
    return remember(scope, direction) { NavigationFocusGuard(scope, { latestBlocked }, { latestReturn() }, direction) }
}
