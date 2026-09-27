package com.saab.tv.ui.player

internal data class WatchProgressEvaluation(
    val storedDurationMs: Long,
    val isCompleted: Boolean
)

internal object WatchProgressPolicy {
    const val MIN_WATCHED_POSITION_MS = 300_000L

    fun preserveWatched(completedNow: Boolean, alreadyWatched: Boolean): Boolean = completedNow || alreadyWatched

    fun evaluate(
        positionMs: Long,
        reportedDurationMs: Long?,
        existingDurationMs: Long?,
        watchedThreshold: Double,
        forceCompleted: Boolean = false
    ): WatchProgressEvaluation {
        val position = positionMs.coerceAtLeast(0L)
        val knownDuration = listOfNotNull(
            reportedDurationMs?.takeIf { it > 0L },
            existingDurationMs?.takeIf { it > 0L }
        ).maxOrNull()
        val storedDuration = knownDuration?.coerceAtLeast(position) ?: 0L
        val reachedMinimumWatchTime = position >= MIN_WATCHED_POSITION_MS
        val isCompleted = reachedMinimumWatchTime && (forceCompleted || if (storedDuration > 0L) {
            val ratio = position.toDouble() / storedDuration.toDouble()
            ratio >= watchedThreshold || storedDuration - position <= 30_000L
        } else {
            false
        })

        return WatchProgressEvaluation(
            storedDurationMs = storedDuration,
            isCompleted = isCompleted
        )
    }

    fun canResume(
        positionMs: Long,
        durationMs: Long,
        isWatched: Boolean,
        watchedThreshold: Double
    ): Boolean {
        if (isWatched || positionMs <= 0L) return false
        if (durationMs <= 0L) return true
        return positionMs.toDouble() / durationMs.toDouble() < watchedThreshold &&
            durationMs - positionMs > 30_000L
    }
}
