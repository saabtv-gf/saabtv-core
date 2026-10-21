package com.saab.tv.ui.player.base

/** Keeps transient network failures and invalid source formats on the source-recovery path. */
internal object PlaybackErrorPolicy {
    fun shouldRetryCurrentSource(errorCodeName: String): Boolean {
        val code = errorCodeName.uppercase()
        return code == "ERROR_CODE_TIMEOUT" || code.startsWith("ERROR_CODE_IO_")
    }

    fun shouldTryNextSource(errorCodeName: String): Boolean {
        val code = errorCodeName.uppercase()
        return shouldRetryCurrentSource(code) || code.contains("MISPARSED") || code.contains("PARSING")
    }

    fun userMessage(errorCodeName: String, fallbackMessage: String?): String {
        val code = errorCodeName.uppercase()
        return when {
            code.contains("TIMEOUT") -> "This source timed out. Try another source."
            code.contains("MISPARSED") || code.contains("PARSING") ->
                "This source has an unreadable media format. Try another source."
            else -> fallbackMessage?.takeIf(String::isNotBlank) ?: "Playback could not start. Try another source."
        }
    }
}

/** Source options are already ordered best-first by the stream ranking pipeline. */
internal fun nextSourceAfter(
    sources: List<PlayerSourceOption>,
    currentSourceId: String?
): PlayerSourceOption? {
    if (sources.size < 2) return null
    val currentIndex = sources.indexOfFirst { it.id == currentSourceId }
    val startIndex = if (currentIndex >= 0) currentIndex + 1 else 0
    return sources.drop(startIndex).firstOrNull { it.id != currentSourceId }
}
