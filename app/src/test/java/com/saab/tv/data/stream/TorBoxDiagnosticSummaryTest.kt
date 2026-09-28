package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TorBoxDiagnosticSummaryTest {
    @Test fun recordsOnlyAggregateSourceState() {
        val url = "https://private.example/secret"
        val summary = TorBoxDiagnosticSummary.sources(listOf(
            Stream(name = "Torrentio", url = url, seeders = 12),
            Stream(name = "MediaFusion", seeders = 4, torBoxChecked = true, torBoxSeeders = 0),
            Stream(name = "Direct", url = "https://cdn.example/video")
        ))

        assertEquals("sources=3 torrentSources=2 notChecked=1 checked=1 cacheUnknown=1 torBoxSeeders=1 providerFallback=1", summary)
        assertFalse(summary.contains(url))
    }
}
