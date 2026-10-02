package com.saab.tv

/** Handled failures are not crashes; verbose transport events require Detailed. */
internal object DiagnosticRecordingPolicy {
    fun records(basic: Boolean, detailed: Boolean, component: String, detailedOnly: Boolean = false): Boolean =
        detailed || (basic && !detailedOnly && component != "Network")

    fun fatal(component: String, event: String): Boolean =
        component == "Application" && event == "Uncaught Exception"

    fun cancellation(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.take(5).any {
        it is java.util.concurrent.CancellationException ||
            (it is java.io.IOException && it.message.equals("Canceled", ignoreCase = true))
    }
}
