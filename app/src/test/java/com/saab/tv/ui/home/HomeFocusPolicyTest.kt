package com.saab.tv.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFocusPolicyTest {
    @Test fun uninitializedFocusTargetDoesNotCrashHome() {
        assertFalse(requestFocusIfReady { throw IllegalStateException("FocusRequester is not initialized") })
    }

    @Test fun reportsReadyAfterARequestCompletes() {
        assertTrue(requestFocusIfReady { })
    }
}
