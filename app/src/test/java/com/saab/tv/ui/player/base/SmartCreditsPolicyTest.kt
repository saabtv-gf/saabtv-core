package com.saab.tv.ui.player.base

import org.junit.Assert.*
import org.junit.Test

class SmartCreditsPolicyTest {
    @Test fun reportsTheFirstReasonSmartFallbackCannotRun() {
        fun reason(
            autoplay: Boolean = true,
            mode: String = "smart",
            outro: Boolean = false,
            nextEpisode: Boolean = true,
            transitionCallback: Boolean = true,
            trailer: Boolean = false,
            error: Boolean = false,
            frameProvider: Boolean = true,
            durationMs: Long = 600_000L
        ) = SmartCreditsPolicy.scanGateReason(
            autoplay, mode, outro, nextEpisode, transitionCallback, trailer, error, frameProvider, durationMs
        )

        assertEquals("autoplay_disabled", reason(autoplay = false))
        assertEquals("threshold_mode_introdb", reason(mode = "introdb"))
        assertEquals("introdb_outro_available", reason(outro = true))
        assertEquals("no_next_episode", reason(nextEpisode = false))
        assertEquals("transition_callback_unavailable", reason(transitionCallback = false))
        assertEquals("trailer_playback", reason(trailer = true))
        assertEquals("playback_error", reason(error = true))
        assertEquals("thumbnail_provider_unavailable_or_disabled", reason(frameProvider = false))
        assertEquals("duration_under_5_minutes", reason(durationMs = 300_000L))
        assertEquals("eligible", reason())
    }

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
