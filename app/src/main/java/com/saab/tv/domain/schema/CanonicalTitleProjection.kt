package com.saab.tv.domain.schema

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaTrailer
import com.saab.tv.data.model.stremio.MetaTrailerStream
import com.google.gson.JsonParser
import java.net.URI

enum class ArtworkRole { POSTER, BACKDROP, LOGO }

/** Image references are URLs/locators only. Encoded bytes belong to Coil's bounded cache. */
data class ArtworkReference(
    val role: ArtworkRole,
    val locator: String,
    val source: String?,
    val language: String? = null
)

data class TrailerReference(val source: String?, val type: String?)

data class TrailerStreamReference(
    val title: String?,
    val videoId: String?,
    val externalUrl: String?,
    val locator: String?
)

/** Small screen-facing contract; provider DTOs and Room rows do not leak past this boundary. */
data class TitleCardProjection(
    val presentationKey: String,
    val identity: ContentIdentity,
    val kind: ContentKind,
    val title: String,
    val synopsis: String?,
    val artwork: List<ArtworkReference>,
    val year: String? = null,
    val rating: RatingProjection? = null,
    val runtimeEstimateMillis: Long? = null,
    val genres: List<String> = emptyList(),
    val trailers: List<TrailerReference> = emptyList(),
    val trailerStreams: List<TrailerStreamReference> = emptyList(),
    /** Existing provider identifier kept only at the compatibility edge. */
    val sourceId: String = identity.sourceReference(),
    val sourceAddonBaseUrl: String? = null
) {
    fun artwork(role: ArtworkRole): ArtworkReference? = artwork.firstOrNull { it.role == role }

    /** Compatibility edge while existing Compose cards still consume MetaItem. */
    fun asMetaItem(): MetaItem = MetaItem(
        id = sourceId,
        type = when (kind) {
            ContentKind.MOVIE -> "movie"
            ContentKind.SERIES -> "series"
            else -> kind.keyPart
        },
        name = title,
        poster = artwork(ArtworkRole.POSTER)?.locator,
        background = artwork(ArtworkRole.BACKDROP)?.locator,
        logo = artwork(ArtworkRole.LOGO)?.locator,
        description = synopsis,
        releaseInfo = year,
        imdbRating = rating?.takeIf { it.source == "imdb" && it.scale == "0-10" }
            ?.value?.toString(),
        runtime = runtimeEstimateMillis?.let { (it / 60_000L).coerceAtLeast(1).toString() },
        genres = genres,
        trailers = trailers.map { MetaTrailer(source = it.source, type = it.type) },
        trailerStreams = trailerStreams.map {
            MetaTrailerStream(title = it.title, ytId = it.videoId, externalUrl = it.externalUrl, url = it.locator)
        },
        addonBaseUrl = sourceAddonBaseUrl
    )
}

data class RatingProjection(val value: Double, val source: String, val scale: String)

/** Canonical card state for catalog rows; opaque IDs remain exact at action boundaries. */
data class CatalogCardProjection(
    val content: TitleCardProjection?,
    private val opaqueCompatibilityItem: MetaItem? = null
) {
    val presentationKey: String
        get() = content?.presentationKey ?: requireNotNull(opaqueCompatibilityItem).canonicalPresentationKey()

    /** Provider-qualified focus identity, retaining exact raw IDs for opaque legacy cards. */
    val focusIdentityKey: String
        get() = content?.presentationKey ?: requireNotNull(opaqueCompatibilityItem).id

    fun asActionItem(): MetaItem = content?.asMetaItem() ?: requireNotNull(opaqueCompatibilityItem)

    companion object {
        fun from(item: MetaItem): CatalogCardProjection {
            val projection = item.toCanonicalProjection()
            return CatalogCardProjection(
                content = projection,
                opaqueCompatibilityItem = item.takeIf { projection == null }
            )
        }
    }
}

/** Maps already validated canonical facts to a UI-safe, role-correct projection. */
fun CanonicalTitleEntity.toTitleCardProjection(
    identity: ContentIdentity,
    fallbackTitle: String,
    fallbackArtwork: List<ArtworkReference> = emptyList()
): TitleCardProjection {
    val titleValue = title?.takeIf { titleSource != null && it.isNotBlank() }
    val posterValue = poster?.takeIf { posterSource != null && it.isNotBlank() }
    val backdropValue = backdrop?.takeIf { backdropSource != null && it.isNotBlank() }
    val logoValue = logo?.takeIf { logoSource != null && it.isNotBlank() }
    val ownArtwork = buildList {
        if (posterValue != null) add(ArtworkReference(ArtworkRole.POSTER, posterValue, posterSource))
        if (backdropValue != null) add(ArtworkReference(ArtworkRole.BACKDROP, backdropValue, backdropSource))
        if (logoValue != null) add(ArtworkReference(ArtworkRole.LOGO, logoValue, logoSource, logoLanguage))
    }
    val roles = ArtworkRole.entries.associateWith { role ->
        ownArtwork.firstOrNull { it.role == role } ?: fallbackArtwork.firstOrNull { it.role == role }
    }
    return TitleCardProjection(
        presentationKey = identity.stableKey,
        identity = identity,
        kind = identity.kind,
        title = titleValue ?: fallbackTitle,
        synopsis = synopsis?.takeIf { synopsisSource != null && it.isNotBlank() },
        artwork = roles.values.filterNotNull(),
        year = releaseDate?.takeIf { releaseDateSource != null }?.takeIf {
            when (releaseDatePrecision?.uppercase()) {
                "YEAR" -> it.matches(Regex("\\d{4}"))
                "DATE", "DAY" -> it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
                // Older cache rows used lowercase precision names; null precision is
                // accepted only when the value itself is an unambiguous year.
                null -> it.matches(Regex("\\d{4}"))
                else -> false
            }
        }?.take(4),
        rating = if (ratingValue != null && ratingSource != null && ratingScale != null)
            RatingProjection(ratingValue, normalizeRatingSource(ratingSource), ratingScale) else null,
        runtimeEstimateMillis = runtimeEstimateMillis.takeIf { runtimeSource != null },
        genres = genresJson?.takeIf { genresSource != null }?.let(::decodeStringArray).orEmpty()
    )
}

