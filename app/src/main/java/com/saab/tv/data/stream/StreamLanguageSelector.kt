package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream

data class PreferredStreamLanguage(
    val code: String,
    val label: String
)

object StreamLanguageSelector {
    val supportedLanguages = listOf(
        PreferredStreamLanguage("en", "English"),
        PreferredStreamLanguage("te", "Telugu"),
        PreferredStreamLanguage("hi", "Hindi")
    )

    /**
     * Preserve the active stream ranking inside each provider, while preferring
     * Torrentio over MediaFusion. MediaFusion is selected when Torrentio does not
     * offer the requested language.
     */
    fun bestAvailable(streams: List<Stream>, languageCode: String): Stream? {
        val languageMatches = streams.filter { hasLanguage(it, languageCode) }
        return languageMatches.minByOrNull(StreamSourceProviderResolver::selectionRank)
    }

    fun hasLanguage(stream: Stream, languageCode: String): Boolean =
        languageCode in StreamParser.extractAudioLanguages(StreamParser.combinedText(stream))

    fun availableLanguages(streams: List<Stream>): List<PreferredStreamLanguage> =
        supportedLanguages.filter { bestAvailable(streams, it.code) != null }
}
