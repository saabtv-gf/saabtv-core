package com.saab.tv.data.repository

import java.util.Locale

internal object SubtitleReleaseMatcher {
    fun score(filename: String?, release: String?): Int {
        if (filename.isNullOrBlank() || release.isNullOrBlank()) return 0
        fun normalize(value: String) = value.lowercase(Locale.ROOT)
            .replace(Regex("\\.(mkv|mp4|avi|srt|ass|vtt)$"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()
        val file = normalize(filename)
        val candidate = normalize(release)
        if (file == candidate) return 10_000
        val tokens = file.split(' ').toSet()
        val other = candidate.split(' ').toSet()
        val episodes = Regex("s\\d{1,2}e\\d{1,3}")
        val episode = episodes.find(file)?.value
        val candidateEpisode = episodes.find(candidate)?.value
        if (episode != null && candidateEpisode != null && episode != candidateEpisode) return -10_000
        val editions = setOf("extended", "unrated", "imax", "theatrical")
        if (tokens.intersect(editions) != other.intersect(editions)) return -1_000
        return tokens.intersect(other).size * 20 - other.minus(tokens).size
    }
}
