package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.AppDiagnostics
import com.saab.tv.domain.schema.CanonicalFieldResolver
import com.saab.tv.domain.schema.CanonicalTextField
import com.saab.tv.domain.schema.CanonicalValueCandidate
import com.saab.tv.domain.schema.CanonicalValueResolver
import com.saab.tv.domain.schema.CanonicalValueSelection
import com.saab.tv.domain.schema.MetadataSource
import com.saab.tv.domain.schema.TextFieldCandidate

/** One winner per semantic field; a partial provider update cannot erase usable artwork/text. */
internal object CanonicalTitleMergePolicy {
    private const val POLICY_VERSION = 2

    fun merge(rows: List<CanonicalTitleEntity>, canonicalKey: String, nowMillis: Long): CanonicalTitleEntity {
        require(rows.isNotEmpty()) { "At least one title observation is required" }
        val newest = rows.sortedWith(compareByDescending<CanonicalTitleEntity> { it.lastFetchedAtMillis }.thenBy { it.identityKey })
        val base = newest.first()
        require(rows.all { it.kind == base.kind && it.locale == base.locale }) { "Cannot merge different kinds or locales" }
        fun select(field: CanonicalTextField) = CanonicalFieldResolver.select(
            field, rows.mapNotNull { observation(it, field) }, base.locale, nowMillis
        )
        val textSelections = CanonicalTextField.entries.associateWith(::select)
        AppDiagnostics.detailed(
            "Metadata Merge",
            "Canonical Fields Resolved",
            diagnosticSummary(canonicalKey, textSelections)
        )
        val title = textSelections[CanonicalTextField.TITLE]
        val synopsis = textSelections[CanonicalTextField.SYNOPSIS]
        val poster = textSelections[CanonicalTextField.POSTER]
        val backdrop = textSelections[CanonicalTextField.BACKDROP]
        val logo = textSelections[CanonicalTextField.LOGO]
        val genres = selectValue(rows, nowMillis,
            value = { row -> row.genresJson?.let { json -> json to row.genreIdsJson } },
            source = { it.genresSource }, observedAt = { it.genresObservedAtMillis })
        val release = selectValue(rows, nowMillis,
            value = { row -> row.releaseDate?.let { date -> date to row.releaseDatePrecision } },
            source = { it.releaseDateSource }, observedAt = { it.releaseDateObservedAtMillis },
            precision = { if (it.releaseDatePrecision.equals("DATE", true) || it.releaseDatePrecision.equals("DAY", true)) 1 else 0 })
        val rating = rows.mapNotNull { row ->
            val value = row.ratingValue ?: return@mapNotNull null
            val source = ratingSource(row.ratingSource) ?: return@mapNotNull null
            CanonicalValueCandidate(
                value = RatingFacts(value, row.ratingSource.orEmpty(), row.ratingScale.orEmpty()),
                source = source,
                observedAtMillis = row.ratingObservedAtMillis,
                identityKey = row.identityKey
            )
        }.let { CanonicalValueResolver.select(it, nowMillis) }
        val runtime = selectValue(rows, nowMillis, { it.runtimeEstimateMillis }, { it.runtimeSource }, { it.runtimeObservedAtMillis })
        val status = selectValue(rows, nowMillis, { it.status }, { it.statusSource }, { it.statusObservedAtMillis })
        val countries = selectValue(rows, nowMillis, { it.countriesJson }, { it.countriesSource }, { it.countriesObservedAtMillis })
        val language = selectValue(rows, nowMillis, { it.originalLanguage }, { it.originalLanguageSource }, { it.originalLanguageObservedAtMillis })
        val collection = selectValue(rows, nowMillis,
            value = { row -> row.collectionId?.let { id -> id to row.collectionName } },
            source = { it.collectionSource }, observedAt = { it.collectionObservedAtMillis })
        val details = selectValue(rows, nowMillis,
            value = { row -> row.creditsObservedAtMillis.takeIf { it > 0L }?.let {
                DetailFacts(row.ageRating, row.ageRatingRegion, row.creditsJson)
            } },
            source = { it.creditsSource }, observedAt = { it.creditsObservedAtMillis })
        return base.copy(
            identityKey = canonicalKey,
            title = title?.value, titleSource = title?.source?.name, titleLanguage = title?.languageTag,
            titleObservedAtMillis = title?.observedAtMillis ?: 0,
            synopsis = synopsis?.value, synopsisSource = synopsis?.source?.name, synopsisLanguage = synopsis?.languageTag,
            synopsisObservedAtMillis = synopsis?.observedAtMillis ?: 0,
            poster = poster?.value, posterSource = poster?.source?.name, posterObservedAtMillis = poster?.observedAtMillis ?: 0,
            backdrop = backdrop?.value, backdropSource = backdrop?.source?.name, backdropObservedAtMillis = backdrop?.observedAtMillis ?: 0,
            logo = logo?.value, logoSource = logo?.source?.name, logoLanguage = logo?.languageTag,
            logoObservedAtMillis = logo?.observedAtMillis ?: rows.maxOf { it.logoObservedAtMillis },
            genresJson = genres?.value?.first,
            genreIdsJson = genres?.value?.second,
            genresSource = genres?.source?.name,
            genresObservedAtMillis = genres?.observedAtMillis ?: 0,
            releaseDate = release?.value?.first,
            releaseDatePrecision = release?.value?.second,
            releaseDateSource = release?.source?.name,
            releaseDateObservedAtMillis = release?.observedAtMillis ?: 0,
            ratingValue = rating?.value?.value,
            ratingSource = rating?.value?.source,
            ratingScale = rating?.value?.scale,
            ratingObservedAtMillis = rating?.observedAtMillis ?: 0,
            runtimeEstimateMillis = runtime?.value,
            runtimeSource = runtime?.source?.name,
            runtimeObservedAtMillis = runtime?.observedAtMillis ?: 0,
            status = status?.value,
            statusSource = status?.source?.name,
            statusObservedAtMillis = status?.observedAtMillis ?: 0,
            countriesJson = countries?.value,
            countriesSource = countries?.source?.name,
            countriesObservedAtMillis = countries?.observedAtMillis ?: 0,
            originalLanguage = language?.value,
            originalLanguageSource = language?.source?.name,
            originalLanguageObservedAtMillis = language?.observedAtMillis ?: 0,
            collectionId = collection?.value?.first,
            collectionName = collection?.value?.second,
            collectionSource = collection?.source?.name,
            collectionObservedAtMillis = collection?.observedAtMillis ?: 0,
            ageRating = details?.value?.ageRating,
            ageRatingRegion = details?.value?.ageRatingRegion,
            ageRatingSource = "TMDB".takeIf { details?.value?.ageRating != null },
            ageRatingObservedAtMillis = details?.observedAtMillis?.takeIf { details.value.ageRating != null } ?: 0,
            creditsJson = details?.value?.creditsJson,
            creditsSource = details?.source?.name,
            creditsObservedAtMillis = details?.observedAtMillis ?: 0
        )
    }

