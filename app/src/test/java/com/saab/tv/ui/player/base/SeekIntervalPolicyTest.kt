package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekIntervalPolicyTest {
    @Test
    fun supportsConfiguredIntervals() {
        assertEquals(10_000L, SeekIntervalPolicy.intervalMs(10))
        assertEquals(20_000L, SeekIntervalPolicy.intervalMs(20))
        assertEquals(30_000L, SeekIntervalPolicy.intervalMs(30))
    }

    @Test
    fun fallsBackToTenSecondsForInvalidValues() {
        assertEquals(10, SeekIntervalPolicy.normalizeSeconds(0))
        assertEquals(10, SeekIntervalPolicy.normalizeSeconds(15))
    }
}
