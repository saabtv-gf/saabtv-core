package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressAnimationPolicyTest {
    @Test
    fun visualProgressUsesTheBackendSnapshotCadenceAndAdvancesLinearly() {
        assertEquals(1_000, PlaybackProgressAnimationPolicy.BACKEND_SNAPSHOT_INTERVAL_MS)
        assertEquals(1_000, PlaybackProgressAnimationPolicy.interpolationDurationMs(isPlaybackAdvancing = true))
        assertEquals(0, PlaybackProgressAnimationPolicy.interpolationDurationMs(isPlaybackAdvancing = false))
        assertEquals(0.25f, PlaybackProgressAnimationPolicy.fraction(15_000L, 60_000L), 0.0001f)
    }

    @Test
    fun progressFractionClampsInvalidPositionsAndHandlesUnknownDuration() {
        assertEquals(0f, PlaybackProgressAnimationPolicy.fraction(-1L, 60_000L), 0f)
        assertEquals(1f, PlaybackProgressAnimationPolicy.fraction(61_000L, 60_000L), 0f)
        assertEquals(0f, PlaybackProgressAnimationPolicy.fraction(10_000L, 0L), 0f)
    }
}
