package com.saab.tv.data.subtitle

/** Native ALASS alignment and WebRTC voice activity detection. No video frames are decoded here. */
internal object AlassBridge {
    val available: Boolean = runCatching {
        System.loadLibrary("saab_subtitle_sync")
    }.isSuccess

    external fun align(referenceSpansMs: LongArray, subtitleSpansMs: LongArray): DoubleArray
    external fun speechFrames(pcm8khz: ShortArray): ByteArray
}
