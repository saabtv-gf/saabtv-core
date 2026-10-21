package com.saab.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Lets a boundary handler authorize the focus request that its D-pad press consumes. */
internal val LocalNavigationFocusHandoff = compositionLocalOf<((KeyEvent?) -> Boolean)?> { null }
internal val LocalNavigationFocusHandoffRegistry = compositionLocalOf<NavigationFocusHandoffRegistry?> { null }

/** Bridges focus-boundary handlers to a navigation surface composed as a sibling. */
internal class NavigationFocusHandoffRegistry {
    private var activeOwner: Any? = null
    private var authorize: ((KeyEvent?) -> Boolean)? = null

    fun register(owner: Any, handler: (KeyEvent?) -> Boolean) {
        activeOwner = owner
        authorize = handler
    }

    fun clear(owner: Any) {
        if (activeOwner === owner) {
            activeOwner = null
            authorize = null
        }
    }

    fun dispatch(event: KeyEvent?): Boolean = authorize?.invoke(event) ?: false
}

@Composable
fun NavigationFocusHandoffHost(content: @Composable () -> Unit) {
    val registry = remember { NavigationFocusHandoffRegistry() }
    val dispatch: (KeyEvent?) -> Boolean = remember(registry) { registry::dispatch }
    CompositionLocalProvider(
        LocalNavigationFocusHandoffRegistry provides registry,
        LocalNavigationFocusHandoff provides dispatch,
        content = content
    )
}

internal class NavigationFocusGuard(
    private val scope: CoroutineScope,
    private val blocked: () -> Boolean,
    private val returnToContent: () -> Unit,
    private val incomingDirection: Key,
    private val recoveryBlocked: () -> Boolean = blocked
) {
    private val policy = NavigationFocusEntryPolicy(android.os.SystemClock::elapsedRealtime)
    private val areas = mutableSetOf<String>()

    fun resetForBlockedNavigation() {
        areas.clear()
        policy.leave()
    }

    fun key(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown) authorizeDirectEntry(event)
        return false
    }

    /**
     * A content boundary can consume the D-pad event before it bubbles to the
     * shell. Authorize only that explicit handoff (or a user-triggered Back),
     * never an unrelated focus restoration.
     */
    fun authorizeDirectEntry(event: KeyEvent?): Boolean {
        // A missing event is not a user action; never arm automatic focus restoration.
        if (event == null) return false
        if (event.type == KeyEventType.KeyDown) {
            val requestsNavigation = event.key == incomingDirection && !blocked()
            val freshPress = event.isInitialDpadPress()
            policy.input(
                requestsNavigation = requestsNavigation,
                freshPress = freshPress
            )
            return requestsNavigation && freshPress
        }
        return false
    }

    /** Drop a handoff grant when its requested destination cannot accept focus. */
    fun handoff(event: KeyEvent?, requestFocus: () -> Boolean): Boolean {
        if (!authorizeDirectEntry(event)) return false
        val requested = requestFocus()
        if (!requested) policy.cancelPendingEntry()
        return requested
    }

    /** A null handoff is used only by an explicit Back action from focused content. */
    fun handoffBack(requestFocus: () -> Boolean): Boolean {
        if (blocked()) return false
        policy.input(requestsNavigation = true)
        val requested = requestFocus()
        if (!requested) policy.cancelPendingEntry()
        return requested
    }

    fun focus(area: String, focused: Boolean): Boolean {
        if (focused) {
            if (!blocked() && policy.enter()) { areas.add(area); return true }
            com.saab.tv.AppDiagnostics.event("Navigation", "Automatic Menu Focus Rejected", "area=$area blocked=${blocked()}")
            scope.launch {
                withFrameNanos { }
                if (!recoveryBlocked() && policy.shouldReturnToContent()) returnToContent()
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
internal fun rememberNavigationFocusGuard(
    blocked: Boolean,
    direction: Key,
    onReturn: () -> Unit,
    recoveryBlocked: Boolean = blocked
): NavigationFocusGuard {
    val scope = rememberCoroutineScope()
    val latestBlocked by rememberUpdatedState(blocked)
    val latestReturn by rememberUpdatedState(onReturn)
    val latestRecoveryBlocked by rememberUpdatedState(recoveryBlocked)
    val guard = remember(scope, direction) {
        NavigationFocusGuard(scope, { latestBlocked }, { latestReturn() }, direction, { latestRecoveryBlocked })
    }
    LaunchedEffect(blocked, guard) {
        if (blocked) guard.resetForBlockedNavigation()
    }
    return guard
}
