package com.saab.tv.data.repository

import com.saab.tv.data.local.CanonicalMetadataDao
import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.CanonicalSeriesSnapshot
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CanonicalMetadataRepositoryCompletionTest {
    @Test fun immediatelyCompletedAliasWriteReturnsSuccessfullyToCaller() = runBlocking {
        val left = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 42))
        val right = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt1234567"))
        var called = false
        val dao = Proxy.newProxyInstance(CanonicalMetadataDao::class.java.classLoader,
            arrayOf(CanonicalMetadataDao::class.java)) { _, method, args ->
            check(method.name == "linkVerified")
            assertEquals(left, args!![0])
            assertEquals(right, args[1])
            assertTrue(args[2] as Long > 0)
            called = true
            Unit
        } as CanonicalMetadataDao
        CanonicalMetadataRepository(dao).linkVerifiedIdentities(left, right)
        assertTrue(called)
    }

    @Test fun canonicalSeriesStoreResolvesAliasesAndPrunesAfterAtomicPut() = runBlocking {
        val identity = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt7654321"))
        val alias = com.saab.tv.data.model.CanonicalIdentityAliasEntity(
            identityKey = identity.stableKey, namespace = "IMDB", kind = "series",
            providerInstallationId = null, externalId = identity.externalId,
            canonicalIdentityKey = "canonical-root", canonicalNamespace = "IMDB",
            canonicalKind = "series", canonicalProviderInstallationId = null,
            canonicalExternalId = identity.externalId, verifiedAtMillis = 1
        )
        val manifest = CanonicalEpisodeListEntity(identity.stableKey, "", "CINEMETA", 0, 5)
        val title = CanonicalTitleEntity(identity.stableKey, "", "series", lastFetchedAtMillis = 5)
        val snapshot = CanonicalSeriesSnapshot(manifest.copy(seriesIdentityKey = "canonical-root"), emptyList())
        val calls = mutableListOf<String>()
        val dao = Proxy.newProxyInstance(CanonicalMetadataDao::class.java.classLoader,
            arrayOf(CanonicalMetadataDao::class.java)) { _, method, args ->
            when (method.name) {
                "getAlias" -> alias
                "getSeriesSnapshot" -> {
                    assertEquals("canonical-root", args!![0]); calls += "read"; snapshot
                }
                "putSeriesSnapshot" -> {
                    assertEquals(title, args!![0]); assertEquals(manifest, args[1]);
                    assertEquals(emptyList<CanonicalEpisodeEntity>(), args[2]); calls += "write"; Unit
                }
                "deleteEpisodeListsOlderThan" -> { calls += "age"; 0 }
                "retainNewestEpisodeSnapshots" -> { calls += "limit"; Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as CanonicalMetadataDao
        val repository = CanonicalMetadataRepository(dao)
        assertEquals(snapshot, repository.getSeriesSnapshot(identity))
        repository.putSeriesSnapshot(title, manifest, emptyList())
        assertEquals(listOf("read", "write", "age", "limit"), calls)
    }
}
