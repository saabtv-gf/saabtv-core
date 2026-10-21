package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CanonicalTitleStoreTest {
    private val identity = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 12))

    @Test fun freshnessHasInclusiveBoundaryAndRejectsFutureRowsAndMissingData() = runBlocking {
        val store = Store()
        assertNull(store.getFreshTitle(identity, "en", 100, 1_000))
        store.putTitle(CanonicalTitleEntity(identity.stableKey, "en", "movie", lastFetchedAtMillis = 900))
        assertNotNull(store.getFreshTitle(identity, "en", 100, 1_000))
        assertNotNull(store.getFreshTitle(identity, "en", 100, 900))
        assertNull(store.getFreshTitle(identity, "en", 100, 1_001))
        assertNull(store.getFreshTitle(identity, "en", 100, 899))
        assertNull(store.getFreshTitle(identity, "te", 100, 1_000))
        assertNull(store.getTitle(identity))
        store.putTitle(store.rows.values.single().copy(lastFetchedAtMillis = System.currentTimeMillis()))
        assertNotNull(store.getFreshTitle(identity, "en", 60_000))
    }

    @Test fun zeroAndNegativeTtlsAreRejected() = runBlocking {
        listOf(0L, -1L).forEach { ttl ->
            try {
                Store().getFreshTitle(identity, "en", ttl, 1_000)
                fail("Invalid TTL must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun noopStoreDoesNotPersistRows() = runBlocking {
        NoopCanonicalTitleStore.putTitle(CanonicalTitleEntity(identity.stableKey, "", "movie", lastFetchedAtMillis = 1_000))
        assertNull(NoopCanonicalTitleStore.getTitle(identity))
        assertNull(NoopCanonicalTitleStore.getFreshTitle(identity, "en", 100, 1_000))
    }

    private class Store : CanonicalTitleStore {
        val rows = mutableMapOf<Pair<String, String>, CanonicalTitleEntity>()
        override suspend fun getTitle(identity: ContentIdentity, locale: String) = rows[identity.stableKey to locale]
        override suspend fun putTitle(title: CanonicalTitleEntity) { rows[title.identityKey to title.locale] = title }
    }
}
