package com.saab.tv.ui.navigation

import androidx.compose.runtime.saveable.Saver

/** One source of truth for the destinations shared by the two navigation layouts. */
internal object NavigationDestinations {
    enum class ContentFocusTarget {
        Catalog,
        Search,
        Settings,
        Library
    }

    val contentTabs = listOf(
        NavDestination.Home,
        NavDestination.Movies,
        NavDestination.Series,
        NavDestination.Ott,
        NavDestination.Watchlist
    )

    val topBar = listOf(NavDestination.Search) + contentTabs

    val drawerFocusOrder = listOf(NavDestination.Profile) + topBar + listOf(
        NavDestination.Settings,
        NavDestination.Exit
    )

    /** Central route-to-entry mapping used by navigation and its regression tests. */
    fun contentFocusTarget(destination: NavDestination): ContentFocusTarget? = when (destination) {
        NavDestination.Home, NavDestination.Movies, NavDestination.Series, NavDestination.Ott -> ContentFocusTarget.Catalog
        NavDestination.Search -> ContentFocusTarget.Search
        NavDestination.Settings -> ContentFocusTarget.Settings
        NavDestination.Watchlist -> ContentFocusTarget.Library
        NavDestination.Profile, NavDestination.Exit -> null
    }

    fun selectionEntersContent(destination: NavDestination): Boolean =
        contentFocusTarget(destination) != null
}

/** Stable saved-state key for the selected shell tab across Activity recreation. */
internal fun NavDestination.toSavedKey(): String = name

/** Unknown/old keys safely reopen the app at Home instead of restoring an invalid tab. */
internal fun navDestinationFromSavedKey(key: String?): NavDestination =
    NavDestination.entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: NavDestination.Home

internal val NavDestinationSaver: Saver<NavDestination, String> = Saver(
    save = { it.toSavedKey() },
    restore = ::navDestinationFromSavedKey
)
