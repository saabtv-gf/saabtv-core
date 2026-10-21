package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import java.util.Locale

/** Keeps source ranking and in-player audio selection on one ordered preference list. */
object AudioLanguagePreferencePolicy {
    fun forProfile(profile: ProfileEntity): List<String> {
        val sourcePriorities = normalize(
            listOf(
                profile.sourceLanguagePriority1,
                profile.sourceLanguagePriority2,
                profile.sourceLanguagePriority3
            )
        )
        val legacyAudioPriorities = normalize(
            listOf(profile.preferredAudioLanguage, profile.preferredAudioLanguageSecondary)
        )
        // Before the two settings surfaces were unified, existing profiles could
        // have an explicit playback preference alongside untouched source defaults.
        // Preserve that choice on upgrade; once source priorities are customized,
        // they are the canonical ordering (and current writes mirror both fields).
        val priorities = if (
            sourcePriorities == DEFAULT_SOURCE_PRIORITIES && legacyAudioPriorities.isNotEmpty()
        ) {
            legacyAudioPriorities + sourcePriorities
        } else {
            sourcePriorities + legacyAudioPriorities
        }
        return priorities.distinct().take(MAX_PREFERENCES)
    }

    /** Replaces a priority slot while keeping the same language from appearing twice. */
    fun setPriority(profile: ProfileEntity, priority: Int, language: String): ProfileEntity {
        val priorities = listOf(
            profile.sourceLanguagePriority1,
            profile.sourceLanguagePriority2,
            profile.sourceLanguagePriority3
        ).map(::canonical).toMutableList()
        val index = (priority - 1).coerceIn(0, priorities.lastIndex)
        val value = canonical(language)
        priorities[index] = value
        priorities.indices
            .filter { it != index && priorities[it] == value }
            .forEach { priorities[it] = "" }

        return profile.withAudioLanguagePriorities(priorities)
    }

    /** Promotes a language explicitly selected during playback to the primary preference. */
    fun promote(profile: ProfileEntity, language: String): ProfileEntity {
        val selected = canonical(language)
        if (selected.isEmpty()) return profile
        val priorities = (listOf(selected) + forProfile(profile).filterNot { it == selected })
            .take(MAX_PREFERENCES)
            .toMutableList()
        while (priorities.size < MAX_PREFERENCES) priorities += ""
        return profile.withAudioLanguagePriorities(priorities)
    }

    private fun ProfileEntity.withAudioLanguagePriorities(priorities: List<String>) = copy(
        sourceLanguagePriority1 = priorities[0],
        sourceLanguagePriority2 = priorities[1],
        sourceLanguagePriority3 = priorities[2],
        // Retain the legacy columns as mirrors for older clients and backups.
        preferredAudioLanguage = priorities[0],
        preferredAudioLanguageSecondary = priorities[1]
    )

    private fun normalize(values: List<String>): List<String> = values
        .map(::canonical)
        .filter(String::isNotEmpty)
        .distinct()

    private fun canonical(value: String): String {
        val tag = value.trim().replace('_', '-')
        if (tag.isEmpty()) return ""
        val lowered = tag.lowercase(Locale.ROOT)
        val primary = lowered.substringBefore('-')
        val language = when (primary) {
            "english", "eng" -> "en"
            "telugu", "tel" -> "te"
            "hindi", "hin" -> "hi"
            "tamil", "tam" -> "ta"
            "malayalam", "mal" -> "ml"
            "kannada", "kan" -> "kn"
            "bengali", "bangla", "ben" -> "bn"
            "spanish", "spa" -> "es"
            "french", "fra", "fre" -> "fr"
            "german", "deu", "ger" -> "de"
            "italian", "ita" -> "it"
            "portuguese", "por" -> "pt"
            "russian", "rus" -> "ru"
            "japanese", "jpn" -> "ja"
            "chinese", "mandarin", "zho", "chi" -> "zh"
            "arabic", "ara" -> "ar"
            else -> primary
        }
        if (language.length !in 2..3 || tag == primary) return language
        val region = tag.substringAfter('-', "").takeIf(String::isNotBlank) ?: return language
        return "$language-${region.uppercase(Locale.ROOT)}"
    }

    private const val MAX_PREFERENCES = 3
    private val DEFAULT_SOURCE_PRIORITIES = listOf("en", "te", "hi")
}
