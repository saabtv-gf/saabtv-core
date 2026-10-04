package com.saab.tv.ui.player.base

/** Local OCR policy for finding a likely credits run; it never advances playback. */
internal object SmartCreditsPolicy {
    data class ImageAssessment(val darkFraction: Double, val brightFraction: Double, val textBands: Int) {
        val credits: Boolean get() = darkFraction >= 0.88 && brightFraction in 0.002..0.12 && textBands >= 2
    }

    fun assessImage(width: Int, height: Int, pixel: (Int, Int) -> Int): ImageAssessment {
        if (width < 32 || height < 24) return ImageAssessment(0.0, 0.0, 0)
        var dark = 0
        var bright = 0
        var bands = 0
        var bandHeight = 0
        var bandHasLetters = false
        fun finishBand() {
            if (bandHeight in 1..8 && bandHasLetters) bands++
            bandHeight = 0
            bandHasLetters = false
        }
        for (y in 0 until height) {
            var rowBright = 0
            var runs = 0
            var previousBright = false
            for (x in 0 until width) {
                val color = pixel(x, y)
                val r = (color ushr 16) and 255
                val g = (color ushr 8) and 255
                val b = color and 255
                if (maxOf(r, g, b) < 55) dark++
                val white = minOf(r, g, b) > 145 && maxOf(r, g, b) - minOf(r, g, b) < 65
                if (white) {
                    bright++
                    rowBright++
                    if (!previousBright) runs++
                }
                previousBright = white
            }
            if (rowBright in 4..(width / 2)) {
                bandHeight++
                bandHasLetters = bandHasLetters || runs >= 3
            } else if (bandHeight > 0) finishBand()
        }
        if (bandHeight > 0) finishBand()
        val count = width.toDouble() * height
        return ImageAssessment(dark / count, bright / count, bands)
    }

    fun frameStillCurrent(requestedMs: Long, currentMs: Long, elapsedMs: Long, speed: Float, busy: Boolean): Boolean =
        !busy && kotlin.math.abs(currentMs - requestedMs - (elapsedMs.coerceAtLeast(0) * speed).toLong()) <= 2_500L

    fun liveScanEligible(positionMs: Long, durationMs: Long, playing: Boolean, busy: Boolean): Boolean =
        durationMs > 300_000 && positionMs >= durationMs - 300_000 && positionMs < durationMs && playing && !busy

    fun gridPosition(positionMs: Long, intervalSeconds: Int): Long {
        val intervalMs = intervalSeconds.coerceAtLeast(1) * 1_000L
        return (positionMs.coerceAtLeast(0) / intervalMs) * intervalMs
    }

    // Local heuristic, not a guarantee: repeated dark-background text across
    // several rows supports credits without requiring role keywords.
    fun liveCreditsLayout(darkFraction: Float, lineCount: Int, letterCount: Int, rowSpanFraction: Float): Boolean =
        darkFraction >= 0.65f && lineCount >= 4 && letterCount >= 30 && rowSpanFraction >= 0.15f
    private val creditRoles = listOf(
        "directed by", "director", "written by", "screenplay", "story by",
        "produced by", "producer", "executive producer", "starring", "cast",
        "casting by", "cinematography", "director of photography", "music by",
        "edited by", "editor", "production designer", "production design",
        "visual effects", "sound design", "costume designer", "costume design",
        "special thanks", "crew", "credits"
    )

    fun scanGateReason(
        autoplayEnabled: Boolean,
        thresholdMode: String,
        hasValidOutro: Boolean,
        hasNextEpisode: Boolean,
        hasTransitionCallback: Boolean,
        isTrailer: Boolean,
        hasPlaybackError: Boolean,
        hasFrameProvider: Boolean,
        durationMs: Long
    ): String = when {
        !autoplayEnabled -> "autoplay_disabled"
        thresholdMode != "smart" -> "threshold_mode_$thresholdMode"
        hasValidOutro -> "introdb_outro_available"
        !hasNextEpisode -> "no_next_episode"
        !hasTransitionCallback -> "transition_callback_unavailable"
        isTrailer -> "trailer_playback"
        hasPlaybackError -> "playback_error"
        !hasFrameProvider -> "thumbnail_provider_unavailable_or_disabled"
        durationMs <= 300_000L -> "duration_under_5_minutes"
        else -> "eligible"
    }

    fun targets(durationMs: Long, intervalSeconds: Int): List<Long> {
        if (durationMs <= 300_000L) return emptyList()
        val step = intervalSeconds.coerceIn(10, 30) * 1_000L
        val start = ((durationMs - 300_000L + step - 1) / step) * step
        return generateSequence(start) { it + step }.takeWhile { it < durationMs }.take(31).toList()
    }

    fun promptAt(targets: List<Long>, classifications: Map<Long, Boolean>, intervalSeconds: Int): Long? {
        val first = targets.firstOrNull { classifications[it] == true } ?: return null
        return (first - intervalSeconds.coerceIn(10, 30) * 1_000L).coerceAtLeast(0L)
    }

    fun nextMissingTarget(targets: List<Long>, classifications: Map<Long, Boolean>): Long? =
        targets.firstOrNull { it !in classifications }

    fun fullCacheReady(cachedFrames: Int?, totalFrames: Int?): Boolean =
        totalFrames != null && totalFrames > 0 && cachedFrames != null && cachedFrames >= totalFrames

    fun tailFramesReady(targets: List<Long>, available: Set<Long>): Boolean =
        targets.isNotEmpty() && targets.all { it in available }

    fun looksLikeCreditsText(text: String): Boolean = classificationReason(text) == "credits_keywords"

    fun classificationReason(text: String): String {
        val lines = text.lineSequence()
            .map { it.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }
            .filter { line -> line.count { it.isLetter() } >= 2 }
            .toList()
        if (lines.isEmpty()) return "no_recognized_text"
        if (lines.size < 2) return "insufficient_text_lines"
        if (lines.sumOf { line -> line.count { it.isLetter() } } < 10) return "insufficient_letters"
        val normalized = lines.joinToString(" ")
        return if (creditRoles.any { role ->
            Regex("\\b${Regex.escape(role)}\\b").containsMatchIn(normalized)
        }) "credits_keywords" else "no_credits_keywords"
    }
}
