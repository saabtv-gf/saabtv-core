package com.saab.tv.ui.player.base

/** Always presents five scene cards; the configured interval controls their spacing. */
internal object SeekThumbnailCarouselPolicy {
    private const val MAX_CARDS = 5

    fun cardCount(intervalSeconds: Int): Int = MAX_CARDS

    fun offsets(intervalSeconds: Int): List<Int> {
        val half = cardCount(intervalSeconds) / 2
        return (-half..half).toList()
    }

    /** Excludes out-of-range scene slots instead of rendering empty edge cards. */
    fun positions(targetMs: Long, durationMs: Long, intervalSeconds: Int): List<Long> {
        val intervalMs = intervalSeconds.coerceAtLeast(1) * 1_000L
        return offsets(intervalSeconds).map { targetMs + it * intervalMs }
            .filter { it >= 0L && (durationMs <= 0L || it <= durationMs) }
    }

    fun selectedIndex(positions: List<Long>, targetMs: Long): Int =
        positions.indexOf(targetMs).takeIf { it >= 0 } ?: positions.size / 2

    fun loadOrder(cardCount: Int): List<Int> {
        if (cardCount <= 0) return emptyList()
        val center = cardCount / 2
        return buildList {
            add(center)
            for (distance in 1..center) {
                add(center - distance)
                if (center + distance < cardCount) add(center + distance)
            }
        }
    }

    /** Reuses visible cached frames that overlap the next seek window. */
    fun retainedFrameIndices(previous: List<Long?>, requested: List<Long?>): List<Int?> {
        val previousIndex = previous.withIndex().associate { it.value to it.index }
        return requested.map { position -> position?.let(previousIndex::get) }
    }
}
