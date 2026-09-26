package com.saab.tv.ui.player.base

/** Always presents five scene cards; the configured interval controls their spacing. */
internal object SeekThumbnailCarouselPolicy {
    private const val MAX_CARDS = 5

    fun cardCount(intervalSeconds: Int): Int = MAX_CARDS

    fun offsets(intervalSeconds: Int): List<Int> {
        val half = cardCount(intervalSeconds) / 2
        return (-half..half).toList()
    }

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
}
