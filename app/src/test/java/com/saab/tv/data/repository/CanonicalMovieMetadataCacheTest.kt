package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalMovieMetadataCacheTest {
    private val id = "tt1234567"
    private val now = 10_000_000L

    @Test fun readsOnlyFreshVerifiedCinemetaMovieData() = runBlocking {
        val store = Store(row = encoded())
        val cache = CanonicalMovieMetadataCache(store)
        val cached = cache.read("movie", id, null, now)
        assertEquals("Cached Film", cached?.name)
        assertEquals("https://v3-cinemeta.strem.io", cached?.addonBaseUrl)
        assertNull(cache.read("series", id, null, now))
        assertEquals("Cached Film", cache.read("movie", id, "https://addon.example", now)?.name)
        assertNull(cache.read("movie", "not-imdb", null, now))
        val eightDaysLater = now + 8L * 24 * 60 * 60 * 1_000L
        assertNull(CanonicalMovieMetadataCache(Store(row = encoded()))
            .read("movie", id, null, eightDaysLater))
        assertNull(CanonicalMovieMetadataCache(Store(row = encoded().copy(titleSource = "TMDB")))
            .read("movie", id, null, now))
    }

    @Test fun readErrorsFallThroughButCancellationIsPreserved() {
        assertNull(runBlocking {
            CanonicalMovieMetadataCache(Store(readFailure = IllegalStateException("disk")))
                .read("movie", id, null, now)
        })
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CanonicalMovieMetadataCache(Store(readFailure = CancellationException("cancel")))
                    .read("movie", id, null, now)
            }
        }
    }

    @Test fun writesOnlyValidatedCinemetaMovieRowsAndIsolatesStorageFailure() {
        val store = Store()
        val cache = CanonicalMovieMetadataCache(store)
        runBlocking {
            cache.write(meta(), "wrong-id", "movie", now)
            cache.write(meta().copy(addonBaseUrl = "https://addon.example"), id, "movie", now)
            cache.write(meta(), id, "series", now)
        }
        assertNull(store.row)

        runBlocking { cache.write(meta(), id, "movie", now) }
        assertEquals(id, store.row?.externalImdbId())

        runBlocking {
            CanonicalMovieMetadataCache(Store(writeFailure = IllegalStateException("disk")))
                .write(meta(), id, "movie", now)
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CanonicalMovieMetadataCache(Store(writeFailure = CancellationException("cancel")))
                    .write(meta(), id, "movie", now)
            }
        }
    }

    private fun encoded(at: Long = now) = requireNotNull(CanonicalStremioMetadata.encode(meta(), id, "movie", at))

    private fun meta() = MetaItem(
        id = id, type = "movie", name = "Cached Film",
        addonBaseUrl = "https://v3-cinemeta.strem.io", runtime = "47m"
    )

    private class Store(
        var row: CanonicalTitleEntity? = null,
        private val readFailure: Exception? = null,
        private val writeFailure: Exception? = null
    ) : CanonicalTitleStore {
        override suspend fun getTitle(identity: ContentIdentity, locale: String): CanonicalTitleEntity? {
            readFailure?.let { throw it }
            return row?.takeIf { it.identityKey == identity.stableKey && it.locale == locale }
        }

        override suspend fun putTitle(title: CanonicalTitleEntity) {
            writeFailure?.let { throw it }
            row = title
        }
    }

    private fun CanonicalTitleEntity.externalImdbId(): String = identityKey.substringAfterLast('/')
}
