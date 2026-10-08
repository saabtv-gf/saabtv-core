package com.saab.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonInitialsTest {
    @Test fun usesAtMostTwoNameInitials() {
        assertEquals("JJ", initialsForPerson("Jack Jones"))
        assertEquals("MJ", initialsForPerson("  Mary   Jane Watson "))
    }

    @Test fun handlesSingleOrMissingNames() {
        assertEquals("J", initialsForPerson("Jack"))
        assertEquals("?", initialsForPerson("   "))
    }
}
