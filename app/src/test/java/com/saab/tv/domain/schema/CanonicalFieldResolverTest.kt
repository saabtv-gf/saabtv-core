package com.saab.tv.domain.schema

import org.junit.Assert.*
import org.junit.Test

class CanonicalFieldResolverTest {
    private fun candidate(
        field: CanonicalTextField = CanonicalTextField.SYNOPSIS,
        value: String = "value",
        source: MetadataSource = MetadataSource.CINEMETA,
        language: String? = null,
        at: Long = 10,
        verified: Boolean = true,
        order: Int = 0
    ) = TextFieldCandidate(field, value, source, language, at, verified, order)

    @Test
    fun cinemetaAuthorityBeatsTmdbAndNormalizesLanguageTags() {
        val selected = CanonicalFieldResolver.select(
            CanonicalTextField.SYNOPSIS,
            listOf(
                candidate(value = "English", source = MetadataSource.TMDB, language = "en-US"),
                candidate(value = "తెలుగు", source = MetadataSource.CINEMETA, language = "te_IN")
            ),
            requestedLanguage = "te-IN",
            nowMillis = 100
        )

        assertEquals("తెలుగు", selected?.value)
        assertEquals("language_match", selected?.reason)
    }

    @Test
    fun languagePreferenceIsAppliedWithinTheSameProvider() {
        val exact = CanonicalFieldResolver.select(
            CanonicalTextField.TITLE,
            listOf(
                candidate(field = CanonicalTextField.TITLE, value = "Base", source = MetadataSource.TMDB, language = "pt"),
                candidate(field = CanonicalTextField.TITLE, value = "Exact", source = MetadataSource.CINEMETA, language = "pt-BR")
            ),
            "pt-BR", 30
        )
        assertEquals("Exact", exact?.value)

        val baseLanguage = CanonicalFieldResolver.select(
            CanonicalTextField.TITLE,
            listOf(candidate(field = CanonicalTextField.TITLE, value = "Português", language = "pt")),
            "pt-PT", 30
        )
        assertEquals("Português", baseLanguage?.value)

        val english = CanonicalFieldResolver.select(
            CanonicalTextField.SYNOPSIS,
            listOf(
                candidate(value = "English", language = "en", order = 0),
                candidate(value = "French", language = "fr", order = 1)
            ),
            "ja", 30
        )
        assertEquals("English", english?.value)
        assertEquals("field_source_priority", english?.reason)

        val cinemeta = CanonicalFieldResolver.select(
            CanonicalTextField.SYNOPSIS,
            listOf(
                candidate(value = "TMDB localized", source = MetadataSource.TMDB, language = "te"),
                candidate(value = "Cinemeta original", source = MetadataSource.CINEMETA, language = "en")
            ),
            "te", 30
        )
        assertEquals("Cinemeta original", cinemeta?.value)
        assertEquals("field_source_priority", cinemeta?.reason)
    }

    @Test
    fun explicitTitleOrSynopsisOverrideWinsButDoesNotOverrideArtwork() {
        val override = candidate(field = CanonicalTextField.TITLE, value = "My title", source = MetadataSource.USER_OVERRIDE)
        val tmdb = candidate(field = CanonicalTextField.TITLE, value = "TMDB title", source = MetadataSource.TMDB)
        val title = CanonicalFieldResolver.select(CanonicalTextField.TITLE, listOf(tmdb, override), "en", 20)
        assertEquals("My title", title?.value)
        assertEquals("explicit_override", title?.reason)

        val posterOverride = candidate(field = CanonicalTextField.POSTER, value = "override", source = MetadataSource.USER_OVERRIDE)
        val addonPoster = candidate(field = CanonicalTextField.POSTER, value = "addon", source = MetadataSource.ADDON)
        assertEquals("addon", CanonicalFieldResolver.select(
            CanonicalTextField.POSTER, listOf(posterOverride, addonPoster), "en", 20
        )?.value)
    }

