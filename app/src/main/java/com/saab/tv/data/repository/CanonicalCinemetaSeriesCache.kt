package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.CancellationException

/** Persistent fast path for Cinemeta's complete, authoritative series episode list. */
internal class CanonicalCinemetaSeriesCache(
    private val titleStore: CanonicalTitleStore,
    private val seriesStore: CanonicalSeriesMetadataStore
) {
    suspend fun read(
        type: String,
        id: String,
        nowMillis: Long = System.currentTimeMillis()
    ): MetaItem? {
        if (ContentKind.fromProvider(type) != ContentKind.SERIES) return null
        val identity = ContentIdentity.imdb(ContentKind.SERIES, id) ?: return null
        return try {
            val snapshot = seriesStore.getSeriesSnapshot(identity) ?: return null
            val age = nowMillis - snapshot.manifest.observedAtMillis
            if (snapshot.manifest.source != CINEMETA_SOURCE || age !in 0..CACHE_TTL_MILLIS ||
                snapshot.manifest.episodeCount != snapshot.episodes.size) return null
            val title = titleStore.getTitle(identity) ?: return null
            if (title.identityKey != snapshot.manifest.seriesIdentityKey) return null
            val meta = CanonicalStremioMetadata.decode(title, id, type) ?: return null
            meta.copy(
                addonBaseUrl = CINEMETA_BASE_URL,
                videos = snapshot.episodes.sortedBy { it.episodeOrder }.map { episode ->
                    MetaVideo(
                        id = episode.id,
                        title = episode.title,
                        released = episode.released,
                        thumbnail = episode.thumbnail,
                        overview = episode.overview,
                        season = episode.season,
                        episode = episode.episode
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun write(meta: MetaItem, requestedId: String, requestedType: String, nowMillis: Long) {
        if (meta.videos == null || meta.videos.size > MAX_EPISODES) return
        val title = CanonicalStremioMetadata.encode(meta, requestedId, requestedType, nowMillis) ?: return
        if (title.kind != ContentKind.SERIES.keyPart) return
        val identity = ContentIdentity.imdb(ContentKind.SERIES, requestedId) ?: return
        val uniqueKeys = HashSet<String>(meta.videos.size)
        val episodes = try {
            meta.videos.mapIndexed { index, video ->
                if (video.season < 0 || video.episode < 0) return
                val rawKey = video.id.trim().ifEmpty { "${video.season}:${video.episode}:$index" }
                val key = if (uniqueKeys.add(rawKey)) rawKey else "$rawKey#$index"
                CanonicalEpisodeEntity(
                    seriesIdentityKey = identity.stableKey,
                    locale = title.locale,
                    episodeKey = key,
                    id = video.id,
                    title = video.title,
                    released = video.released,
                    thumbnail = video.thumbnail,
                    overview = video.overview,
                    season = video.season,
                    episode = video.episode,
                    episodeOrder = index,
                    observedAtMillis = nowMillis
                )
            }
        } catch (_: Exception) {
            // Gson can still surface null Kotlin properties inside a nominally valid response.
            return
        }
        val manifest = CanonicalEpisodeListEntity(
            seriesIdentityKey = identity.stableKey,
            locale = title.locale,
            source = CINEMETA_SOURCE,
            episodeCount = episodes.size,
            observedAtMillis = nowMillis
        )
        try {
            seriesStore.putSeriesSnapshot(title, manifest, episodes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Content cache failures never make an otherwise valid details response fail.
        }
    }

    private companion object {
        const val CINEMETA_SOURCE = "CINEMETA"
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        const val CACHE_TTL_MILLIS = 6L * 60 * 60 * 1_000
        const val MAX_EPISODES = 5_000
    }
}
