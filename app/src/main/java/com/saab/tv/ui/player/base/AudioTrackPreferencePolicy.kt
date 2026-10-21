package com.saab.tv.ui.player.base

import java.util.Locale

/** Pure preference rules shared by ExoPlayer startup and the in-player audio picker. */
internal object AudioTrackPreferencePolicy {
    private val supportedLanguages by lazy { Locale.getISOLanguages().toSet() }

    fun normalizePreferences(preferences: List<String>): List<String> = preferences
        .map(::normalizeLanguageToIso2)
        .filter { it != "und" }
        .distinct()
        .take(3)

    fun preferredTrackId(
        tracks: List<PlayerTrackOption>,
        preferences: List<String>
    ): String? {
        val normalizedPreferences = normalizePreferences(preferences)
        for (preferred in normalizedPreferences) {
            tracks.firstOrNull { isCompatible(trackLanguage(it), preferred) }
                ?.let { return it.id }
        }
        return null
    }

    /** Resolves a one-shot language choice without falling back to another language. */
    fun trackIdForLanguage(tracks: List<PlayerTrackOption>, language: String): String? {
        val normalized = normalizeTrackLanguage(language, null) ?: return null
        return preferredTrackId(tracks, listOf(normalized))
    }

    /**
     * Resolves the startup track without letting a stale per-title choice beat
     * the highest-priority language currently available in this stream. Keep a
     * remembered variant only when it belongs to that same best-priority language.
     */
    fun initialTrackId(
        tracks: List<PlayerTrackOption>,
        preferences: List<String>,
        rememberedTrackId: String?
    ): String? {
        val normalizedPreferences = normalizePreferences(preferences)
        val remembered = tracks.firstOrNull { it.id == rememberedTrackId }
        if (normalizedPreferences.isEmpty()) return remembered?.id

        val preferred = tracks.firstOrNull { isCompatible(trackLanguage(it), normalizedPreferences.first()) }
            ?: preferredTrackId(tracks, normalizedPreferences)?.let { id -> tracks.firstOrNull { it.id == id } }
            ?: return null
        val preferredRank = languageRank(trackLanguage(preferred), normalizedPreferences)
        val rememberedRank = remembered?.let {
            languageRank(trackLanguage(it), normalizedPreferences)
        } ?: Int.MAX_VALUE

        return if (remembered != null && rememberedRank == preferredRank) remembered.id else preferred.id
    }

    /** Include the track-group position so duplicate manifest format IDs stay distinct. */
    fun stableTrackId(prefix: String, groupIndex: Int, trackIndex: Int, explicitId: String?): String {
        val position = "$prefix:g$groupIndex:t$trackIndex"
        val explicit = explicitId?.trim()?.takeIf(String::isNotEmpty) ?: return position
        return "$position:$explicit"
    }

    fun trackLanguage(track: PlayerTrackOption): String =
        normalizeTrackLanguage(track.language, track.label) ?: "und"

    fun normalizeTrackLanguage(language: String?, label: String?): String? =
        normalizeLanguageToIso2(language).takeUnless { it == "und" }
            ?: label?.let(::inferLanguageFromLabel)?.takeUnless { it == "und" }

    private fun inferLanguageFromLabel(label: String): String {
        return label.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9-]+"))
            .asSequence()
            .map(::normalizeLanguageToIso2)
            .firstOrNull { language ->
                language != "und" && language.substringBefore('-') in supportedLanguages
            }
            ?: "und"
    }

    fun languageRank(languageKey: String, preferences: List<String>): Int {
        val normalizedPreferences = normalizePreferences(preferences)
        val normalizedKey = normalizeLanguageToIso2(languageKey)
        val exact = normalizedPreferences.indexOf(normalizedKey)
        if (exact >= 0) return exact
        val base = normalizedKey.substringBefore('-')
        return normalizedPreferences.indexOfFirst { it.substringBefore('-') == base }
            .takeIf { it >= 0 }
            ?: Int.MAX_VALUE
    }

    private fun isCompatible(trackLanguage: String?, preferredLanguage: String): Boolean {
        val track = normalizeLanguageToIso2(trackLanguage)
        return track == preferredLanguage ||
            (track != "und" && track.substringBefore('-') == preferredLanguage.substringBefore('-'))
    }
}
