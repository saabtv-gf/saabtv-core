package com.saab.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class DiagnosticPolicyBranchRegressionTest {
    @Test fun loggingMatrixNeverTreatsHandledNetworkFailuresAsCrashes() {
        for (basic in listOf(false, true)) for (detailed in listOf(false, true)) {
            assertEquals(basic || detailed, DiagnosticRecordingPolicy.records(basic, detailed, "Playback"))
            assertEquals(detailed, DiagnosticRecordingPolicy.records(basic, detailed, "Network"))
            assertEquals(detailed, DiagnosticRecordingPolicy.records(basic, detailed, "Playback", true))
        }
        assertTrue(DiagnosticRecordingPolicy.fatal("Application", "Uncaught Exception"))
        assertFalse(DiagnosticRecordingPolicy.fatal("Application", "Request Failed"))
        assertFalse(DiagnosticRecordingPolicy.fatal("Network", "Uncaught Exception"))
    }
    @Test fun cancellationDetectionIncludesWrappedCancelledHttpButNotOtherIoFailures() {
        assertTrue(DiagnosticRecordingPolicy.cancellation(CancellationException()))
        assertTrue(DiagnosticRecordingPolicy.cancellation(IOException("cAnCeLeD")))
        assertTrue(DiagnosticRecordingPolicy.cancellation(IllegalStateException(IOException("Canceled"))))
        assertFalse(DiagnosticRecordingPolicy.cancellation(IOException()))
        assertFalse(DiagnosticRecordingPolicy.cancellation(IOException("timeout")))
        assertFalse(DiagnosticRecordingPolicy.cancellation(IllegalStateException("Canceled")))
        var nested: Throwable = CancellationException()
        repeat(5) { nested = IllegalStateException(nested) }
        assertFalse(DiagnosticRecordingPolicy.cancellation(nested))
    }
}
