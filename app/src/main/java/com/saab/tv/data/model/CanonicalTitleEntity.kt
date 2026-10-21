package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Compact metadata cache row. Artwork bytes remain in Coil's bounded file cache. */
@Entity(
    tableName = "canonical_titles",
    primaryKeys = ["identityKey", "locale"],
    indices = [Index(value = ["kind"]), Index(value = ["lastFetchedAtMillis"])]
)
data class CanonicalTitleEntity(
    val identityKey: String,
    /** Requested metadata locale; empty means provider-neutral metadata. */
    val locale: String,
    val kind: String,
    val title: String? = null,
    val titleSource: String? = null,
    val titleLanguage: String? = null,
    val titleObservedAtMillis: Long = 0,
    val synopsis: String? = null,
    val synopsisSource: String? = null,
    val synopsisLanguage: String? = null,
    val synopsisObservedAtMillis: Long = 0,
    val poster: String? = null,
    val posterSource: String? = null,
    val posterObservedAtMillis: Long = 0,
    val backdrop: String? = null,
    val backdropSource: String? = null,
    val backdropObservedAtMillis: Long = 0,
    val logo: String? = null,
    val logoSource: String? = null,
    val logoLanguage: String? = null,
    val logoObservedAtMillis: Long = 0,
    val genresJson: String? = null,
    val genreIdsJson: String? = null,
    val releaseDate: String? = null,
    val releaseDatePrecision: String? = null,
    val ratingValue: Double? = null,
    val ratingSource: String? = null,
    val ratingScale: String? = null,
    val runtimeEstimateMillis: Long? = null,
    val status: String? = null,
    val countriesJson: String? = null,
    val originalLanguage: String? = null,
    val collectionId: Int? = null,
    val collectionName: String? = null,
    val lastFetchedAtMillis: Long,
    /** Scalar facts carry the same source/freshness contract as localized text and artwork. */
    val genresSource: String? = null,
    val genresObservedAtMillis: Long = 0,
    val releaseDateSource: String? = null,
    val releaseDateObservedAtMillis: Long = 0,
    val ratingObservedAtMillis: Long = 0,
    val runtimeSource: String? = null,
    val runtimeObservedAtMillis: Long = 0,
    val statusSource: String? = null,
    val statusObservedAtMillis: Long = 0,
    val countriesSource: String? = null,
    val countriesObservedAtMillis: Long = 0,
    val originalLanguageSource: String? = null,
    val originalLanguageObservedAtMillis: Long = 0,
    val collectionSource: String? = null,
    val collectionObservedAtMillis: Long = 0,
    /** Full details are cached separately from lightweight Home-card observations. */
    val ageRating: String? = null,
    val ageRatingRegion: String? = null,
    val ageRatingSource: String? = null,
    val ageRatingObservedAtMillis: Long = 0,
    /** Bounded canonical person-credit projection; image bytes remain in Coil's cache. */
    val creditsJson: String? = null,
    val creditsSource: String? = null,
    val creditsObservedAtMillis: Long = 0
)

/** Provider aliases are cache metadata, not account data and never enter cloud snapshots. */
@Entity(
    tableName = "canonical_identity_aliases",
    indices = [Index(value = ["canonicalIdentityKey", "namespace"])]
)
data class CanonicalIdentityAliasEntity(
    @PrimaryKey val identityKey: String,
    val namespace: String,
    val kind: String,
    val providerInstallationId: String?,
    val externalId: String,
    val canonicalIdentityKey: String,
    val canonicalNamespace: String,
    val canonicalKind: String,
    val canonicalProviderInstallationId: String?,
    val canonicalExternalId: String,
    val verifiedAtMillis: Long
)
