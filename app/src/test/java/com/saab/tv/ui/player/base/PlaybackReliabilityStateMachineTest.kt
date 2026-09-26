package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackReliabilityStateMachineTest {
    @Test
    fun fallsBackInScoreOrderWithoutRepeatingSources() {
        val machine = PlaybackReliabilityStateMachine(maxFallbackSources = 2)
        machine.begin("best")

        assertEquals("second", machine.nextFallback(listOf("best", "second", "third", "fourth")))
        assertEquals("third", machine.nextFallback(listOf("best", "second", "third", "fourth")))
        assertNull(machine.nextFallback(listOf("best", "second", "third", "fourth")))
    }

    @Test
    fun newManualSelectionResetsFallbackBudget() {
        val machine = PlaybackReliabilityStateMachine(maxFallbackSources = 1)
        machine.begin("first")
        assertEquals("second", machine.nextFallback(listOf("first", "second")))
        machine.begin("second")
        assertEquals("first", machine.nextFallback(listOf("second", "first")))
    }
}
