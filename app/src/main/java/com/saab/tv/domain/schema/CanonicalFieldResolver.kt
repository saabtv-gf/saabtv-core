package com.saab.tv.domain.schema

import java.util.Locale

/** Canonical semantic fields shared by all metadata providers. */
enum class CanonicalTextField(val isLocalized: Boolean) {
    TITLE(true), SYNOPSIS(true), POSTER(false), BACKDROP(false), LOGO(true)
}

enum class MetadataSource { USER_OVERRIDE, TMDB, CINEMETA, ADDON, LEGACY }

/** Stable provider authority used by canonical merge and presentation policies. */
internal fun metadataAuthorityRank(source: MetadataSource): Int = when (source) {
    MetadataSource.USER_OVERRIDE -> 400
    MetadataSource.CINEMETA -> 300
    MetadataSource.TMDB -> 200
    MetadataSource.ADDON -> 100
    MetadataSource.LEGACY -> 0
}

data class CanonicalValueCandidate<T : Any>(
    val value: T,
    val source: MetadataSource,
    val observedAtMillis: Long,
    val identityKey: String,
    /** More precise observations may win within the same provider authority. */
    val precisionRank: Int = 0,
    val identityVerified: Boolean = true
)

data class CanonicalValueSelection<T : Any>(
    val value: T,
    val source: MetadataSource,
    val observedAtMillis: Long
)

/** Shared authority/freshness policy for non-localized canonical facts. */
object CanonicalValueResolver {
    fun <T : Any> select(
        candidates: List<CanonicalValueCandidate<T>>,
        nowMillis: Long
    ): CanonicalValueSelection<T>? = candidates.asSequence()
        .filter { it.identityVerified && it.observedAtMillis in 0..nowMillis }
        .maxWithOrNull(
            compareBy<CanonicalValueCandidate<T>> { sourceRank(it.source) }
                .thenBy { it.precisionRank }
                .thenBy { it.observedAtMillis }
                .thenBy { it.identityKey }
                .thenBy { it.value.toString() }
        )
        ?.let { CanonicalValueSelection(it.value, it.source, it.observedAtMillis) }

    private fun sourceRank(source: MetadataSource): Int = metadataAuthorityRank(source)
}

data class TextFieldCandidate(
    val field: CanonicalTextField,
    val value: String,
    val source: MetadataSource,
    val languageTag: String? = null,
    val observedAtMillis: Long,
    val identityVerified: Boolean,
    /** Provider order is stable across runs and used only as a final tie breaker. */
    val providerOrder: Int = 0
)

data class TextFieldSelection(
    val field: CanonicalTextField,
    val value: String,
    val source: MetadataSource,
    val languageTag: String?,
    val observedAtMillis: Long,
    val reason: String
)

/** Pure field policy: invalid/foreign observations never erase a usable canonical value. */
object CanonicalFieldResolver {
    fun select(
        field: CanonicalTextField,
        candidates: List<TextFieldCandidate>,
        requestedLanguage: String?,
        nowMillis: Long
    ): TextFieldSelection? {
        val usable = candidates.asSequence()
            .filter { it.field == field }
            .filter { it.identityVerified }
            .filter { it.value.isNotBlank() }
            .filter { it.observedAtMillis >= 0L && it.observedAtMillis <= nowMillis }
            .toList()
        if (usable.isEmpty()) return null

        val wantedLanguage = normalizeLanguage(requestedLanguage)
        val selected = usable.maxWith(
            compareBy<TextFieldCandidate> { explicitOverrideRank(field, it.source) }
                .thenBy { sourceRank(it.source) }
                // Language may choose among values from the same authority, but must never
                // promote TMDB over a present Cinemeta value.
                .thenBy { languageRank(field, it.languageTag, wantedLanguage) }
                .thenBy { it.observedAtMillis }
                .thenBy { -it.providerOrder }
                .thenBy { it.value }
        )

        val reason = when {
            selected.source == MetadataSource.USER_OVERRIDE -> "explicit_override"
            field.isLocalized && languagesMatch(selected.languageTag, wantedLanguage) -> "language_match"
            else -> "field_source_priority"
        }
        return TextFieldSelection(
            field = field,
            value = selected.value.trim(),
            source = selected.source,
            languageTag = selected.languageTag,
            observedAtMillis = selected.observedAtMillis,
            reason = reason
        )
    }

    private fun sourceRank(source: MetadataSource): Int = when (source) {
        MetadataSource.USER_OVERRIDE -> 0
        MetadataSource.TMDB -> 200
        MetadataSource.CINEMETA -> 300
        MetadataSource.ADDON -> 100
        MetadataSource.LEGACY -> -100
    }

    private fun explicitOverrideRank(field: CanonicalTextField, source: MetadataSource): Int =
        if (source == MetadataSource.USER_OVERRIDE && (field == CanonicalTextField.TITLE || field == CanonicalTextField.SYNOPSIS)) 1 else 0

    private fun languageRank(field: CanonicalTextField, candidate: String?, requested: String?): Int {
        if (!field.isLocalized || requested == null) return 0
        val language = normalizeLanguage(candidate) ?: return 1
        return when {
            language == requested -> 3
            language.substringBefore('-') == requested.substringBefore('-') -> 2
            language == "en" -> 1
            else -> 0
        }
    }

    private fun languagesMatch(candidate: String?, requested: String?): Boolean {
        val candidateLanguage = normalizeLanguage(candidate) ?: return false
        val requestedLanguage = requested ?: return false
        return candidateLanguage == requestedLanguage ||
            candidateLanguage.substringBefore('-') == requestedLanguage.substringBefore('-')
    }

    private fun normalizeLanguage(value: String?): String? = value
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf(String::isNotEmpty)
        ?.lowercase(Locale.ROOT)
}
