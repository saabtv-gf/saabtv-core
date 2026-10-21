package com.saab.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationFocusHandoffRegistryTest {
    @Test
    fun dispatchOnlyAuthorizesTheLatestActiveNavigationSurface() {
        val registry = NavigationFocusHandoffRegistry()
        val drawer = Any()
        val topBar = Any()
        var drawerCalls = 0
        var topBarCalls = 0

        registry.register(drawer) { drawerCalls++; true }
        registry.register(topBar) { topBarCalls++; true }
        assertEquals(true, registry.dispatch(null))

        assertEquals(0, drawerCalls)
        assertEquals(1, topBarCalls)
    }

    @Test
    fun disposingAnOlderSurfaceCannotClearTheNewActiveSurface() {
        val registry = NavigationFocusHandoffRegistry()
        val drawer = Any()
        val topBar = Any()
        var topBarCalls = 0

        registry.register(drawer) { true }
        registry.register(topBar) { topBarCalls++; true }
        registry.clear(drawer)
        assertEquals(true, registry.dispatch(null))

        assertEquals(1, topBarCalls)
    }

    @Test
    fun clearingTheActiveSurfaceStopsFutureHandoffs() {
        val registry = NavigationFocusHandoffRegistry()
        val owner = Any()
        var calls = 0

        registry.register(owner) { calls++; true }
        registry.clear(owner)
        assertEquals(false, registry.dispatch(null))

        assertEquals(0, calls)
    }

    @Test
    fun dispatchReturnsTheActiveSurfaceAuthorizationResult() {
        val registry = NavigationFocusHandoffRegistry()
        registry.register(Any()) { false }

        assertEquals(false, registry.dispatch(null))
    }
}
