package com.saab.tv.data.tmdb

import com.saab.tv.data.model.CanonicalTitleEntity
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.ContentIdentity
import java.util.Locale

/** Lossless conversion of the lightweight Home projection; credits stay details-only. */
internal object CanonicalHomeMetadata {
    private val gson = Gson()
    fun encode(
        identity: ContentIdentity,
        locale: String,
        value: TmdbEnrichment,
        nowMillis: Long,
        logoFetched: Boolean,
        includeDetails: Boolean = false
    ) =
        CanonicalTitleEntity(
            identityKey = identity.stableKey,
            locale = locale.trim().replace('_', '-').lowercase(Locale.ROOT),
            kind = identity.kind.keyPart,
            title = value.localizedTitle,
            titleSource = "TMDB",
            titleLanguage = locale,
            titleObservedAtMillis = nowMillis,
            synopsis = value.description,
            synopsisSource = "TMDB",
            synopsisLanguage = locale,
            synopsisObservedAtMillis = nowMillis,
            poster = value.poster,
            posterSource = "TMDB",
            posterObservedAtMillis = nowMillis,
            backdrop = value.backdrop,
            backdropSource = "TMDB",
            backdropObservedAtMillis = nowMillis,
            logo = value.logo,
            logoSource = "TMDB",
            logoLanguage = locale,
            logoObservedAtMillis = if (logoFetched) nowMillis else 0,
            genresJson = gson.toJson(value.genres),
            genreIdsJson = gson.toJson(value.genreIds),
            genresSource = "TMDB",
            genresObservedAtMillis = nowMillis,
            releaseDate = value.releaseInfo,
            releaseDateSource = "TMDB",
            releaseDateObservedAtMillis = nowMillis,
            releaseDatePrecision = value.releaseInfo?.let {
                if (it.matches(Regex("\\d{4}"))) "YEAR" else "DATE"
            },
            ratingValue = value.rating,
            ratingSource = "TMDB",
            ratingScale = "0-10",
            ratingObservedAtMillis = nowMillis,
            runtimeEstimateMillis = value.runtimeMinutes?.toLong()?.times(60_000),
            runtimeSource = "TMDB".takeIf { value.runtimeMinutes != null },
            runtimeObservedAtMillis = nowMillis.takeIf { value.runtimeMinutes != null } ?: 0L,
            status = value.status,
            statusSource = "TMDB".takeIf { value.status != null },
            statusObservedAtMillis = nowMillis.takeIf { value.status != null } ?: 0L,
            countriesJson = value.countries?.let { gson.toJson(it) },
            countriesSource = "TMDB".takeIf { value.countries != null },
            countriesObservedAtMillis = nowMillis.takeIf { value.countries != null } ?: 0L,
            originalLanguage = value.language,
            originalLanguageSource = "TMDB".takeIf { value.language != null },
            originalLanguageObservedAtMillis = nowMillis.takeIf { value.language != null } ?: 0L,
            collectionId = value.collectionId,
            collectionName = value.collectionName,
            collectionSource = "TMDB".takeIf { value.collectionId != null },
            collectionObservedAtMillis = nowMillis.takeIf { value.collectionId != null } ?: 0L,
            lastFetchedAtMillis = nowMillis,
            ageRating = value.ageRating.takeIf { includeDetails },
            ageRatingRegion = value.ageRatingRegion.takeIf { includeDetails },
            ageRatingSource = "TMDB".takeIf { includeDetails && value.ageRating != null },
            ageRatingObservedAtMillis = nowMillis.takeIf { includeDetails && value.ageRating != null } ?: 0L,
            creditsJson = if (includeDetails) encodeCredits(value) else null,
            creditsSource = "TMDB".takeIf { includeDetails },
            creditsObservedAtMillis = nowMillis.takeIf { includeDetails } ?: 0L
        )

