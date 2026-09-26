package com.saab.tv.ui.player.base

object SeekIntervalPolicy {
    val supportedSeconds = listOf(10, 20, 30)

    fun normalizeSeconds(seconds: Int): Int =
        seconds.takeIf { it in supportedSeconds } ?: 10

    fun intervalMs(seconds: Int): Long = normalizeSeconds(seconds) * 1_000L
}
