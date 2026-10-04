package com.saab.tv.ui.player.base

/** Local OCR policy for finding a likely credits run; it never advances playback. */
internal object SmartCreditsPolicy {
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
        val first = targets.zipWithNext().firstOrNull { (a, b) ->
            classifications[a] == true && classifications[b] == true
        }?.first ?: return null
        return (first - intervalSeconds.coerceIn(10, 30) * 1_000L).coerceAtLeast(targets.first())
    }

    fun nextMissingTarget(targets: List<Long>, classifications: Map<Long, Boolean>): Long? =
        targets.firstOrNull { it !in classifications }

    fun looksLikeCreditsText(text: String): Boolean {
        val lines = text.lineSequence()
            .map { it.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }
            .filter { line -> line.count { it.isLetter() } >= 2 }
            .toList()
        if (lines.size < 2 || lines.sumOf { line -> line.count { it.isLetter() } } < 10) return false
        val normalized = lines.joinToString(" ")
        return creditRoles.any { role ->
            Regex("\\b${Regex.escape(role)}\\b").containsMatchIn(normalized)
        }
    }
}
