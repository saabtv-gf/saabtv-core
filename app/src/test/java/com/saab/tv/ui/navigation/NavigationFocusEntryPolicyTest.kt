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

    @Test fun repeatedEntryKeyDoesNotExtendTheHandoffWindow() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.input(requestsNavigation = true)
        time = 300L
        policy.input(requestsNavigation = true, freshPress = false)
        time = 351L

        assertFalse(policy.enter())
    }

    @Test fun heldRepeatDoesNotRevokeTheOriginalHandoffWindow() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.input(requestsNavigation = true)
        time = 200L
        policy.input(requestsNavigation = true, freshPress = false)

        assertTrue(policy.enter())
    }

    @Test fun delayedFocusCorrectionDoesNotUndoAnAcceptedNavigationEntry() {
        val policy = NavigationFocusEntryPolicy { 100L }
        assertFalse(policy.enter())
        assertTrue(policy.shouldReturnToContent())

        policy.input(true)
        assertTrue(policy.enter())
        assertFalse(policy.shouldReturnToContent())

        policy.leave()
        assertTrue(policy.shouldReturnToContent())
    }

    @Test fun delayedFocusCorrectionWaitsForAnAuthorizedEntryStillInTransit() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.input(requestsNavigation = true)

        assertFalse("a fresh D-pad handoff is pending focus delivery", policy.shouldReturnToContent())

        time = 200L
        policy.input(requestsNavigation = true, freshPress = false)
        assertFalse("held repeats keep the original handoff alive without extending it", policy.shouldReturnToContent())
        assertTrue(policy.enter())
        assertFalse(policy.shouldReturnToContent())

        policy.leave()
        assertTrue(policy.shouldReturnToContent())
    }

    @Test fun expiredUnacceptedHandoffAllowsFocusRecovery() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.input(requestsNavigation = true)
        time = 351L

        assertFalse(policy.enter())
        assertTrue(policy.shouldReturnToContent())
    }

    @Test fun failedFocusRequestCancelsThePendingEntryWindow() {
        var time = 100L
        val policy = NavigationFocusEntryPolicy { time }
        policy.input(requestsNavigation = true)

        policy.cancelPendingEntry()
        time = 101L

        assertFalse(policy.enter())
        assertTrue(policy.shouldReturnToContent())
    }

    @Test fun outgoingNavigationSurfaceCannotClearTheCurrentHandoffOwner() {
        val registry = NavigationFocusHandoffRegistry()
        val outgoingOwner = Any()
        val currentOwner = Any()
        var outgoingCalls = 0
        var currentCalls = 0

        registry.register(outgoingOwner) { outgoingCalls++; true }
        registry.register(currentOwner) { currentCalls++; true }
        registry.clear(outgoingOwner)
        registry.dispatch(null)

        assertEquals(0, outgoingCalls)
        assertEquals(1, currentCalls)

        registry.clear(currentOwner)
        registry.dispatch(null)
        assertEquals(1, currentCalls)
    }
}
