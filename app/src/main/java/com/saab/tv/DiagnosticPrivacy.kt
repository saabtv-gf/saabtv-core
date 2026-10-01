package com.saab.tv

internal object DiagnosticPrivacy {
    fun redact(value: String): String = value
        .replace(Regex("(?i)(?:https?|postgres(?:ql)?|magnet|file|content)://[^\\s]+"), "[url redacted]")
        .replace(Regex("(?i)\"(password|passwd|token|apikey|api_key|authorization|cookie|username|email|pin|cap|key)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"[redacted]\"")
        .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer [redacted]")
        .replace(Regex("(?i)(password|passwd|token|apikey|api_key|authorization|cookie|username|email|pin|cap|key)\\s*[=:]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email redacted]")

    /** Exception messages may contain credentials or server responses; retain types and frames only. */
    fun stack(error: Throwable): String = generateSequence(error) { it.cause }.take(5).joinToString(" | caused-by ") { cause ->
        cause.javaClass.name + safeFailureCode(cause) + " " + cause.stackTrace.take(35).joinToString(" <- ") { frame ->
            "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
        }
    }

    // Structured, allow-listed fields only; never include arbitrary messages/bodies.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun safeFailureCode(error: Throwable): String = when (error) {
        is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> " httpStatus=${error.responseCode}"
        is androidx.media3.common.PlaybackException -> " playbackCode=${error.errorCode}"
        is java.net.SocketTimeoutException -> " category=timeout"
        is java.net.UnknownHostException -> " category=dns"
        is javax.net.ssl.SSLHandshakeException -> " category=tls_handshake"
        is java.io.IOException -> if (error.message.equals("Canceled", ignoreCase = true)) " category=cancelled" else ""
        else -> ""
    }
}
