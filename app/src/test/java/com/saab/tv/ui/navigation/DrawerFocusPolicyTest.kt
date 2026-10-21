package com.saab.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerFocusPolicyTest {
    @Test fun bothNavigationLayoutsShareTheSameContentDestinationOrder() {
        assertEquals(
            NavigationDestinations.contentTabs,
            NavigationDestinations.topBar.drop(1)
        )
        assertEquals(
            NavDestination.values().toSet(),
            NavigationDestinations.drawerFocusOrder.toSet()
        )
        assertEquals(NavigationDestinations.drawerFocusOrder, DrawerFocusPolicy.order)
    }

    @Test fun everyMenuItemHasDeterministicVerticalNeighbours() {
        val order = DrawerFocusPolicy.order
        assertEquals(NavDestination.values().toSet(), order.toSet())
        order.forEachIndexed { index, destination ->
            assertEquals(order[(index - 1).coerceAtLeast(0)], DrawerFocusPolicy.adjacent(destination, false))
            assertEquals(order[(index + 1).coerceAtMost(order.lastIndex)], DrawerFocusPolicy.adjacent(destination, true))
        }
    }

    @Test fun everyNavigationDestinationHasOneExplicitContentFocusTargetOrNone() {
        val expected = mapOf(
            NavDestination.Home to NavigationDestinations.ContentFocusTarget.Catalog,
            NavDestination.Movies to NavigationDestinations.ContentFocusTarget.Catalog,
            NavDestination.Series to NavigationDestinations.ContentFocusTarget.Catalog,
            NavDestination.Ott to NavigationDestinations.ContentFocusTarget.Catalog,
            NavDestination.Search to NavigationDestinations.ContentFocusTarget.Search,
            NavDestination.Watchlist to NavigationDestinations.ContentFocusTarget.Library,
            NavDestination.Settings to NavigationDestinations.ContentFocusTarget.Settings,
            NavDestination.Profile to null,
            NavDestination.Exit to null
        )

        NavDestination.values().forEach { destination ->
            val focusTarget = NavigationDestinations.contentFocusTarget(destination)
            assertEquals("Unexpected focus entry for $destination", expected[destination], focusTarget)
            assertEquals("Entry policy must match focus mapping for $destination", focusTarget != null,
                NavigationDestinations.selectionEntersContent(destination))
        }
    }
}
