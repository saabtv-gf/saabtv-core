package com.saab.tv.ui.player.base

/**
 * Detects a sustained burst of dropped video frames without reacting to normal
 * startup, seek, or occasional decoder drops. The caller performs the actual
 * renderer flush when this policy returns true.
 */
internal class VideoJitterRecoveryPolicy(
    private val warmupMs: Long = 10_000L,
    private val windowMs: Long = 4_000L,
    private val droppedFrameThreshold: Int = 12,
    private val recoveryCooldownMs: Long = 30_000L
) {
    private var windowStartedAtMs: Long = 0L
    private var droppedFramesInWindow: Int = 0
    private var lastRecoveryAtMs: Long = Long.MIN_VALUE

    fun shouldRecover(
        nowMs: Long,
        sourcePreparedAtMs: Long,
        droppedFrames: Int,
        eligible: Boolean
    ): Boolean {
        if (!eligible || droppedFrames <= 0 || nowMs - sourcePreparedAtMs < warmupMs) {
            resetWindow()
            return false
        }

        if (windowStartedAtMs == 0L || nowMs - windowStartedAtMs > windowMs) {
            windowStartedAtMs = nowMs
            droppedFramesInWindow = 0
        }
        droppedFramesInWindow += droppedFrames

        val cooldownComplete = lastRecoveryAtMs == Long.MIN_VALUE ||
            nowMs - lastRecoveryAtMs >= recoveryCooldownMs
        if (droppedFramesInWindow < droppedFrameThreshold || !cooldownComplete) return false

        lastRecoveryAtMs = nowMs
        resetWindow()
        return true
    }

    fun reset() {
        resetWindow()
        lastRecoveryAtMs = Long.MIN_VALUE
    }

    private fun resetWindow() {
        windowStartedAtMs = 0L
        droppedFramesInWindow = 0
    }
}
