package com.saab.tv

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPrivacyTest {
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
