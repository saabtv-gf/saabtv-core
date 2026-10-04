package com.saab.tv.ui.player.base

import org.junit.Assert.*
import org.junit.Test

class SmartCreditsPolicyTest {
    @Test fun imageHeuristicRecognizesTwoTextBandsAndRejectsBlackScreens() {
        fun assess(bands: Int) = SmartCreditsPolicy.assessImage(160, 90) { x, y ->
            val textRow = (0 until bands).any { y in (20 + it * 12)..(22 + it * 12) }
            if (textRow && x in 40..79 && (x - 40) % 5 < 2) -1 else 0xff000000.toInt()
        }
        assertTrue(assess(3).credits)
        assertTrue(assess(2).credits)
        assertFalse(assess(1).credits)
        assertFalse(assess(0).credits)
        assertFalse(SmartCreditsPolicy.assessImage(160, 90) { _, _ -> -1 }.credits)
    }

    @Test fun captureLatencyIsAllowedButForwardAndBackwardSeeksAreRejected() {
        assertTrue(SmartCreditsPolicy.frameStillCurrent(100_000, 105_000, 5_000, 1f, false))
        assertTrue(SmartCreditsPolicy.frameStillCurrent(100_000, 110_000, 5_000, 2f, false))
        assertFalse(SmartCreditsPolicy.frameStillCurrent(100_000, 140_000, 5_000, 1f, false))
        assertFalse(SmartCreditsPolicy.frameStillCurrent(100_000, 90_000, 5_000, 1f, false))
        assertFalse(SmartCreditsPolicy.frameStillCurrent(100_000, 105_000, 5_000, 1f, true))
    }
    @Test fun liveCastCreditsDoNotRequireKeywordsButRejectOrdinarySubtitles() {
        assertTrue(SmartCreditsPolicy.liveCreditsLayout(0.9f, 12, 130, 0.45f))
        assertFalse(SmartCreditsPolicy.liveCreditsLayout(0.3f, 12, 130, 0.45f))
        assertFalse(SmartCreditsPolicy.liveCreditsLayout(0.9f, 2, 60, 0.05f))
        assertFalse(SmartCreditsPolicy.liveCreditsLayout(0.9f, 5, 15, 0.4f))
        assertFalse(SmartCreditsPolicy.liveCreditsLayout(0.9f, 5, 60, 0.1f))
    }
    @Test fun liveScanOnlyRunsDuringPlayingFinalFiveMinutes() {
        assertTrue(SmartCreditsPolicy.liveScanEligible(600_000, 900_000, true, false))
        assertFalse(SmartCreditsPolicy.liveScanEligible(599_999, 900_000, true, false))
        assertFalse(SmartCreditsPolicy.liveScanEligible(900_000, 900_000, true, false))
        assertFalse(SmartCreditsPolicy.liveScanEligible(600_000, 900_000, false, false))
        assertFalse(SmartCreditsPolicy.liveScanEligible(600_000, 900_000, true, true))
        assertFalse(SmartCreditsPolicy.liveScanEligible(10_000, 300_000, true, false))
    }
    @Test fun cachedFallbackUsesThumbnailGrid() {
        assertEquals(2_430_000L, SmartCreditsPolicy.gridPosition(2_433_000L, 30))
        assertEquals(2_420_000L, SmartCreditsPolicy.gridPosition(2_433_000L, 20))
        assertEquals(0L, SmartCreditsPolicy.gridPosition(-5, 10))
    }
    @Test fun fullCacheRequiresEveryFrameAndKnownPositiveTotal() {
        assertFalse(SmartCreditsPolicy.fullCacheReady(null, null))
        assertFalse(SmartCreditsPolicy.fullCacheReady(0, 0))
        assertFalse(SmartCreditsPolicy.fullCacheReady(null, 89))
        assertFalse(SmartCreditsPolicy.fullCacheReady(89, null))
        assertFalse(SmartCreditsPolicy.fullCacheReady(10, 89))
        assertFalse(SmartCreditsPolicy.fullCacheReady(88, 89))
        assertTrue(SmartCreditsPolicy.fullCacheReady(89, 89))
        assertTrue(SmartCreditsPolicy.fullCacheReady(90, 89))
    }
    @Test fun waitsForEveryTailFrameRegardlessOfOtherCachedFrames() {
        val targets = listOf(400_000L, 410_000L, 420_000L)
        assertFalse(SmartCreditsPolicy.tailFramesReady(emptyList(), emptySet()))
        assertFalse(SmartCreditsPolicy.tailFramesReady(targets, setOf(0L, 400_000L, 410_000L)))
        assertTrue(SmartCreditsPolicy.tailFramesReady(targets, targets.toSet()))
        assertTrue(SmartCreditsPolicy.tailFramesReady(targets, targets.toSet() + 0L))
    }

    @Test fun reportsWhyOcrWasRejected() {
        assertEquals("no_recognized_text", SmartCreditsPolicy.classificationReason(""))
        assertEquals("insufficient_text_lines", SmartCreditsPolicy.classificationReason("Directed by"))
        assertEquals("insufficient_letters", SmartCreditsPolicy.classificationReason("AB\nCD"))
        assertEquals("no_credits_keywords", SmartCreditsPolicy.classificationReason("A dramatic scene\nwith dialogue"))
        assertEquals("credits_keywords", SmartCreditsPolicy.classificationReason("Directed by\nJane Smith"))
    }
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
    @Test fun singleCreditFrameOffersOneIntervalEarlier() {
        val targets = listOf(400_000L, 410_000L, 420_000L, 430_000L)
        assertEquals(400_000L, SmartCreditsPolicy.promptAt(targets, mapOf(410_000L to true), 10))
        assertEquals(400_000L, SmartCreditsPolicy.promptAt(targets, mapOf(410_000L to true, 420_000L to true), 10))
        assertEquals(370_000L, SmartCreditsPolicy.promptAt(targets, mapOf(400_000L to true), 30))
        assertNull(SmartCreditsPolicy.promptAt(targets, targets.associateWith { false }, 10))
    }
    @Test fun sparseCreditsNeedPointTwoPercentAndTwoBands() {
        assertTrue(SmartCreditsPolicy.ImageAssessment(0.9848, 0.00368, 4).credits)
        assertTrue(SmartCreditsPolicy.ImageAssessment(0.88, 0.002, 2).credits)
        assertFalse(SmartCreditsPolicy.ImageAssessment(0.99, 0.00199, 4).credits)
        assertFalse(SmartCreditsPolicy.ImageAssessment(0.99, 0.004, 1).credits)
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
