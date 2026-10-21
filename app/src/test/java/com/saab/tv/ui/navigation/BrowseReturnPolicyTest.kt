package com.saab.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseReturnPolicyTest {
    @Test fun savedRouteStateRestoresLegacyKeysAndDefaultsUnknownValuesToMenu() {
        assertEquals(ShellView.Menu, ShellView.fromSaved("menu"))
        assertEquals(ShellView.Grid, ShellView.fromSaved("grid"))
        assertEquals(ShellView.Details, ShellView.fromSaved("DETAILS"))
        assertEquals(ShellView.Resume, ShellView.fromSaved("resume"))
        assertEquals(ShellView.Player, ShellView.fromSaved("player"))
        assertEquals(ShellView.Menu, ShellView.fromSaved("unrecognized-route"))
    }

    @Test fun detailsCannotBecomeItsOwnBackDestination() {
        assertEquals(ShellView.Menu, BrowseReturnPolicy.forPlayback(ShellView.Details, ShellView.Details))
        assertEquals(ShellView.Menu, BrowseReturnPolicy.onBack(ShellView.Details))
    }

    @Test fun playbackPreservesBrowseDestination() {
        assertEquals(ShellView.Grid, BrowseReturnPolicy.forPlayback(ShellView.Details, ShellView.Grid))
        assertEquals(ShellView.Grid, BrowseReturnPolicy.onBack(ShellView.Grid))
        assertEquals(ShellView.Menu, BrowseReturnPolicy.forPlayback(ShellView.Menu, ShellView.Details))
        assertEquals(ShellView.Grid, BrowseReturnPolicy.forPlayback(ShellView.Grid, ShellView.Menu))
    }

    @Test fun nestedRoutesReturnToTheirBrowseParent() {
        for (route in listOf(ShellView.Details, ShellView.Resume, ShellView.Player)) {
            assertEquals(ShellView.Grid, BrowseReturnPolicy.forPlayback(route, ShellView.Grid))
            assertEquals(ShellView.Menu, BrowseReturnPolicy.forPlayback(route, ShellView.Menu))
        }
    }

    @Test fun everyNonGridReturnStateFallsBackToMenu() {
        for (route in listOf(ShellView.Menu, ShellView.Details, ShellView.Resume, ShellView.Player)) {
            assertEquals(ShellView.Menu, BrowseReturnPolicy.onBack(route))
        }
    }
}