    @Test
    fun artworkUsesFieldAuthorityAndDoesNotTreatLanguageAsAProxyForRole() {
        val selected = CanonicalFieldResolver.select(
            CanonicalTextField.BACKDROP,
            listOf(
                candidate(field = CanonicalTextField.BACKDROP, value = "addon", source = MetadataSource.ADDON, language = "en"),
                candidate(field = CanonicalTextField.BACKDROP, value = "tmdb", source = MetadataSource.TMDB, language = "fr")
            ),
            requestedLanguage = "en",
            nowMillis = 20
        )
        assertEquals("tmdb", selected?.value)
        assertEquals("field_source_priority", selected?.reason)

        val poster = CanonicalFieldResolver.select(
            CanonicalTextField.POSTER,
            listOf(
                candidate(field = CanonicalTextField.POSTER, value = "cinemeta", source = MetadataSource.CINEMETA),
                candidate(field = CanonicalTextField.POSTER, value = "tmdb", source = MetadataSource.TMDB)
            ),
            requestedLanguage = "en", nowMillis = 20
        )
        assertEquals("cinemeta", poster?.value)

        val logo = CanonicalFieldResolver.select(
            CanonicalTextField.LOGO,
            listOf(
                candidate(field = CanonicalTextField.LOGO, value = "english", source = MetadataSource.TMDB, language = "en"),
                candidate(field = CanonicalTextField.LOGO, value = "other", source = MetadataSource.TMDB, language = "fr")
            ),
            requestedLanguage = " ", nowMillis = 20
        )
        assertEquals("other", logo?.value)
        assertEquals("field_source_priority", logo?.reason)

        val localizedLogo = CanonicalFieldResolver.select(
            CanonicalTextField.LOGO,
            listOf(
                candidate(field = CanonicalTextField.LOGO, value = "english", source = MetadataSource.TMDB, language = "en"),
                candidate(field = CanonicalTextField.LOGO, value = "français", source = MetadataSource.TMDB, language = "fr")
            ),
            requestedLanguage = "en-US", nowMillis = 20
        )
        assertEquals("english", localizedLogo?.value)
        assertEquals("language_match", localizedLogo?.reason)
    }

    @Test
    fun invalidOrForeignCandidatesAreIgnoredAndNeverClearGoodData() {
        val selection = CanonicalFieldResolver.select(
            CanonicalTextField.SYNOPSIS,
            listOf(
                candidate(field = CanonicalTextField.TITLE, value = "wrong field", source = MetadataSource.TMDB),
                candidate(value = "unverified", source = MetadataSource.TMDB, verified = false),
                candidate(value = "  ", source = MetadataSource.TMDB),
                candidate(value = "negative time", source = MetadataSource.TMDB, at = -1),
                candidate(value = "future time", source = MetadataSource.TMDB, at = 101),
                candidate(value = "good", source = MetadataSource.CINEMETA, at = 10)
            ),
            requestedLanguage = null,
            nowMillis = 100
        )
        assertEquals("good", selection?.value)
        assertNull(CanonicalFieldResolver.select(CanonicalTextField.LOGO, emptyList(), null, 0))
    }

    @Test
    fun newerCandidateAndStableTieBreakersResolveDeterministically() {
        val newer = candidate(value = "new", source = MetadataSource.TMDB, at = 20)
        val older = candidate(value = "old", source = MetadataSource.TMDB, at = 10)
        assertEquals("new", CanonicalFieldResolver.select(CanonicalTextField.SYNOPSIS, listOf(older, newer), null, 30)?.value)

        val providerTwo = candidate(value = "provider 2", source = MetadataSource.TMDB, order = 2)
        val providerOne = candidate(value = "provider 1", source = MetadataSource.TMDB, order = 1)
        assertEquals("provider 1", CanonicalFieldResolver.select(
            CanonicalTextField.SYNOPSIS, listOf(providerOne, providerTwo), null, 30
        )?.value)
        assertEquals("z", CanonicalFieldResolver.select(
            CanonicalTextField.SYNOPSIS,
            listOf(candidate(value = "a", source = MetadataSource.TMDB), candidate(value = "z", source = MetadataSource.TMDB)),
            null, 30
        )?.value)
    }

    @Test fun legacySourceRemainsAUsableLowestPriorityFallback() {
        val legacyOnly = CanonicalFieldResolver.select(
            CanonicalTextField.TITLE,
            listOf(candidate(field = CanonicalTextField.TITLE, value = "Migrated title", source = MetadataSource.LEGACY)),
            null,
            30
        )
        val withProvider = CanonicalFieldResolver.select(
            CanonicalTextField.TITLE,
            listOf(
                candidate(field = CanonicalTextField.TITLE, value = "Migrated title", source = MetadataSource.LEGACY),
                candidate(field = CanonicalTextField.TITLE, value = "Provider title", source = MetadataSource.ADDON)
            ),
            null,
            30
        )

        assertEquals("Migrated title", legacyOnly?.value)
        assertEquals("Provider title", withProvider?.value)
    }
}
