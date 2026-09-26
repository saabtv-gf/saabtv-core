package com.saab.tv.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountSyncCadenceTest {
    @Test fun firstAutomaticSyncWaitsSixtySeconds() {
        val cadence = AccountSyncCadence(0)
        assertFalse(cadence.beginAttempt(59_999, true))
        assertTrue(cadence.beginAttempt(60_000, true))
    }
    @Test fun continuousChangesDoNotStarveOrAccelerateSync() {
        val cadence = AccountSyncCadence(0)
        repeat(12) { cadence.markDirty(); assertFalse(cadence.beginAttempt(it * 5_000L, true)) }
        assertTrue(cadence.beginAttempt(60_000, true))
        cadence.markDirty()
        assertFalse(cadence.beginAttempt(65_000, true))
        assertTrue(cadence.beginAttempt(120_000, true))
    }
    @Test fun noChangesSkipAutomaticWork() {
        val cadence = AccountSyncCadence(0)
        assertTrue(cadence.beginAttempt(60_000, true))
        assertFalse(cadence.beginAttempt(120_000, true))
    }
    @Test fun manualFlushIsImmediateAndResetsAutomaticWindow() {
        val cadence = AccountSyncCadence(0)
        assertTrue(cadence.beginAttempt(5_000, false))
        cadence.markDirty()
        assertFalse(cadence.beginAttempt(60_000, true))
        assertTrue(cadence.beginAttempt(65_000, true))
    }
    @Test fun changesDuringAttemptSurviveForNextWindow() {
        val cadence = AccountSyncCadence(0)
        assertTrue(cadence.beginAttempt(60_000, true))
        cadence.markDirty()
        assertTrue(cadence.beginAttempt(120_000, true))
    }
}
