package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {
    @Test fun timeoutRetriesCurrentSourceAndSuggestsNextSource() {
        assertTrue(PlaybackErrorPolicy.shouldRetryCurrentSource("ERROR_CODE_TIMEOUT"))
        assertTrue(PlaybackErrorPolicy.shouldTryNextSource("ERROR_CODE_TIMEOUT"))
        assertEquals(
            "This source timed out. Try another source.",
            PlaybackErrorPolicy.userMessage("ERROR_CODE_TIMEOUT", "socket timeout")
        )
    }

    @Test fun misparsedRetriesByMovingToNextSourceButDoesNotRetrySameSource() {
        assertFalse(PlaybackErrorPolicy.shouldRetryCurrentSource("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
        assertTrue(PlaybackErrorPolicy.shouldTryNextSource("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
        assertEquals(
            "This source has an unreadable media format. Try another source.",
            PlaybackErrorPolicy.userMessage("ERROR_CODE_MISPARSED", null)
        )
    }

    @Test fun unrelatedErrorsPreserveUsefulFallbackMessage() {
        assertFalse(PlaybackErrorPolicy.shouldTryNextSource("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals("Decoder failed", PlaybackErrorPolicy.userMessage("ERROR_CODE_UNKNOWN", "Decoder failed"))
        assertEquals(
            "Playback could not start. Try another source.",
            PlaybackErrorPolicy.userMessage("ERROR_CODE_UNKNOWN", " ")
        )
    }

    @Test fun nextSourceFollowsBestFirstOrderAndHandlesMissingOrFinalSelection() {
        val sources = listOf(
            PlayerSourceOption("best", "https://fixture.invalid/best", "Best"),
            PlayerSourceOption("next", "https://fixture.invalid/next", "Next"),
            PlayerSourceOption("last", "https://fixture.invalid/last", "Last")
        )

        assertEquals("next", nextSourceAfter(sources, "best")?.id)
        assertEquals("last", nextSourceAfter(sources, "next")?.id)
        assertNull(nextSourceAfter(sources, "last"))
        assertEquals("best", nextSourceAfter(sources, "unknown")?.id)
        assertNull(nextSourceAfter(emptyList(), null))
    }
}
