package com.saab.tv.ui.player.base

import org.junit.Assert.*
import org.junit.Test

class SubtitleTrackIdentityTest {
    private val ids = setOf(externalSubtitleTrackId("1"), externalSubtitleTrackId("en"))

    @Test fun numericEmbeddedIdsCannotMatchAnAddon() {
        assertNull(matchExternalSubtitleTrackId("1", ids))
        assertNull(matchExternalSubtitleTrackId("s:1", ids))
        assertNull(matchExternalSubtitleTrackId("0:1", ids))
        assertNull(matchExternalSubtitleTrackId("en", ids))
    }

    @Test fun externalIdentitySurvivesMediaSourceMerging() {
        assertEquals("s:external:1", matchExternalSubtitleTrackId("s:external:1", ids))
        assertEquals("s:external:1", matchExternalSubtitleTrackId("2:s:external:1", ids))
        assertEquals("s:external:en", matchExternalSubtitleTrackId("0:2:s:external:en", ids))
        assertNull(matchExternalSubtitleTrackId("embedded:s:external:en", ids))
        assertEquals("s:external:1", externalSubtitleTrackId("s:external:1"))
    }
}
