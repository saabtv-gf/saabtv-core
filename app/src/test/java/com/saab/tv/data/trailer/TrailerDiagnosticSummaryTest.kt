package com.saab.tv.data.trailer

import org.junit.Assert.*
import org.junit.Test

class TrailerDiagnosticSummaryTest {
    @Test fun summaryContainsQualityButNoSignedUrlsOrHeaders() {
        val summary = TrailerPlaybackVariant("https://private/video?token=secret", "https://private/audio",
            "1080p", 1080, mapOf("Authorization" to "Bearer secret"), 137, "avc1", 2395944, 24).diagnosticSummary()
        assertTrue(summary.contains("formatId=137"))
        assertTrue(summary.contains("bitrateBps=2395944"))
        assertTrue(summary.contains("codec=avc1"))
        assertTrue(summary.contains("audioSeparate=true"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("Authorization"))
    }
    @Test fun unknownMetadataIsNotInvented() {
        val summary = TrailerPlaybackVariant("video").diagnosticSummary()
        assertTrue(summary.contains("formatId=-1"))
        assertTrue(summary.contains("bitrateBps=-1"))
    }
}
