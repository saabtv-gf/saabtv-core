package com.saab.tv.ui.navigation

/** Automatic Android focus restoration is not an instruction to open navigation. */
internal class NavigationFocusEntryPolicy(private val now: () -> Long) {
    private var allowedUntil = -1L
    var insideNavigation = false
        private set
    fun input(requestsNavigation: Boolean) {
        if (!insideNavigation) allowedUntil = if (requestsNavigation) now() + 250 else -1L
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
}
