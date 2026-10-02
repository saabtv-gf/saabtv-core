package com.saab.tv.ui.player

/** Boundary saves include the start of an episode, but never an unstarted backend. */
internal object ProgressSnapshotPolicy {
    fun shouldSave(positionMs: Long, boundary: Boolean, playbackEstablished: Boolean): Boolean =
        playbackEstablished && (boundary || positionMs >= 5_000L)

    fun terminalPosition(positionMs: Long, durationMs: Long): Long =
        maxOf(0L, positionMs, durationMs)
}
