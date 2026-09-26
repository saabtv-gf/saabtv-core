package com.saab.tv.ui.components

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

private object CardFocusSoundPlayer {
    private const val MIN_INTERVAL_MS = 45L
    private const val SAMPLE_RATE = 44_100
    private const val DURATION_SECONDS = 0.036
    private var lastPlayedAt = 0L

    private val audioTrack: AudioTrack? by lazy {
        runCatching {
            val sampleCount = (SAMPLE_RATE * DURATION_SECONDS).toInt()
            val samples = ShortArray(sampleCount) { index ->
                val time = index.toDouble() / SAMPLE_RATE
                val attack = (time / 0.002).coerceIn(0.0, 1.0)
                val decay = exp(-time * 92.0)
                val tone = sin(2.0 * PI * 1_080.0 * time) +
                    (0.24 * sin(2.0 * PI * 1_620.0 * time))
                (tone * attack * decay * Short.MAX_VALUE * 0.13)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
                .also { track ->
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
        }.getOrNull()
    }

    @Synchronized
    fun play() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayedAt < MIN_INTERVAL_MS) return
        lastPlayedAt = now

        val track = audioTrack ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.setPlaybackHeadPosition(0)
            track.play()
        }
    }
}

/** Plays SaabTv's own media-stream navigation cue whenever a card gains focus. */
@Composable
fun Modifier.cardFocusSound(): Modifier = onFocusChanged { focusState ->
    if (focusState.isFocused) CardFocusSoundPlayer.play()
}
