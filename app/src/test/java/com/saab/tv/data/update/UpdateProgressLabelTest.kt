package com.saab.tv.data.update

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class UpdateProgressLabelTest {
    @Test fun everyDownloadPercentageFormatsWithoutAnException() {
        for (percent in 0..100) {
            val text = updateProgressLabel(percent / 100f, 12.5f, 63f)
            assertTrue(text.contains("%"))
            assertTrue(text.contains("MB"))
        }
    }
    @Test fun unknownSizeHasNoPercentage() {
        assertFalse(updateProgressLabel(-1f, 1.5f, 0f).contains("%"))
    }
    @Test fun localizedDecimalSeparatorsAreSupported() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Downloading… 50% · 12,5 / 25,0 MB", updateProgressLabel(0.5f, 12.5f, 25f))
        } finally { Locale.setDefault(original) }
    }
}
