package com.saab.tv.ui.navigation

import androidx.compose.runtime.saveable.Saver

/** Concrete states owned by the app shell; use strings only at external navigation boundaries. */
internal enum class ShellView(internal val savedKey: String) {
    Menu("menu"),
    Grid("grid"),
    Details("details"),
    Resume("resume"),
    Player("player");

    companion object {
        private val byKey = entries.associateBy(ShellView::savedKey)

        fun fromSaved(value: String): ShellView = byKey[value.lowercase()] ?: Menu
    }
}

/** Accept the legacy string state written by older builds and persist typed routes thereafter. */
internal val ShellViewSaver: Saver<ShellView, String> = Saver(
    save = { it.savedKey },
    restore = ShellView::fromSaved
)

/** Browsing parents must never point back at their own details/resume/player route. */
internal object BrowseReturnPolicy {
    fun onBack(previous: ShellView): ShellView =
        if (previous == ShellView.Grid) ShellView.Grid else ShellView.Menu

    fun forPlayback(current: ShellView, previous: ShellView): ShellView =
        if (current == ShellView.Grid || current == ShellView.Menu) current else onBack(previous)
}
