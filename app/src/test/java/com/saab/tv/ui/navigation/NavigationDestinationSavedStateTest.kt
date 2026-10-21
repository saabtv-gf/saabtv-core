package com.saab.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDestinationSavedStateTest {
    @Test
    fun everyDestinationRoundTripsThroughItsStableSavedKey() {
        NavDestination.entries.forEach { destination ->
            assertEquals(destination, navDestinationFromSavedKey(destination.toSavedKey()))
        }
    }

    @Test
    fun unknownOrMissingSavedKeyFallsBackToHome() {
        assertEquals(NavDestination.Home, navDestinationFromSavedKey(null))
        assertEquals(NavDestination.Home, navDestinationFromSavedKey("removed-tab"))
    }

    @Test
    fun savedKeysAreCaseInsensitiveForForwardCompatibility() {
        assertEquals(NavDestination.Watchlist, navDestinationFromSavedKey("watchlist"))
    }
}
