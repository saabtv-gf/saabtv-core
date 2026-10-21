package com.saab.tv.ui.navigation

/** Automatic Android focus restoration is not an instruction to open navigation. */
internal class NavigationFocusEntryPolicy(private val now: () -> Long) {
    private var allowedUntil = -1L
    var insideNavigation = false
        private set
    fun input(requestsNavigation: Boolean, freshPress: Boolean = true) {
        if (insideNavigation) return
        when {
            !requestsNavigation -> allowedUntil = -1L
            freshPress -> allowedUntil = now() + 250
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
