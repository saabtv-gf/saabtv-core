package com.saab.tv

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPrivacyTest {
    @Test fun accountErrorsExposeSafeStatusAndKnownCodeWithoutResponseMessages() {
        val trace = DiagnosticPrivacy.stack(com.saab.tv.data.account.AccountApiException(
            429, "private server response token=secret", "PT429"
        ))
        assertTrue(trace.contains("httpStatus=429 serviceCode=PT429"))
        assertFalse(trace.contains("private server response"))
        assertFalse(trace.contains("secret"))
        val unknownCode = DiagnosticPrivacy.stack(com.saab.tv.data.account.AccountApiException(500, "secret", "SECRET_TOKEN"))
        assertFalse(unknownCode.contains("SECRET_TOKEN"))
    }

    @Test fun cryptoAndDetachedFocusAreClassifiedWithoutArbitraryMessages() {
        assertTrue(DiagnosticPrivacy.stack(javax.crypto.AEADBadTagException("private cipher"))
            .contains("category=authentication_tag"))
        val trace = DiagnosticPrivacy.stack(IllegalStateException("FocusRequester is not initialized. private detail"))
        assertTrue(trace.contains("category=focus_target_uninitialized"))
        assertFalse(trace.contains("private detail"))
    }
    @Test fun cancellationIsClassifiedWithoutLeakingMessage() {
        assertTrue(DiagnosticPrivacy.stack(java.io.IOException("Canceled")).contains("category=cancelled"))
        assertTrue(DiagnosticPrivacy.stack(java.net.SocketTimeoutException("secret")).contains("category=timeout"))
        assertFalse(DiagnosticPrivacy.stack(java.net.SocketTimeoutException("secret")).contains("secret"))
    }
    @Test fun credentialsAndUrlsAreRemoved() {
        val result = DiagnosticPrivacy.redact("password=secret token=abc Bearer xyz https://host/private?cap=123 email=user@example.com pin=1234")
        listOf("secret", "abc", "xyz", "host", "123", "user@example.com", "1234").forEach { assertFalse(result.contains(it)) }
    }
    @Test fun failurePreservesFramesWithoutMessages() {
        val error = IllegalStateException("private response password=secret", java.io.IOException("https://secret/path"))
        val trace = DiagnosticPrivacy.stack(error)
        assertTrue(trace.contains("IllegalStateException"))
        assertTrue(trace.contains("IOException"))
        assertTrue(trace.contains("DiagnosticPrivacyTest"))
        assertFalse(trace.contains("private response"))
        assertFalse(trace.contains("secret"))
    }
    @Test fun ordinaryMetricsArePreserved() {
        assertEquals("code=200 ms=120 java=45MB/256MB", DiagnosticPrivacy.redact("code=200 ms=120 java=45MB/256MB"))
    }
    @Test fun jsonCredentialsAndDatabaseLinksAreRemoved() {
        val result = DiagnosticPrivacy.redact("{\"password\":\"secret with spaces\",\"cookie\":\"session=abc\"} postgresql://owner:secret@host/db")
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("session=abc"))
        assertFalse(result.contains("owner"))
    }
}
