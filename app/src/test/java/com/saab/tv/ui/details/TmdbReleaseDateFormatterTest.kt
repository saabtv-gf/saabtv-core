package com.saab.tv.ui.details

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TmdbReleaseDateFormatterTest {
    @Test fun formatsIsoDateForTheSelectedLocale() {
        assertEquals("Oct 14, 2026", formatTmdbReleaseDate("2026-10-14", Locale.US))
    }

    @Test fun preservesValuesThatAreNotIsoDates() {
        assertEquals("TBA", formatTmdbReleaseDate("TBA", Locale.US))
    }
}