    /** Safe, compact policy trace: only a truncated canonical hash and provenance are emitted. */
    internal fun diagnosticSummary(
        canonicalKey: String,
        selections: Map<CanonicalTextField, com.saab.tv.domain.schema.TextFieldSelection?>
    ): String {
        val identity = canonicalKey.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            ?.take(12) ?: "unavailable"
        val fields = CanonicalTextField.entries.mapNotNull { field ->
            selections[field]?.let { "${field.name.lowercase()}=${it.source.name.lowercase()}:${it.reason}" }
        }.joinToString(",")
        return "policyVersion=$POLICY_VERSION identity=$identity fields=$fields"
    }

    private fun <T : Any> selectValue(
        rows: List<CanonicalTitleEntity>,
        nowMillis: Long,
        value: (CanonicalTitleEntity) -> T?,
        source: (CanonicalTitleEntity) -> String?,
        observedAt: (CanonicalTitleEntity) -> Long,
        precision: (CanonicalTitleEntity) -> Int = { 0 }
    ): CanonicalValueSelection<T>? = rows.mapNotNull { row ->
        val item = value(row) ?: return@mapNotNull null
        val provenance = MetadataSource.entries.firstOrNull { it.name == source(row) } ?: return@mapNotNull null
        CanonicalValueCandidate(item, provenance, observedAt(row), row.identityKey, precision(row))
    }.let { CanonicalValueResolver.select(it, nowMillis) }

    private fun ratingSource(value: String?): MetadataSource? = when (value?.trim()?.uppercase()) {
        "TMDB" -> MetadataSource.TMDB
        "IMDB", "CINEMETA" -> MetadataSource.CINEMETA
        "ADDON" -> MetadataSource.ADDON
        else -> null
    }

    private fun observation(row: CanonicalTitleEntity, field: CanonicalTextField): TextFieldCandidate? = when (field) {
        CanonicalTextField.TITLE -> candidate(field, row.title, row.titleSource, row.titleLanguage, row.titleObservedAtMillis)
        CanonicalTextField.SYNOPSIS -> candidate(field, row.synopsis, row.synopsisSource, row.synopsisLanguage, row.synopsisObservedAtMillis)
        CanonicalTextField.POSTER -> candidate(field, row.poster, row.posterSource, null, row.posterObservedAtMillis)
        CanonicalTextField.BACKDROP -> candidate(field, row.backdrop, row.backdropSource, null, row.backdropObservedAtMillis)
        CanonicalTextField.LOGO -> candidate(field, row.logo, row.logoSource, row.logoLanguage, row.logoObservedAtMillis)
    }

    private fun candidate(field: CanonicalTextField, value: String?, sourceName: String?, language: String?, at: Long): TextFieldCandidate? {
        if (value.isNullOrBlank()) return null
        val source = MetadataSource.entries.firstOrNull { it.name == sourceName } ?: return null
        return TextFieldCandidate(field, value, source, language, at, identityVerified = true)
    }
}

private data class RatingFacts(val value: Double, val source: String, val scale: String)
private data class DetailFacts(val ageRating: String?, val ageRatingRegion: String?, val creditsJson: String?)
