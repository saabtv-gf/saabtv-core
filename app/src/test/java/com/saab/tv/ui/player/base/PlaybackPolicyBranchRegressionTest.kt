package com.saab.tv.ui.player.base

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyBranchRegressionTest {
    @Test fun reliabilityStateAndFallbackBudgetCoverEmptyAndRepeatedSources() {
        val machine = PlaybackReliabilityStateMachine()
        assertEquals(PlaybackReliabilityPhase.IDLE, machine.phase)
        machine.begin(null)
        assertEquals(PlaybackReliabilityPhase.PREPARING, machine.phase)
        assertNull(machine.nextFallback(emptyList()))
        assertNull(machine.nextFallback(listOf("", " ")))
        assertEquals("a", machine.nextFallback(listOf("", "a")))
        assertEquals("b", machine.nextFallback(listOf("a", "b")))
        assertEquals(PlaybackReliabilityPhase.FALLING_BACK, machine.phase)
        assertNull(machine.nextFallback(listOf("c")))
        machine.retryCurrent()
        assertEquals(PlaybackReliabilityPhase.RETRYING, machine.phase)
        for (phase in PlaybackReliabilityPhase.entries) {
            machine.transition(phase)
            assertEquals(phase, machine.phase)
        }
        machine.begin(" ")
        assertEquals("a", machine.nextFallback(listOf("a")))
        machine.begin("a")
        assertNull(machine.nextFallback(listOf("a")))
        assertNull(PlaybackReliabilityStateMachine(0).nextFallback(listOf("a")))
    }
    @Test fun bufferRecoveryCooldownResetAndBothMovementDirections() {
        val defaults = PlaybackBufferRecoveryPolicy()
        assertFalse(defaults.shouldRecover(1, 0, true, true))
        assertTrue(defaults.shouldRecover(12_001, 0, true, true))
        val policy = PlaybackBufferRecoveryPolicy(100, 300, 10)
        assertFalse(policy.shouldRecover(1, 100, true, true))
        assertFalse(policy.shouldRecover(100, 100, true, true))
        assertTrue(policy.shouldRecover(101, 100, true, true))
        assertFalse(policy.shouldRecover(201, 100, true, true))
        assertTrue(policy.shouldRecover(401, 100, true, true))
        assertFalse(policy.shouldRecover(402, 110, true, true))
        assertFalse(policy.shouldRecover(403, 100, true, true))
        assertFalse(policy.shouldRecover(1000, 100, false, true))
        assertFalse(policy.shouldRecover(1001, 100, true, false))
        policy.reset()
        assertFalse(policy.shouldRecover(1002, 100, true, true))
        assertTrue(policy.shouldRecover(1102, 100, true, true))
    }
    @Test fun jitterIgnoresZeroDropsExpiresItsWindowAndResetsCooldown() {
        val defaults = VideoJitterRecoveryPolicy()
        assertFalse(defaults.shouldRecover(9999, 0, 12, true))
        assertTrue(defaults.shouldRecover(10000, 0, 12, true))
        val policy = VideoJitterRecoveryPolicy(0, 100, 10, 300)
        assertFalse(policy.shouldRecover(1, 0, 0, true))
        assertFalse(policy.shouldRecover(2, 0, -1, true))
        assertFalse(policy.shouldRecover(10, 0, 9, true))
        assertFalse(policy.shouldRecover(111, 0, 1, true))
        assertTrue(policy.shouldRecover(112, 0, 9, true))
        assertFalse(policy.shouldRecover(212, 0, 10, true))
        policy.reset()
        assertTrue(policy.shouldRecover(213, 0, 10, true))
        assertFalse(policy.shouldRecover(214, 0, 10, false))
    }
    @Test fun subtitleManualAutomaticAndEmbeddedSelectionBranches() {
        val embedded = PlayerTrackOption("embedded", "English", subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED)
        val addon = embedded.copy(id = "addon", subtitleSourcePriority = SubtitleSourcePriority.ADDON)
        assertFalse(SubtitleSelectionPolicy.preserveOffOnSourceChange(true, null))
        assertFalse(SubtitleSelectionPolicy.preserveOffOnSourceChange(true, "embedded"))
        assertFalse(SubtitleSelectionPolicy.retainManualSelection(false, null, null))
        assertTrue(SubtitleSelectionPolicy.retainManualSelection(true, "embedded", "embedded"))
        assertFalse(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, null, null, null))
        assertFalse(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, null, null, addon))
        assertFalse(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, null, "embedded", embedded))
        assertTrue(SubtitleSelectionPolicy.shouldUpgradeToEmbedded(false, null, null, embedded))
    }
    @Test fun subtitlePreferenceRejectsUnknownLanguageAndUsesRoleFlagsAsTieBreakers() {
        val ordinary = PlayerTrackOption("ordinary", "English", "en", subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED)
        val forced = ordinary.copy(id = "forced", selectionFlags = androidx.media3.common.C.SELECTION_FLAG_FORCED)
        val descriptive = ordinary.copy(id = "descriptive", roleFlags = androidx.media3.common.C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)
        assertNull(SubtitleTrackPreference.findPreferred(listOf(ordinary), "unknown"))
        assertNull(SubtitleTrackPreference.findPreferred(listOf(ordinary), "und"))
        assertNull(SubtitleTrackPreference.findPreferred(emptyList(), "en"))
        assertEquals(ordinary, SubtitleTrackPreference.findPreferred(listOf(forced, descriptive, ordinary), "en"))
        assertEquals(forced, SubtitleTrackPreference.findPreferred(listOf(descriptive, forced), "en"))
    }
    @Test fun externalSubtitleIdsDoNotCollideWithNumericContainerTrackIds() {
        assertEquals("s:external:unknown", externalSubtitleTrackId(" "))
        assertEquals("s:external:track", externalSubtitleTrackId("s:track"))
        assertEquals("s:external:track", externalSubtitleTrackId("s:external:track"))
        val ids = linkedSetOf("s:external:track", "s:external:other")
        assertNull(matchExternalSubtitleTrackId(null, ids))
        assertEquals("s:external:track", matchExternalSubtitleTrackId("s:external:track", ids))
        assertEquals("s:external:other", matchExternalSubtitleTrackId("0:1:s:external:other", ids))
        assertNull(matchExternalSubtitleTrackId("garbage:s:external:track", ids))
        assertNull(matchExternalSubtitleTrackId("unknown", ids))
        assertNull(matchExternalSubtitleTrackId("0:s:external:other", emptySet()))
    }
    @Test fun adaptiveBufferMatrixIncludesMemoryAndQualityBoundaries() {
        for (torrent in listOf(false, true)) {
            for (memory in listOf(255, 256, 257, 767, 768)) {
                for (lowRam in listOf(false, true)) {
                    for (height in listOf<Int?>(null, 720, 2159, 2160)) {
                        val config = AdaptiveBufferPolicy.choose(torrent, memory, lowRam, height)
                        val low = lowRam || memory <= 256
                        val high4k = !low && memory >= 768 && height == 2160
                        assertEquals(if (low) 48 else if (high4k) 96 else 72, config.targetBufferBytes / (1024 * 1024))
                        assertEquals(if (torrent) 500 else 250, config.bufferForPlaybackMs)
                        assertEquals(if (low) 1500 else if (torrent) 3000 else 5000, config.backBufferMs)
                        assertEquals(if (low) 2000 else if (torrent) 3000 else 2500, config.bufferForPlaybackAfterRebufferMs)
                        assertEquals(if (low) { if (torrent) 6000 else 8000 } else { if (torrent) 8000 else 10000 }, config.minBufferMs)
                        assertEquals(if (low) { if (torrent) 20000 else 24000 } else if (high4k) 42000 else if (torrent) 30000 else 35000, config.maxBufferMs)
                    }
                }
            }
        }
    }
    @Test fun seekCarouselEmptySingleEvenAndOddLayouts() {
        assertEquals(emptyList<Int>(), SeekThumbnailCarouselPolicy.loadOrder(0))
        assertEquals(emptyList<Int>(), SeekThumbnailCarouselPolicy.loadOrder(-1))
        assertEquals(listOf(0), SeekThumbnailCarouselPolicy.loadOrder(1))
        assertEquals(listOf(1, 0), SeekThumbnailCarouselPolicy.loadOrder(2))
        assertEquals(listOf(2, 1, 3, 0), SeekThumbnailCarouselPolicy.loadOrder(4))
        assertEquals(listOf(2, 1, 3, 0, 4), SeekThumbnailCarouselPolicy.loadOrder(5))
    }
    @Test fun autoplayRequiresValidIntroDbMarkerButFallbackRemainsManual() {
        for (marker in listOf<Long?>(null, -1, 0, 1000, 2000)) {
            val valid = marker == 1000L
            assertEquals(valid, AutoplayNextEpisodePolicy.shouldStartCountdown(1000, 2000, marker, "off", 95, 60, false))
            assertEquals(valid, AutoplayNextEpisodePolicy.shouldStartCountdown(0, 2000, marker, "off", 95, 60, true))
        }
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(999, 2000, 1000, "percentage", 95, 60, false))
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(0, 0, null, "off", 95, 60, true))
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(0, 0, null, "off", 95, 60, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(999, 2000, 1000, "percentage", 0, 60, false))
        for (marker in listOf<Long?>(null, -1, 0, 2000)) {
            assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(1500, 2000, marker, "introdb", 95, 60, false))
        }
        for (prompt in listOf<Long?>(null, -1, 1000, 1600)) {
            assertEquals(prompt == 1000L, AutoplayNextEpisodePolicy.shouldOfferNextEpisode(1500, 2000, null, "smart", 95, 60, false, prompt))
        }
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(1999, 2000, null, "time", 95, -1, false))
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(2000, 2000, null, "time", 95, -1, false))
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(0, 2000, null, "percentage", -1, 60, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(1999, 2000, null, "percentage", 101, 60, false))
    }
}
