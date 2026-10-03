package com.saab.tv.ui.player.base

import org.junit.Assert.*
import org.junit.Test

class SmartCreditsPolicyTest {
    @Test fun scansOnlyFinalFiveMinutesOnCacheGrid() {
        for (interval in listOf(10, 20, 30)) {
            val targets = SmartCreditsPolicy.targets(2_703_500L, interval)
            assertTrue(targets.size <= 31)
            assertTrue(targets.all { it >= 2_403_500L && it < 2_703_500L && it % (interval * 1_000L) == 0L })
        }
        assertTrue(SmartCreditsPolicy.targets(300_000L, 10).isEmpty())
    }
    @Test fun requiresAdjacentFramesAndOffersOneIntervalEarlier() {
        val targets = listOf(400_000L, 410_000L, 420_000L, 430_000L)
        assertNull(SmartCreditsPolicy.promptAt(targets, mapOf(410_000L to true, 430_000L to true), 10))
        assertEquals(400_000L, SmartCreditsPolicy.promptAt(targets, mapOf(410_000L to true, 420_000L to true), 10))
    }
    @Test fun prioritizesEarliestUncachedCreditWindowFrame() {
        val targets = listOf(400_000L, 410_000L, 420_000L)
        assertEquals(410_000L, SmartCreditsPolicy.nextMissingTarget(targets, mapOf(400_000L to false)))
        assertNull(SmartCreditsPolicy.nextMissingTarget(targets, targets.associateWith { false }))
    }
    @Test fun recognizesMultiLineOcrWithCreditRoles() {
        assertTrue(SmartCreditsPolicy.looksLikeCreditsText("Directed by\nJane Smith\nProduced by\nJohn Doe"))
        assertTrue(SmartCreditsPolicy.looksLikeCreditsText("CINEMATOGRAPHY\nA. Operator\nMUSIC BY\nComposer"))
    }
    @Test fun rejectsBlankOrdinaryOrSingleLineOcr() {
        assertFalse(SmartCreditsPolicy.looksLikeCreditsText(""))
        assertFalse(SmartCreditsPolicy.looksLikeCreditsText("A dramatic scene\nwith dialogue"))
        assertFalse(SmartCreditsPolicy.looksLikeCreditsText("Directed by"))
    }
    @Test fun smartDoesNotBecomePercentageFallbackOrAutoplay() {
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(990_000, 1_000_000, null, "smart", 95, 30, false))
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(950_000, 1_000_000, null, "smart", 95, 30, false, 940_000))
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(950_000, 1_000_000, 980_000, "smart", 95, 30, false, 940_000))
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(990_000, 1_000_000, null, "smart", 95, 30, false))
    }
}
