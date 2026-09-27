package com.saab.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class LanguageOptionsTest {
    @Test fun malayalamAndKannadaAreAvailableWithoutDuplicates() {
        assertTrue(AUDIO_LANGUAGE_OPTIONS.contains("Malayalam" to "ml"))
        assertTrue(AUDIO_LANGUAGE_OPTIONS.contains("Kannada" to "kn"))
        val codes = AUDIO_LANGUAGE_OPTIONS.map { it.second }
        assertEquals(codes.size, codes.distinct().size)
    }
}
