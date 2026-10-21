package com.saab.tv.data.repository

import com.google.gson.Gson
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.MetadataSource
import java.net.URI
import java.time.LocalDate

/** Maps a validated Cinemeta movie observation to/from the shared title-facts cache. */
internal object CanonicalStremioMetadata {
    private const val CINEMETA_HOST = "v3-cinemeta.strem.io"
    private val gson = Gson()

    fun encode(
        meta: MetaItem,
        requestedId: String,
        requestedType: String,
        nowMillis: Long
    ): CanonicalTitleEntity? {
        if (nowMillis <= 0 || !isCinemetaOrigin(meta.addonBaseUrl)) return null
        val kind = ContentKind.fromProvider(requestedType) ?: return null
        if (kind !in setOf(ContentKind.MOVIE, ContentKind.SERIES) || ContentKind.fromProvider(meta.type) != kind) return null
        val identity = ContentIdentity.imdb(kind, requestedId) ?: return null
        if (meta.id.trim() != identity.externalId) return null
        val title = meta.name.trim().takeIf(String::isNotEmpty) ?: return null
        val source = MetadataSource.CINEMETA.name
        val release = parseRelease(meta.releaseInfo)
        val rating = meta.imdbRating?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
        val runtimeMillis = parseRuntimeMillis(meta.runtime)

        return CanonicalTitleEntity(
            identityKey = identity.stableKey,
            locale = "",
            kind = kind.keyPart,
            title = title,
            titleSource = source,
            titleObservedAtMillis = nowMillis,
            synopsis = meta.description?.trim()?.takeIf(String::isNotEmpty),
            synopsisSource = source.takeIf { !meta.description.isNullOrBlank() },
            synopsisObservedAtMillis = nowMillis.takeIf { !meta.description.isNullOrBlank() } ?: 0,
            poster = meta.poster?.trim()?.takeIf(String::isNotEmpty),
            posterSource = source.takeIf { !meta.poster.isNullOrBlank() },
            posterObservedAtMillis = nowMillis.takeIf { !meta.poster.isNullOrBlank() } ?: 0,
            backdrop = meta.background?.trim()?.takeIf(String::isNotEmpty),
            backdropSource = source.takeIf { !meta.background.isNullOrBlank() },
            backdropObservedAtMillis = nowMillis.takeIf { !meta.background.isNullOrBlank() } ?: 0,
            logo = meta.logo?.trim()?.takeIf(String::isNotEmpty),
            logoSource = source.takeIf { !meta.logo.isNullOrBlank() },
            logoObservedAtMillis = nowMillis.takeIf { !meta.logo.isNullOrBlank() } ?: 0,
            genresJson = meta.genres.orEmpty().map(String::trim).filter(String::isNotEmpty)
                .takeIf { it.isNotEmpty() }?.let(gson::toJson),
            genresSource = source.takeIf { meta.genres.orEmpty().any(String::isNotBlank) },
            genresObservedAtMillis = nowMillis.takeIf { meta.genres.orEmpty().any(String::isNotBlank) } ?: 0L,
            releaseDate = release?.first,
            releaseDatePrecision = release?.second,
            releaseDateSource = source.takeIf { release != null },
            releaseDateObservedAtMillis = nowMillis.takeIf { release != null } ?: 0L,
            ratingValue = rating,
            ratingSource = "IMDb",
            ratingScale = "0-10",
            ratingObservedAtMillis = nowMillis.takeIf { rating != null } ?: 0L,
            runtimeEstimateMillis = runtimeMillis,
            runtimeSource = source.takeIf { runtimeMillis != null },
            runtimeObservedAtMillis = nowMillis.takeIf { runtimeMillis != null } ?: 0L,
            lastFetchedAtMillis = nowMillis
        )
    }

    fun decode(row: CanonicalTitleEntity, requestedId: String, requestedType: String): MetaItem? {
        val kind = ContentKind.fromProvider(requestedType) ?: return null
        if (kind !in setOf(ContentKind.MOVIE, ContentKind.SERIES) || row.kind != kind.keyPart) return null
        val identity = ContentIdentity.imdb(kind, requestedId) ?: return null
        if (row.identityKey != identity.stableKey ||
            (kind == ContentKind.MOVIE && row.titleSource != MetadataSource.CINEMETA.name)) return null
        val title = row.title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val genres = row.genresJson?.takeIf { row.genresSource == MetadataSource.CINEMETA.name }?.let { json ->
            runCatching { gson.fromJson(json, Array<String>::class.java)?.toList().orEmpty() }.getOrNull()
        } ?: emptyList()
        return MetaItem(
            id = identity.externalId,
            type = kind.keyPart,
            name = title,
            poster = row.poster,
            background = row.backdrop,
            logo = row.logo,
            description = row.synopsis,
            releaseInfo = row.releaseDate.takeIf { row.releaseDateSource == MetadataSource.CINEMETA.name },
            // A canonical row may contain another provider's rating. Do not expose
            // that value through the IMDb-specific Stremio field.
            imdbRating = row.ratingValue.takeIf {
                row.ratingSource == "IMDb" && row.ratingScale == "0-10"
            }?.toString(),
            runtime = row.runtimeEstimateMillis?.takeIf { row.runtimeSource == MetadataSource.CINEMETA.name }?.let(::formatRuntime),
            genres = genres
        )
    }

    internal fun isCinemetaOrigin(baseUrl: String?): Boolean = runCatching {
        val uri = URI(baseUrl?.trim())
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(CINEMETA_HOST, ignoreCase = true)
    }.getOrDefault(false)

    private fun parseRelease(value: String?): Pair<String, String>? {
        val release = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (release.matches(Regex("\\d{4}"))) {
            val year = release.toIntOrNull() ?: return null
            if (year !in 1800..2200) return null
            return release to "YEAR"
        }
        val date = runCatching { LocalDate.parse(release) }.getOrNull() ?: return null
        return date.toString() to "DATE"
    }

    private fun parseRuntimeMillis(value: String?): Long? {
        val runtime = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        val hours = Regex("(\\d+)\\s*h(?:ours?)?").find(runtime)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("(\\d+)\\s*m(?:in(?:ute)?s?)?").find(runtime)?.groupValues?.get(1)?.toLongOrNull()
        val totalMinutes = when {
            hours > 0 || minutes != null -> hours * 60 + (minutes ?: 0L)
            runtime.matches(Regex("\\d{1,4}")) -> runtime.toLongOrNull()
            else -> null
        } ?: return null
        return totalMinutes.takeIf { it in 1..(24 * 60) }?.times(60_000)
    }

    private fun formatRuntime(milliseconds: Long): String {
        val minutes = (milliseconds / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