    fun decode(row: CanonicalTitleEntity): TmdbEnrichment = TmdbEnrichment(
        // A canonical row may have been merged with Cinemeta or an addon after the
        // TMDB observation was stored. Only project facts whose provenance is
        // explicitly TMDB; otherwise Home can relabel (for example) an IMDb rating
        // as TMDB or replace TMDB artwork with another provider's asset.
        localizedTitle = row.title.takeIf { row.titleSource == "TMDB" },
        description = row.synopsis.takeIf { row.synopsisSource == "TMDB" },
        genres = row.genresJson?.takeIf { row.genresSource == "TMDB" }?.let(::strings).orEmpty(),
        genreIds = row.genreIdsJson?.takeIf { row.genresSource == "TMDB" }
            ?.let { json -> JsonParser.parseString(json).asJsonArray.map { it.asString.toInt() } }.orEmpty(),
        backdrop = row.backdrop.takeIf { row.backdropSource == "TMDB" },
        logo = row.logo.takeIf { row.logoSource == "TMDB" },
        poster = row.poster.takeIf { row.posterSource == "TMDB" },
        releaseInfo = row.releaseDate.takeIf { row.releaseDateSource == "TMDB" },
        rating = row.ratingValue.takeIf { row.ratingSource == "TMDB" && row.ratingScale == "0-10" },
        runtimeMinutes = row.runtimeEstimateMillis?.takeIf { row.runtimeSource == "TMDB" }?.div(60_000)?.toInt(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        status = row.status.takeIf { row.statusSource == "TMDB" },
        countries = row.countriesJson?.takeIf { row.countriesSource == "TMDB" }?.let(::strings),
        language = row.originalLanguage.takeIf { row.originalLanguageSource == "TMDB" },
        collectionId = row.collectionId.takeIf { row.collectionSource == "TMDB" },
        collectionName = row.collectionName.takeIf { row.collectionSource == "TMDB" },
        ageRating = row.ageRating.takeIf { row.ageRatingSource == "TMDB" && row.ageRatingObservedAtMillis > 0L },
        ageRatingRegion = row.ageRatingRegion.takeIf { row.ageRatingSource == "TMDB" && row.ageRatingObservedAtMillis > 0L },
        directorMembers = decodeCredits(row, "director"),
        writerMembers = decodeCredits(row, "writer"),
        castMembers = decodeCredits(row, "cast")
    )

    private fun encodeCredits(value: TmdbEnrichment): String {
        val credits = buildList {
            value.castMembers.take(MAX_CAST).forEach { add(it.toCanonicalCredit("cast")) }
            value.directorMembers.take(MAX_CREW).forEach { add(it.toCanonicalCredit("director")) }
            value.writerMembers.take(MAX_CREW).forEach { add(it.toCanonicalCredit("writer")) }
        }
        return gson.toJson(credits).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_CREDITS_BYTES) {
                "Canonical credits exceed the bounded metadata cache entry"
            }
        }
    }

    private fun decodeCredits(row: CanonicalTitleEntity, role: String): List<TmdbCastInfo> {
        if (row.creditsSource != "TMDB" || row.creditsObservedAtMillis <= 0L) return emptyList()
        val records = gson.fromJson<List<CanonicalCreditRecord>>(
            requireNotNull(row.creditsJson) { "Cached TMDB credits are missing" },
            CREDIT_LIST_TYPE
        )
        return records.filter { it.role == role }.map {
            TmdbCastInfo(it.name, it.character, it.photo, it.personId?.let { id -> id.substringAfterLast('/').toIntOrNull() })
        }
    }

    private fun TmdbCastInfo.toCanonicalCredit(role: String) = CanonicalCreditRecord(
        role = role,
        personId = tmdbId?.let { ContentIdentity.tmdb(ContentKind.PERSON, it)?.stableKey },
        name = name,
        character = character,
        photo = photo
    )

    private data class CanonicalCreditRecord(
        val role: String,
        val personId: String?,
        val name: String,
        val character: String?,
        val photo: String?
    )

    private fun strings(json: String): List<String> = JsonParser.parseString(json).asJsonArray.map {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid text list in canonical metadata" }
        it.asString
    }

    private val CREDIT_LIST_TYPE = object : TypeToken<List<CanonicalCreditRecord>>() {}.type
    private const val MAX_CAST = 40
    private const val MAX_CREW = 20
    private const val MAX_CREDITS_BYTES = 64 * 1024
}
