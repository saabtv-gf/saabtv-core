package com.saab.tv.ui.player

import com.saab.tv.ui.player.base.PlayerUiState

/** Boundary saves include the start of an episode, but never an unstarted backend. */
internal object ProgressSnapshotPolicy {
    data class LifecycleSnapshot(
        val positionMs: Long,
        val durationMs: Long?,
        val playbackEstablished: Boolean
    )

    fun shouldSave(positionMs: Long, boundary: Boolean, playbackEstablished: Boolean): Boolean =
        playbackEstablished && (boundary || positionMs >= 5_000L)

    fun terminalPosition(positionMs: Long, durationMs: Long): Long =
        maxOf(0L, positionMs, durationMs)

    fun onLifecycleStop(state: PlayerUiState) = LifecycleSnapshot(
        positionMs = if (state.isEnded) terminalPosition(state.positionMs, state.durationMs)
            else state.positionMs.coerceAtLeast(0L),
        durationMs = state.durationMs.takeIf { it > 0L },
        playbackEstablished = state.isReady || state.hasRenderedFirstFrame || state.isEnded
    )
}
