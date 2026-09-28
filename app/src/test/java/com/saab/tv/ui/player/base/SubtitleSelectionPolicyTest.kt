package com.saab.tv.ui.player.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionPolicyTest {
    private val embedded = PlayerTrackOption(
        id = "embedded-en",
        label = "English",
        subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED
    )

    @Test
    fun manualOffRemainsStickyAfterPendingSelectionIsApplied() {
        assertTrue(SubtitleSelectionPolicy.retainManualSelection(true, null, null))
        assertFalse(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(true, null, "#none", embedded))
        assertTrue(SubtitleSelectionPolicy.preserveOffOnSourceChange(true, "#none"))
    }

    @Test
    fun invalidRememberedTrackCanFallBackToAutomaticSelection() {
        assertFalse(SubtitleSelectionPolicy.retainManualSelection(true, "missing", null))
        assertTrue(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, null, "external-en", embedded))
    }

    @Test
    fun noAutomaticUpgradeWhenSubtitlesAreOff() {
        assertFalse(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(true, null, "#none", embedded))
        assertFalse(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, "#none", null, embedded))
        assertTrue(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, null, "#none", embedded))
        assertFalse(SubtitleSelectionPolicy.preserveOffOnSourceChange(false, "#none"))
    }
}
