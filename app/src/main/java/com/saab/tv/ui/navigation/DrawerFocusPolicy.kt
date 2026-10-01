package com.saab.tv.ui.navigation

/** Stable vertical order independent of spacer sizes and expansion animation. */
internal object DrawerFocusPolicy {
    val order = listOf(NavDestination.Profile, NavDestination.Search,
        NavDestination.Home, NavDestination.Movies, NavDestination.Series,
        NavDestination.Ott, NavDestination.Watchlist, NavDestination.Settings, NavDestination.Exit)

    fun adjacent(destination: NavDestination, down: Boolean): NavDestination {
        val index = order.indexOf(destination).coerceAtLeast(0)
        return order[(index + if (down) 1 else -1).coerceIn(order.indices)]
    }
}
