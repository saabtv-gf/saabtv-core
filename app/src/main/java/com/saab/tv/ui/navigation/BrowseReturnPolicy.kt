package com.saab.tv.ui.navigation

/** Browsing parents must never point back at their own details/resume/player route. */
internal object BrowseReturnPolicy {
    fun onBack(previous: String): String = if (previous == "grid") "grid" else "menu"
    fun forPlayback(current: String, previous: String): String =
        if (current == "grid" || current == "menu") current else onBack(previous)
}
