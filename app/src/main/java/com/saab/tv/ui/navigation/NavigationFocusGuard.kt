package com.saab.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
        // A key inside a popup/row is not a request to enter navigation.
        if (event.type == KeyEventType.KeyDown) policy.input(false)
        return false
    }
    fun contentFocused(focused: Boolean) {
        if (focused) { areas.clear(); policy.leave() }
    }
    fun contentExit(direction: FocusDirection, target: FocusRequester?): FocusRequester {
        val incoming = if (incomingDirection == Key.DirectionLeft) FocusDirection.Left else FocusDirection.Up
        if (!blocked() && direction == incoming && target != null) {
            policy.authorize()
            return target
        }
        return FocusRequester.Default
    }
    fun requestNavigation(target: () -> FocusRequester?) {
        if (blocked()) return
        scope.launch {
            withFrameNanos { }
            if (blocked()) return@launch
            val requester = target() ?: return@launch
            policy.authorize()
            val result = runCatching { requester.requestFocus() }
            if (result.getOrDefault(false) != true) {
                policy.leave()
                result.exceptionOrNull()?.let {
                    com.saab.tv.AppDiagnostics.failure("Navigation", "Explicit Menu Focus Failed", it)
                }
                returnToContent()
            }
        }
    }
    fun focus(area: String, focused: Boolean): Boolean {
        if (focused) {
            if (!blocked() && policy.enter()) { areas.add(area); return true }
            areas.clear(); policy.leave()
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

/** Only content handlers for explicit Back/boundary navigation should invoke this. */
internal val LocalNavigationMenuRequest = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun navigationMenuRequest(fallback: FocusRequester?): () -> Unit =
    LocalNavigationMenuRequest.current ?: { runCatching { fallback?.requestFocus() }; Unit }

@Composable
internal fun rememberNavigationFocusGuard(blocked: Boolean, direction: Key, onReturn: () -> Unit): NavigationFocusGuard {
    val scope = rememberCoroutineScope()
    val latestBlocked by rememberUpdatedState(blocked)
    val latestReturn by rememberUpdatedState(onReturn)
    return remember(scope, direction) { NavigationFocusGuard(scope, { latestBlocked }, { latestReturn() }, direction) }
}
