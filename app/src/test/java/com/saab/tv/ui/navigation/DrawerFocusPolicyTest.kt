package com.saab.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerFocusPolicyTest {
    @Test fun everyMenuItemHasDeterministicVerticalNeighbours() {
        val order = DrawerFocusPolicy.order
        assertEquals(NavDestination.values().toSet(), order.toSet())
        order.forEachIndexed { index, destination ->
            assertEquals(order[(index - 1).coerceAtLeast(0)], DrawerFocusPolicy.adjacent(destination, false))
            assertEquals(order[(index + 1).coerceAtMost(order.lastIndex)], DrawerFocusPolicy.adjacent(destination, true))
        }
    }
}
