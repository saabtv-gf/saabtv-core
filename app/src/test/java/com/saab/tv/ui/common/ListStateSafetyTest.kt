package com.saab.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ListStateSafetyTest {
    @Test
    fun clampsStaleRestoredIndexToLastItem() {
        assertEquals(1, ListStateSafety.boundedIndex(requestedIndex = 80, itemCount = 2))
    }
}