/** Compatibility adapter: unknown IDs must carry an addon installation/base URL to avoid collisions. */
fun MetaItem.toCanonicalProjection(addonInstallationId: String? = null): TitleCardProjection? {
    val kind = ContentKind.fromProvider(type)?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
        ?: return null
    val rawId = id.trim()
    val install = addonInstallationId ?: addonBaseUrl?.let(::installationFingerprint)
    val identity = when {
        rawId.startsWith("tmdb:", ignoreCase = true) -> ContentIdentity.tmdb(kind, rawId.substringAfter(':').substringBefore(':'))
        rawId.matches(Regex("tt[0-9]+")) -> ContentIdentity.imdb(kind, rawId)
        install != null -> ContentIdentity.addon(install, kind, rawId)
        else -> null
    } ?: return null
    val artwork = buildList {
        val source = metadataSource().name
        poster?.takeIf(String::isNotBlank)?.let { add(ArtworkReference(ArtworkRole.POSTER, it, source)) }
        background?.takeIf(String::isNotBlank)?.let { add(ArtworkReference(ArtworkRole.BACKDROP, it, source)) }
        logo?.takeIf(String::isNotBlank)?.let { add(ArtworkReference(ArtworkRole.LOGO, it, source)) }
    }
    return TitleCardProjection(
        presentationKey = identity.stableKey,
        identity = identity,
        kind = kind,
        title = name,
        synopsis = description,
        artwork = artwork,
        year = releaseInfo?.take(4),
        rating = imdbRating?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
            ?.let { RatingProjection(it, "imdb", "0-10") },
        runtimeEstimateMillis = parseRuntime(runtime),
        genres = genres.orEmpty().filter(String::isNotBlank).distinct(),
        trailers = trailers.orEmpty().map { TrailerReference(it.source, it.type) },
        trailerStreams = trailerStreams.orEmpty().map {
            TrailerStreamReference(it.title, it.ytId, it.externalUrl, it.url)
        },
        sourceId = id,
        sourceAddonBaseUrl = addonBaseUrl
    )
}

private fun parseRuntime(value: String?): Long? {
    val text = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val minutes = Regex("(\\d+)\\s*m").find(text)?.groupValues?.get(1)?.toLongOrNull()
        ?: if (hours == 0L) text.toLongOrNull() else 0L
    val millis = (hours * 60L + (minutes ?: return null)) * 60_000L
    return millis.takeIf { it > 0L }
}

private fun decodeStringArray(json: String): List<String> = runCatching {
    JsonParser.parseString(json).asJsonArray.mapNotNull { it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
        ?.asString?.trim()?.takeIf(String::isNotEmpty) }.distinct()
}.getOrDefault(emptyList())

private fun normalizeRatingSource(source: String): String = when (source.trim().lowercase()) {
    "imdb" -> "imdb"
    "tmdb" -> "tmdb"
    else -> source.trim().lowercase()
}

private fun installationFingerprint(baseUrl: String): String = addonInstallationKey(baseUrl)

private fun ContentIdentity.sourceReference(): String = when (namespace) {
    IdentityNamespace.TMDB -> "tmdb:$externalId"
    IdentityNamespace.IMDB, IdentityNamespace.ADDON -> externalId
}

/** Stable, provider-qualified de-duplication key for compatibility MetaItem lists. */
fun MetaItem.canonicalPresentationKey(): String = toCanonicalProjection()?.presentationKey
    ?: "legacy/${type.trim().lowercase()}/${id.trim()}"

/** Best-known origin for metadata that has not yet crossed the canonical database boundary. */
fun MetaItem.metadataSource(): MetadataSource = when {
    id.startsWith("tmdb:", ignoreCase = true) -> MetadataSource.TMDB
    addonBaseUrl.isNullOrBlank() -> MetadataSource.LEGACY
    isCinemetaBaseUrl(addonBaseUrl) -> MetadataSource.CINEMETA
    else -> MetadataSource.ADDON
}

private fun isCinemetaBaseUrl(value: String): Boolean = runCatching {
    URI(value).let { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("v3-cinemeta.strem.io", ignoreCase = true)
    }
}.getOrDefault(false)
