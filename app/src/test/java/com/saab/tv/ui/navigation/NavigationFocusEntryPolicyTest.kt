package com.saab.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationFocusEntryPolicyTest {
    @Test fun removingAnActionIsNotMenuNavigation() {
        val policy = NavigationFocusEntryPolicy { 100L }
        assertFalse(policy.enter())
        policy.authorize()
        policy.input(false) // OK on a content action cancels any stale directional intent.
        assertFalse(policy.enter())
    }
    @Test fun remoteEntryAndMovementWithinMenuRemainAvailable() {
        val policy = NavigationFocusEntryPolicy { 100L }
        policy.authorize()
        assertTrue(policy.enter())
        policy.input(false)
        assertTrue(policy.enter())
        policy.leave()
        assertFalse(policy.enter())
    }
    @Test fun oldNavigationInputCannotAuthorizeLaterFocusTheft() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.authorize()
        time = 351L
        assertFalse(policy.enter())
    }
    @Test fun directionalAndBackInputDoNotAuthorizeAutomaticFocus() {
        val policy = NavigationFocusEntryPolicy { 100L }
        policy.input(true)
        assertFalse(policy.enter())
        policy.input(false)
        assertFalse(policy.enter())
    }
    @Test fun returningToContentRevokesMenuOwnershipImmediately() {
        val policy = NavigationFocusEntryPolicy { 100L }
        policy.authorize()
        assertTrue(policy.enter())
        policy.leave()
        assertFalse(policy.enter())
    }
}
