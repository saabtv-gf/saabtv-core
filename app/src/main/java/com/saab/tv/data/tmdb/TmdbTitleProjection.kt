package com.saab.tv.data.tmdb

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.ArtworkReference
import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.RatingProjection
import com.saab.tv.domain.schema.TitleCardProjection

/** One validated TMDB preview mapping shared by search, catalog rails and related-title cards. */
internal fun TmdbMetaPreview.toCanonicalProjection(): TitleCardProjection? {
    val kind = ContentKind.fromProvider(type)
        ?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
        ?: return null
    val identity = ContentIdentity.tmdb(kind, tmdbId) ?: return null
    val artwork = buildList {
        poster?.takeIf(String::isNotBlank)?.let { add(ArtworkReference(ArtworkRole.POSTER, it, "TMDB")) }
        backdrop?.takeIf(String::isNotBlank)?.let { add(ArtworkReference(ArtworkRole.BACKDROP, it, "TMDB")) }
    }
    return TitleCardProjection(
        presentationKey = identity.stableKey,
        identity = identity,
        kind = kind,
        title = name,
        synopsis = description,
        artwork = artwork,
        year = releaseInfo?.take(4),
        rating = rating?.takeIf { it in 0.0..10.0 }?.let { RatingProjection(it, "tmdb", "0-10") },
        sourceId = "tmdb:${identity.externalId}"
    )
}

internal fun TmdbMetaPreview.toCanonicalMetaItem(): MetaItem? =
    toCanonicalProjection()?.asMetaItem()
