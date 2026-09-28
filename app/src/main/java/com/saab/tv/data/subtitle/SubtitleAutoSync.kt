package com.saab.tv.data.subtitle

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteOrder
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Best-effort ALASS audio/subtitle alignment. All work is bounded, off the playback thread,
 * and a weak or ambiguous match is deliberately ignored. The caller must cancel on source or
 * subtitle changes. The selected video is never paused or reconfigured.
 */
internal object SubtitleAutoSync {
    private const val MAX_SUBTITLE_BYTES = 1_500_000
    private const val MAX_REFERENCE_MS = 8 * 60_000L
    private const val MAX_WORK_MS = 35_000L
    private const val MAX_OFFSET_MS = 10_000L
    private val timeCode = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    data class Result(val offsetMs: Long, val improvement: Double, val analyzedMs: Long)

    suspend fun align(mediaUrl: String, subtitleUrl: String): Result? {
        if (!AlassBridge.available || !mediaUrl.startsWith("http") || !subtitleUrl.startsWith("http")) return null
        val subtitles = readSubtitleSpans(subtitleUrl)
        if (subtitles.size < 40) return null
        coroutineContext.ensureActive()
        val reference = extractSpeechSpans(mediaUrl)
        coroutineContext.ensureActive()
        if (reference.size < 40) return null
        val analyzedMs = reference.last().second
        val candidateSubtitles = subtitles.filter { it.first < analyzedMs && it.second <= analyzedMs + 2_000L }
        if (candidateSubtitles.size < 35) return null
        val raw = AlassBridge.align(reference.flatten(), candidateSubtitles.flatten())
        val offsetMs = raw.getOrNull(0)?.takeIf { it.isFinite() }?.toLong() ?: return null
        if (offsetMs == 0L || offsetMs !in -MAX_OFFSET_MS..MAX_OFFSET_MS) return null
        val baseline = overlapRatio(reference, candidateSubtitles, 0L)
        val aligned = overlapRatio(reference, candidateSubtitles, offsetMs)
        val improvement = aligned - baseline
        if (aligned < 0.35 || improvement < 0.10) return null
        return Result(offsetMs, improvement, analyzedMs)
    }

    private fun List<Pair<Long, Long>>.flatten(): LongArray = LongArray(size * 2).also { values ->
        forEachIndexed { index, span ->
            values[index * 2] = span.first
            values[index * 2 + 1] = span.second
        }
    }

    private fun readSubtitleSpans(url: String): List<Pair<Long, Long>> {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        val bytes = connection.getInputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_SUBTITLE_BYTES) return emptyList()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return parseSubtitleSpans(bytes.toString(Charsets.UTF_8))
    }

    internal fun parseSubtitleSpans(text: String): List<Pair<Long, Long>> =
        timeCode.findAll(text).mapNotNull { match ->
            val parts = match.groupValues.drop(1).mapNotNull(String::toLongOrNull)
            if (parts.size != 8) return@mapNotNull null
            fun millis(start: Int): Long = ((parts[start] * 60 + parts[start + 1]) * 60 + parts[start + 2]) * 1000 +
                parts[start + 3].let { fraction -> if (match.groupValues[start + 4].length == 2) fraction * 10 else fraction }
            val begin = millis(0)
            val end = millis(4)
            if (end > begin && end - begin <= 15_000L) begin to end else null
        }.toList()

    private suspend fun extractSpeechSpans(url: String): List<Pair<Long, Long>> {
        val oldPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val voiceFrames = ArrayList<Boolean>(48_000)
        val pcm = ShortArray(16_000)
        var pcmCount = 0
        var sampleRate = 48_000
        var channels = 2
        var phase = 0
        val started = SystemClock.elapsedRealtime()
        try {
            extractor.setDataSource(url)
            val audioIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(audioIndex)
            val format = extractor.getTrackFormat(audioIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (SystemClock.elapsedRealtime() - started < MAX_WORK_MS && voiceFrames.size < MAX_REFERENCE_MS / 10) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: break
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > MAX_REFERENCE_MS * 1_000L) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                        ) return emptyList()
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    in 0..Int.MAX_VALUE -> {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0 && sampleRate >= 8_000 && channels in 1..8) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val shorts = output.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var mono = 0
                                repeat(channels) { mono += shorts.get().toInt() / channels }
                                phase += 8_000
                                if (phase >= sampleRate) {
                                    phase -= sampleRate
                                    pcm[pcmCount++] = mono.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                }
                                if (pcmCount >= pcm.size) {
                                    val frames = AlassBridge.speechFrames(pcm)
                                    frames.forEach { voiceFrames.add(it.toInt() != 0) }
                                    pcmCount = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (pcmCount >= 80) {
                AlassBridge.speechFrames(pcm.copyOf(pcmCount)).forEach { voiceFrames.add(it.toInt() != 0) }
            }
            return voiceSpans(voiceFrames)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            Process.setThreadPriority(oldPriority)
        }
    }

    private fun voiceSpans(frames: List<Boolean>): List<Pair<Long, Long>> {
        val spans = ArrayList<Pair<Long, Long>>()
        var start = -1
        frames.forEachIndexed { index, voice ->
            if (voice && start < 0) start = index
            if ((!voice || index == frames.lastIndex) && start >= 0) {
                val end = if (voice) index + 1 else index
                if (end - start >= 10) spans.add(start * 10L to end * 10L)
                start = -1
            }
        }
        return spans
    }

    internal fun overlapRatio(reference: List<Pair<Long, Long>>, subtitles: List<Pair<Long, Long>>, shift: Long): Double {
        var covered = 0L
        var total = 0L
        var referenceIndex = 0
        for ((originalStart, originalEnd) in subtitles) {
            val start = max(0L, originalStart + shift)
            val end = originalEnd + shift
            if (end <= start) continue
            total += end - start
            while (referenceIndex < reference.size && reference[referenceIndex].second <= start) referenceIndex++
            var index = referenceIndex
            while (index < reference.size && reference[index].first < end) {
                covered += (minOf(end, reference[index].second) - max(start, reference[index].first)).coerceAtLeast(0L)
                index++
            }
        }
        return if (total > 0) covered.toDouble() / total else 0.0
    }
}
