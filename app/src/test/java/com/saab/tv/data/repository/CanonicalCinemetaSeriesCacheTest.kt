package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.CanonicalSeriesSnapshot
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalCinemetaSeriesCacheTest {
    private val id = "tt1234567"
    private val identity = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, id))
    private val now = 10_000_000L

    @Test fun roundTripsCompleteEpisodeListAndAcceptsEmptyLists() = runBlocking {
        val original = seriesMeta().copy(videos = listOf(
            MetaVideo("provider-s1e1", "Pilot", "2025-01-01", "still", "plot", 1, 1),
            MetaVideo("provider-s2e5", "Fifth", season = 2, episode = 5)
        ))
        val store = Store()
        val cache = CanonicalCinemetaSeriesCache(store, store)
        cache.write(original, id, "series", now)

        val cached = cache.read("series", id, now)
        assertEquals(original.videos, cached?.videos)
        assertEquals("https://v3-cinemeta.strem.io", cached?.addonBaseUrl)
        assertEquals("The Series", cached?.name)
        assertEquals("2024", cached?.releaseInfo)

        cache.write(original.copy(videos = emptyList()), id, "series", now + 1)
        assertEquals(emptyList<MetaVideo>(), cache.read("series", id, now + 1)?.videos)
    }

    @Test fun readRejectsWrongIdentityKindIncompleteStaleAndFutureSnapshots() = runBlocking {
        val cached = storeWith(seriesMeta())
        val cache = CanonicalCinemetaSeriesCache(cached, cached)
        assertNull(cache.read("movie", id, now))
        assertNull(cache.read("series", "bad-id", now))
        assertNull(cache.read("series", id, now + 6L * 60 * 60 * 1_000 + 1))
        assertNull(cache.read("series", id, now - 1))
        cached.snapshot = cached.snapshot?.copy(manifest = cached.snapshot!!.manifest.copy(source = "ADDON"))
        assertNull(cache.read("series", id, now))
        cached.snapshot = storeWith(seriesMeta()).snapshot?.copy(manifest =
            storeWith(seriesMeta()).snapshot!!.manifest.copy(episodeCount = 4))
        assertNull(cache.read("series", id, now))
    }

    @Test fun readRequiresCanonicalTitleAndPreservesCancellation() {
        val empty = Store(snapshot = snapshot(seriesMeta()), missingTitle = true)
        assertNull(runBlocking { CanonicalCinemetaSeriesCache(empty, empty).read("series", id, now) })
        val storageFailure = Store(readFailure = IllegalStateException("disk"))
        assertNull(runBlocking { CanonicalCinemetaSeriesCache(storageFailure, storageFailure).read("series", id, now) })
        val cancelled = Store(readFailure = CancellationException("cancel"))
        assertThrows(CancellationException::class.java) {
            runBlocking { CanonicalCinemetaSeriesCache(cancelled, cancelled).read("series", id, now) }
        }
    }

    @Test fun writeOnlyStoresVerifiedCompleteCinemetaSeriesAndKeepsDuplicateIdsDistinct() = runBlocking {
        val store = Store()
        val cache = CanonicalCinemetaSeriesCache(store, store)
        val twoSameIds = seriesMeta().copy(videos = listOf(
            MetaVideo("same", "One", season = 1, episode = 1),
            MetaVideo("same", "Two", season = 1, episode = 2),
            MetaVideo("", "Three", season = 1, episode = 3)
        ))
        cache.write(twoSameIds, id, "series", now)
        assertEquals(listOf("same", "same#1", "1:3:2"), store.savedEpisodes.map { it.episodeKey })
        assertEquals(3, store.snapshot?.manifest?.episodeCount)

        val before = store.snapshot
        cache.write(twoSameIds.copy(videos = null), id, "series", now)
        cache.write(twoSameIds.copy(videos = List(5_001) { MetaVideo("$it") }), id, "series", now)
        cache.write(twoSameIds.copy(addonBaseUrl = "https://addon.example"), id, "series", now)
        cache.write(twoSameIds.copy(type = "movie"), id, "series", now)
        cache.write(twoSameIds, "invalid", "series", now)
        assertEquals(before, store.snapshot)

        cache.write(twoSameIds.copy(videos = listOf(MetaVideo("bad", season = -1))), id, "series", now)
        assertEquals(before, store.snapshot)
    }

    @Test fun writeCacheFailuresAreIsolatedButCancellationPropagates() {
        @Suppress("UNCHECKED_CAST")
        val malformedVideos = listOf<MetaVideo?>(null) as List<MetaVideo>
        val withNullEpisode = seriesMeta().copy(videos = malformedVideos)
        val malformed = Store()
        runBlocking { CanonicalCinemetaSeriesCache(malformed, malformed).write(withNullEpisode, id, "series", now) }
        assertNull(malformed.snapshot)

        val broken = Store(writeFailure = IllegalStateException("disk"))
        runBlocking {
            CanonicalCinemetaSeriesCache(broken, broken).write(
                seriesMeta().copy(videos = emptyList()), id, "series", now
            )
        }
        val cancelled = Store(writeFailure = CancellationException("cancel"))
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CanonicalCinemetaSeriesCache(cancelled, cancelled).write(
                    seriesMeta().copy(videos = emptyList()), id, "series", now
                )
            }
        }
    }

    @Test fun noopSeriesStoreIsSafe() = runBlocking {
        assertNull(NoopCanonicalSeriesMetadataStore.getSeriesSnapshot(identity))
        NoopCanonicalSeriesMetadataStore.putSeriesSnapshot(
            title(), manifest(emptyList()), emptyList()
        )
    }

    private fun seriesMeta() = MetaItem(
        id = id, type = "series", name = "The Series", description = "Plot",
        releaseInfo = "2024", addonBaseUrl = "https://v3-cinemeta.strem.io"
    )

    private fun title() = requireNotNull(CanonicalStremioMetadata.encode(seriesMeta(), id, "series", now))
    private fun manifest(videos: List<MetaVideo>) = CanonicalEpisodeListEntity(
        identity.stableKey, "", "CINEMETA", videos.size, now
    )

    private fun snapshot(meta: MetaItem): CanonicalSeriesSnapshot {
        val videos = meta.videos.orEmpty()
        return CanonicalSeriesSnapshot(
            manifest(videos),
            videos.mapIndexed { index, episode -> CanonicalEpisodeEntity(
                identity.stableKey, "", episode.id, episode.id, episode.title, episode.released,
                episode.thumbnail, episode.overview, episode.season, episode.episode, index, now
            ) }
        )
    }

    private fun storeWith(meta: MetaItem) = Store(
        row = title(), snapshot = snapshot(meta)
    )

    private class Store(
        var row: CanonicalTitleEntity? = null,
        var snapshot: CanonicalSeriesSnapshot? = null,
        var savedEpisodes: List<CanonicalEpisodeEntity> = emptyList(),
        private val missingTitle: Boolean = false,
        private val readFailure: Exception? = null,
        private val writeFailure: Exception? = null
    ) : CanonicalTitleStore, CanonicalSeriesMetadataStore {
        override suspend fun getTitle(identity: ContentIdentity, locale: String): CanonicalTitleEntity? {
            readFailure?.let { throw it }
            return if (missingTitle) null else row?.takeIf { it.identityKey == identity.stableKey && it.locale == locale }
        }

        override suspend fun putTitle(title: CanonicalTitleEntity) { row = title }

        override suspend fun getSeriesSnapshot(identity: ContentIdentity, locale: String): CanonicalSeriesSnapshot? {
            readFailure?.let { throw it }
            return snapshot?.takeIf { it.manifest.seriesIdentityKey == identity.stableKey && it.manifest.locale == locale }
        }

        override suspend fun putSeriesSnapshot(
            title: CanonicalTitleEntity,
            manifest: CanonicalEpisodeListEntity,
            episodes: List<CanonicalEpisodeEntity>
        ) {
            writeFailure?.let { throw it }
            row = title
            savedEpisodes = episodes
            snapshot = CanonicalSeriesSnapshot(manifest, episodes)
        }
    }
}
