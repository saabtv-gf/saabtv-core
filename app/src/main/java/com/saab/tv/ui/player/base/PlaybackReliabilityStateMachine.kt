package com.saab.tv.ui.player.base

internal enum class PlaybackReliabilityPhase {
    IDLE,
    PREPARING,
    BUFFERING,
    READY,
    SEEKING,
    RETRYING,
    FALLING_BACK,
    ENDED,
    FAILED
}

/** Owns retry/fallback progression independently from ExoPlayer callbacks. */
internal class PlaybackReliabilityStateMachine(
    private val maxFallbackSources: Int = 2
) {
    var phase: PlaybackReliabilityPhase = PlaybackReliabilityPhase.IDLE
        private set
    private val attemptedSourceIds = linkedSetOf<String>()
    private var fallbackCount = 0

    fun begin(sourceId: String?) {
        attemptedSourceIds.clear()
        fallbackCount = 0
        sourceId?.takeIf(String::isNotBlank)?.let(attemptedSourceIds::add)
        phase = PlaybackReliabilityPhase.PREPARING
    }

    fun transition(next: PlaybackReliabilityPhase) {
        phase = next
    }

    fun retryCurrent() {
        phase = PlaybackReliabilityPhase.RETRYING
    }

    fun nextFallback(orderedSourceIds: List<String>): String? {
        if (fallbackCount >= maxFallbackSources) return null
        val next = orderedSourceIds.firstOrNull { it.isNotBlank() && it !in attemptedSourceIds }
            ?: return null
        attemptedSourceIds += next
        fallbackCount += 1
        phase = PlaybackReliabilityPhase.FALLING_BACK
        return next
    }
}
