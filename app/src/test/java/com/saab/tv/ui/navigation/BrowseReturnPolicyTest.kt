package com.saab.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseReturnPolicyTest {
    @Test fun detailsCannotBecomeItsOwnBackDestination() {
        assertEquals("menu", BrowseReturnPolicy.forPlayback("details", "details"))
        assertEquals("menu", BrowseReturnPolicy.onBack("details"))
    }

    @Test fun playbackPreservesBrowseDestination() {
        assertEquals("grid", BrowseReturnPolicy.forPlayback("details", "grid"))
        assertEquals("menu", BrowseReturnPolicy.forPlayback("menu", "details"))
        assertEquals("grid", BrowseReturnPolicy.forPlayback("grid", "menu"))
    }

    @Test fun playbackRoutesNeverTrapBack() {
        for (route in listOf("player", "resume", "details", "unknown")) {
            assertEquals("menu", BrowseReturnPolicy.onBack(route))
        }
    }
}
