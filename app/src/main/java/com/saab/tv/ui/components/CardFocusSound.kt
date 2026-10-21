package com.saab.tv.ui.components

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object CardFocusSoundPolicy {
    const val MIN_INTERVAL_MS = 80L

    fun gainedFocus(wasFocused: Boolean, isFocused: Boolean): Boolean =
        !wasFocused && isFocused

    fun mayPlay(lastPlayedAtMs: Long, nowMs: Long): Boolean =
        nowMs - lastPlayedAtMs >= MIN_INTERVAL_MS
}

internal class FocusSoundSuppression {
    private var suppressNext = false

    @Synchronized
    fun suppressNext() { suppressNext = true }

    @Synchronized
    fun shouldPlay(): Boolean {
        if (!suppressNext) return true
        suppressNext = false
        return false
    }

    @Synchronized
    fun clear() { suppressNext = false }
}

private object CardFocusSoundPlayer {
    private const val SAMPLE_RATE = 44_100
    private const val DURATION_SECONDS = 0.036
    private var lastPlayedAt = 0L
    private val pending = AtomicBoolean(false)
    private val focusSoundSuppression = FocusSoundSuppression()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun play() {
        if (!focusSoundSuppression.shouldPlay()) return
        // AudioTrack creation/write/stop can block on AudioFlinger. Never perform
        // these calls in a Compose focus callback, and never queue stale cues.
        if (!pending.compareAndSet(false, true)) return
        scope.launch {
            try { playOnWorker() } finally { pending.set(false) }
        }
    }

    fun suppressNextFocusSound() = focusSoundSuppression.suppressNext()
    fun clearFocusSoundSuppression() = focusSoundSuppression.clear()

    private fun playOnWorker() {
        val now = SystemClock.uptimeMillis()
        if (!CardFocusSoundPolicy.mayPlay(lastPlayedAt, now)) return
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

/** Used when an overlay restores its anchor before handing focus to a neighbor. */
internal fun suppressNextCardFocusSound() = CardFocusSoundPlayer.suppressNextFocusSound()
internal fun clearCardFocusSoundSuppression() = CardFocusSoundPlayer.clearFocusSoundSuppression()

/** Plays SaabTv's own media-stream navigation cue whenever a card gains focus. */
@Composable
fun Modifier.cardFocusSound(): Modifier {
    var wasFocused by remember { mutableStateOf(false) }
    return onFocusChanged { focusState ->
        val gainedFocus = CardFocusSoundPolicy.gainedFocus(wasFocused, focusState.isFocused)
        wasFocused = focusState.isFocused
        if (gainedFocus) CardFocusSoundPlayer.play()
    }
}
