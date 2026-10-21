package com.saab.tv.data.repository

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.CancellationException

/** Warm, rebuildable metadata path for Cinemeta movies. Series still require their episode list. */
internal class CanonicalMovieMetadataCache(private val store: CanonicalTitleStore) {
    suspend fun read(
        type: String,
        id: String,
        preferredAddonBaseUrl: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): MetaItem? {
        if (ContentKind.fromProvider(type) != ContentKind.MOVIE) return null
        val identity = ContentIdentity.imdb(ContentKind.MOVIE, id) ?: return null
        return try {
            store.getFreshTitle(identity, "", CACHE_TTL_MILLIS, nowMillis)
                ?.let { CanonicalStremioMetadata.decode(it, id, type) }
                ?.copy(addonBaseUrl = CINEMETA_BASE_URL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun write(meta: MetaItem, requestedId: String, requestedType: String, nowMillis: Long) {
        val entity = CanonicalStremioMetadata.encode(meta, requestedId, requestedType, nowMillis) ?: return
        try {
            store.putTitle(entity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }

    private companion object {
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}
