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
        cause.javaClass.name + " " + cause.stackTrace.take(35).joinToString(" <- ") { frame ->
            "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
        }
    }
}
