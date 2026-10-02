package com.saab.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationFocusEntryPolicyTest {
    @Test fun removingAnActionIsNotMenuNavigation() {
        val policy = NavigationFocusEntryPolicy { 100L }
        assertFalse(policy.enter())
        policy.input(true)
        policy.input(false) // OK on a content action cancels any stale directional intent.
        assertFalse(policy.enter())
    }
    @Test fun remoteEntryAndMovementWithinMenuRemainAvailable() {
        val policy = NavigationFocusEntryPolicy { 100L }
        policy.input(true)
        assertTrue(policy.enter())
        policy.input(false)
        assertTrue(policy.enter())
        policy.leave()
        assertFalse(policy.enter())
    }
    @Test fun oldNavigationInputCannotAuthorizeLaterFocusTheft() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.input(true)
        time = 351L
        assertFalse(policy.enter())
    }
}
