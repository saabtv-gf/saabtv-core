package com.saab.tv.ui.player.base

/** Conservative, local image heuristic; not an authoritative credits marker. */
internal object SmartCreditsPolicy {
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

    fun looksLikeCredits(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width < 32 || height < 24) return false
        var dark = 0
        var bright = 0
        var textBands = 0
        var inBand = false
        var bandHeight = 0
        var bandHasLetters = false
        fun finishBand() {
            if (bandHeight in 1..8 && bandHasLetters) textBands++
            bandHeight = 0; bandHasLetters = false
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
                if (white) { rowBright++; bright++; if (!previousBright) runs++ }
                previousBright = white
            }
            val textRow = rowBright in 4..(width / 2)
            if (textRow) {
                bandHeight++
                bandHasLetters = bandHasLetters || runs >= 3
            } else if (inBand) finishBand()
            inBand = textRow
        }
        if (inBand) finishBand()
        val count = width * height
        return dark.toDouble() / count >= 0.88 && bright.toDouble() / count in 0.008..0.12 && textBands >= 3
    }
}
