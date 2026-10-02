package com.saab.tv

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecordingPolicyTest {
    @Test fun defaultDoesNotRecordRoutineOrHandledEvents() {
        listOf("Network", "TorBox", "Trailer", "Updater", "Diagnostics").forEach {
            assertFalse(DiagnosticRecordingPolicy.records(false, false, it))
        }
    }
    @Test fun basicKeepsSummariesButNotNetworkOrDetailedEvents() {
        assertTrue(DiagnosticRecordingPolicy.records(true, false, "Trailer"))
        assertFalse(DiagnosticRecordingPolicy.records(true, false, "Network"))
        assertFalse(DiagnosticRecordingPolicy.records(true, false, "Trailer", true))
        assertTrue(DiagnosticRecordingPolicy.records(false, true, "Network", true))
    }
    @Test fun onlyUncaughtApplicationExceptionIsFatal() {
        assertTrue(DiagnosticRecordingPolicy.fatal("Application", "Uncaught Exception"))
        assertFalse(DiagnosticRecordingPolicy.fatal("Network", "Request Failed"))
        assertFalse(DiagnosticRecordingPolicy.fatal("Trailer Preview", "Playback Failed"))
    }
    @Test fun cancellationIsNotAHandledFailure() {
        assertTrue(DiagnosticRecordingPolicy.cancellation(java.util.concurrent.CancellationException()))
        assertTrue(DiagnosticRecordingPolicy.cancellation(java.io.IOException("Canceled")))
        assertTrue(DiagnosticRecordingPolicy.cancellation(RuntimeException(java.util.concurrent.CancellationException())))
        assertFalse(DiagnosticRecordingPolicy.cancellation(java.net.SocketTimeoutException()))
    }
}
