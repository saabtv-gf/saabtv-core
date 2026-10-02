package com.saab.tv.data.trailer

import org.junit.Assert.*
import org.junit.Test

class TrailerCodecPreferenceTest {
    private fun avc(height: Int = 1080) = TrailerPlaybackVariant("avc$height", height = height,
        codec = "avc1.640028", width = height * 16 / 9)
    private fun vp9(height: Int = 1080, fps: Int = 24) = TrailerPlaybackVariant("vp9$height", height = height,
        codec = "vp9", fps = fps, width = height * 16 / 9)

    @Test fun supportedVp9WinsSameResolutionAndKeepsAvcFallback() {
        val eligible = TrailerPolicy.hardwareVp9Candidates(listOf(avc(), vp9())) { "c2.vendor.vp9.decoder" }
        val ranked = TrailerPolicy.rank(eligible)
        assertEquals(listOf("vp91080", "avc1080"), ranked.map { it.videoUrl })
        assertEquals("c2.vendor.vp9.decoder", ranked.first().hardwareDecoder)
    }
    @Test fun lowerResolutionVp9CannotBeatHigherResolutionAvc() {
        val eligible = TrailerPolicy.hardwareVp9Candidates(listOf(vp9(720), avc())) { "hardware" }
        assertEquals("avc1080", TrailerPolicy.rank(eligible).first().videoUrl)
    }
    @Test fun unsupportedVp9IsRemovedInsteadOfUsingSoftware() {
        val eligible = TrailerPolicy.hardwareVp9Candidates(listOf(vp9(), avc())) { null }
        assertEquals(listOf("avc1080"), eligible.map { it.videoUrl })
    }
    @Test fun capabilityIsCheckedPerResolutionAndFrameRate() {
        val eligible = TrailerPolicy.hardwareVp9Candidates(listOf(vp9(2160, 60), vp9(), avc())) {
            if (it.height <= 1080 && it.fps <= 30) "hardware" else null
        }
        assertEquals(listOf("vp91080", "avc1080"), TrailerPolicy.rank(eligible).map { it.videoUrl })
    }
    @Test fun unverifiedVp9DoesNotGainPreference() {
        assertEquals("avc1080", TrailerPolicy.rank(listOf(vp9(), avc())).first().videoUrl)
    }
    @Test fun vp9CodecStringsAreRecognizedWithoutMatchingAv1() {
        assertTrue(TrailerPolicy.isVp9("vp09.00.41.08"))
        assertTrue(TrailerPolicy.isVp9("video/webm; codecs=\"vp9\""))
        assertFalse(TrailerPolicy.isVp9("av01.0.08M.08"))
        assertFalse(TrailerPolicy.isVp9("avc1.640028"))
    }
    @Test fun removingFailedPrimaryLeavesAvcAheadOfLowerResolutionVp9() {
        val ranked = TrailerPolicy.rank(TrailerPolicy.hardwareVp9Candidates(listOf(vp9(720), avc(), vp9())) { "hardware" })
        assertEquals("avc1080", TrailerPolicy.rank(ranked.drop(1)).first().videoUrl)
    }
}
