package com.saab.tv.ui.navigation

/** Automatic Android focus restoration is not an instruction to open navigation. */
internal class NavigationFocusEntryPolicy(private val now: () -> Long) {
    // A focus handoff can be delayed by TV layout/animation work, particularly on
    // slower devices. Keep the grant bounded, but long enough for the destination
    // to receive focus after the key event has been handled.
    private companion object { const val FOCUS_HANDOFF_TIMEOUT_MS = 3_000L }
    private var allowedUntil = -1L
    var insideNavigation = false
        private set
    fun input(requestsNavigation: Boolean, freshPress: Boolean = true) {
        if (insideNavigation) return
        when {
            !requestsNavigation -> allowedUntil = -1L
            freshPress -> allowedUntil = now() + FOCUS_HANDOFF_TIMEOUT_MS
            // A held repeat cannot authorize or extend entry, but must not revoke
            // the handoff window opened by the original press before focus arrives.
        }
    }
    fun cancelPendingEntry() {
        if (!insideNavigation) allowedUntil = -1L
    }
    fun enter(): Boolean {
        if (insideNavigation || now() <= allowedUntil) {
            insideNavigation = true
            allowedUntil = -1L
            return true
        }
        return false
    }
    fun leave() { insideNavigation = false; allowedUntil = -1L }

    /** A delayed focus correction must not race a still-pending directional handoff. */
    fun shouldReturnToContent(): Boolean = !insideNavigation && now() > allowedUntil
}
