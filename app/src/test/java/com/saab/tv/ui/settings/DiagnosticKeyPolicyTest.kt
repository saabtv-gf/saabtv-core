package com.saab.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class DiagnosticKeyPolicyTest {
    @Test fun remoteOkAndKeyboardEnterAreConsumed() {
        listOf(23, 66, 160).forEach { assertTrue(isDiagnosticActivationKey(it)) }
    }
    @Test fun navigationAndBackRemainAvailable() {
        listOf(19, 20, 21, 22, 4).forEach { assertFalse(isDiagnosticActivationKey(it)) }
    }
}
