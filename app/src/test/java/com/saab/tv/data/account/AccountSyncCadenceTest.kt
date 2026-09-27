package com.saab.tv.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountSyncCadenceTest {
    @Test fun idleDoesNotScheduleAnyWork() {
        val cadence = AccountSyncCadence(0)
        assertNull(cadence.waitMs(0))
        assertFalse(cadence.beginAttempt(1_000_000, true))
    }
    @Test fun meaningfulChangesAreCoalesced() {
        val cadence = AccountSyncCadence(0)
        cadence.markDirty(0)
        cadence.markDirty(1_000)
        assertFalse(cadence.beginAttempt(1_999, true))
        assertTrue(cadence.beginAttempt(2_000, true))
        assertNull(cadence.waitMs(2_001))
    }
    @Test fun progressIsBatchedAndUrgentBoundaryAcceleratesIt() {
        val cadence = AccountSyncCadence(0)
        cadence.markDirty(0, progressOnly = true)
        repeat(20) { cadence.markDirty(it * 5_000L, progressOnly = true) }
        assertFalse(cadence.beginAttempt(99_999, true))
        cadence.markDirty(100_000, urgent = true)
        assertTrue(cadence.beginAttempt(100_500, true))
    }
    @Test fun continuousProgressCannotStarveCheckpoint() {
        val cadence = AccountSyncCadence(0)
        repeat(24) { cadence.markDirty(it * 5_000L, progressOnly = true) }
        assertTrue(cadence.beginAttempt(120_000, true))
    }
    @Test fun changesDuringAttemptAreNotLost() {
        val cadence = AccountSyncCadence(0)
        cadence.markDirty(0)
        assertTrue(cadence.beginAttempt(2_000, true))
        cadence.markDirty(2_001)
        assertTrue(cadence.beginAttempt(4_001, true))
    }
    @Test fun manualFlushIsImmediate() {
        val cadence = AccountSyncCadence(0)
        cadence.markDirty(0, progressOnly = true)
        assertTrue(cadence.beginAttempt(1, false))
        assertNull(cadence.waitMs(2))
    }
    @Test fun retriesBackOffAndWaitForNewEventAfterExhaustion() {
        val cadence = AccountSyncCadence(0)
        cadence.retryAt(0, 15_000)
        assertFalse(cadence.beginAttempt(14_999, true))
        assertTrue(cadence.beginAttempt(15_000, true))
        cadence.deferUntilEvent(15_001)
        assertNull(cadence.waitMs(100_000))
        cadence.markDirty(100_000)
        assertTrue(cadence.beginAttempt(102_000, true))
    }
    @Test fun progressAfterDeferralStartsANewBatch() {
        val cadence = AccountSyncCadence(0)
        cadence.deferUntilEvent(0)
        cadence.markDirty(300_000, progressOnly = true)
        assertEquals(120_000L, cadence.waitMs(300_000))
        assertFalse(cadence.beginAttempt(419_999, true))
        assertTrue(cadence.beginAttempt(420_000, true))
    }
}
