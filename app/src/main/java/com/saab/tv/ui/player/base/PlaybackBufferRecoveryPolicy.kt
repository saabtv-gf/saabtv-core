package com.saab.tv.ui.player.base

import kotlin.math.abs

/** Detects a genuine playback stall without reacting to ordinary short rebuffering. */
internal class PlaybackBufferRecoveryPolicy(
    private val stallThresholdMs: Long = 12_000L,
    private val recoveryCooldownMs: Long = 30_000L,
    private val meaningfulMovementMs: Long = 750L
) {
    private var anchorPositionMs: Long? = null
    private var anchorElapsedMs: Long = 0L
    private var lastRecoveryElapsedMs: Long? = null

    fun shouldRecover(
        nowMs: Long,
        positionMs: Long,
        isBuffering: Boolean,
        playWhenReady: Boolean
    ): Boolean {
        if (!isBuffering || !playWhenReady) {
            anchorPositionMs = null
            anchorElapsedMs = 0L
            return false
        }

        val anchor = anchorPositionMs
        if (anchor == null || abs(positionMs - anchor) >= meaningfulMovementMs) {
            anchorPositionMs = positionMs
            anchorElapsedMs = nowMs
            return false
        }

        if (nowMs - anchorElapsedMs < stallThresholdMs) return false
        val lastRecovery = lastRecoveryElapsedMs
        if (lastRecovery != null && nowMs - lastRecovery < recoveryCooldownMs) return false

        lastRecoveryElapsedMs = nowMs
        anchorPositionMs = positionMs
        anchorElapsedMs = nowMs
        return true
    }

    fun reset() {
        anchorPositionMs = null
        anchorElapsedMs = 0L
        lastRecoveryElapsedMs = null
    }
}
