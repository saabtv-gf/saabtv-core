package com.saab.tv.data.account

import android.app.Application
import androidx.room.Room
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.SyncClientStateEntity
import com.saab.tv.data.model.SyncOutboxEntity
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.ProfileRuntimeSnapshot
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.testing.AccountTransportFixture
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountSyncV3EngineTest {
    private lateinit var database: SaabTvDatabase
    private lateinit var fixture: AccountTransportFixture

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        fixture = AccountTransportFixture(RuntimeEnvironment.getApplication())
    }

    @After fun close() = database.close()

    @Test fun standaloneRestorePreservesInactiveProfilesRuntimeInsteadOfDroppingIt() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Inactive", isActive = false)
        database.addonDao().insertProfile(profile)
        val commands = UserStateCommandRepository(database, fixture.auth)
        commands.saveProfileRuntime(1, ProfileRuntimeSnapshot(addons = listOf(AddonEntity(
            "https://fixture.invalid", "fixture", "Inactive configuration", "1", null, null
        ))))
        assertTrue(AccountSyncV3Engine(fixture.auth, database, ReusableRemote()).synchronize())
        assertEquals("Inactive configuration", database.addonDao()
            .getRuntimeStateForProfile(profile.portableId).addons.single().name)
        assertEquals(false, database.addonDao().getProfileById(1)?.isActive)
    }

    @Test fun idleSyncPullsWithoutConsumingAnEmptyPushQuota() = runBlocking {
        var pulls = 0
        val remote = object : SyncV3Remote {
            override suspend fun push(operations: List<SyncOutboxEntity>): SyncV3PushResult =
                error("An unchanged account must not send an empty push")
            override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage {
                pulls++
                return SyncV3PullPage(emptyList(), afterSequence, false)
            }
        }
        assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
        assertEquals(1, pulls)
    }

    @Test fun restoredSnapshotRebasesCursorBeforePullingIncrementalState() = runBlocking {
        database.syncOutboxDao().insertClientStateIfMissing(
            SyncClientStateEntity(serverCursor = SyncV3Protocol.REBASE_CURSOR_REQUIRED)
        )
        val remote = RebaseRemote()

        assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())

        assertEquals(listOf(SyncV3Protocol.REBASE_CURSOR_REQUIRED, 47L), remote.pullCursors)
        assertEquals(47L, database.syncOutboxDao().getClientState()?.serverCursor)
    }

    @Test fun unavailablePullRpcReportsV3UnavailableSoCallerUsesCompatibilitySnapshot() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val profile = ProfileEntity(id = 1, portableId = portableId, name = "Local")
        database.addonDao().insertProfile(profile)
        val commands = UserStateCommandRepository(database, fixture.auth)
        commands.saveProfile(profile.copy(name = "Changed"))
        val remote = object : SyncV3Remote {
            override suspend fun push(operations: List<SyncOutboxEntity>) =
                SyncV3PushResult(operations.map { it.operationId }.toSet(), 1L)

            override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage? = null
        }

        assertEquals(false, AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
        // The accepted push may be acknowledged; the unchanged cursor preserves retry/fallback
        // semantics without falsely claiming that the other direction completed.
        assertEquals(0L, database.syncOutboxDao().getClientState()?.serverCursor)
    }

    @Test fun lostPushAcknowledgementRetriesSameOperationWithoutDuplicatingState() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val profile = ProfileEntity(id = 1, portableId = portableId, name = "Retry-safe")
        database.addonDao().insertProfile(profile)
        val commands = UserStateCommandRepository(database, fixture.auth)
        commands.saveProfile(profile)
        val delegate = ReusableRemote()
        val sentBatches = mutableListOf<List<String>>()
        var loseFirstResponse = true
        val responseLossRemote = object : SyncV3Remote {
            override suspend fun push(operations: List<SyncOutboxEntity>): SyncV3PushResult {
                sentBatches += operations.map { it.operationId }
                val result = delegate.push(operations)
                if (loseFirstResponse) {
                    loseFirstResponse = false
                    throw IOException("Simulated lost acknowledgement after server commit")
                }
                return result
            }

            override suspend fun pull(afterSequence: Long, limit: Int) = delegate.pull(afterSequence, limit)
        }
        val engine = AccountSyncV3Engine(fixture.auth, database, responseLossRemote)

        try {
            engine.synchronize()
            org.junit.Assert.fail("The first lost acknowledgement should force a safe retry")
        } catch (_: IOException) { }
        assertEquals(1, database.syncOutboxDao().pendingCount(fixture.user))
        val originalId = requireNotNull(database.syncOutboxDao().pending(fixture.user, 1).singleOrNull()).operationId

        assertTrue(engine.synchronize())

        assertEquals(listOf(listOf(originalId), listOf(originalId)), sentBatches)
        assertEquals(0, database.syncOutboxDao().pendingCount(fixture.user))
        assertEquals("Retry-safe", database.addonDao().getProfileByPortableId(portableId)?.name)
    }

    @Test fun legacyPlaintextOutboxRowsAreEncryptedBeforeAnOfflineRetry() = runBlocking {
        val client = SyncClientStateEntity()
        database.syncOutboxDao().insertClientStateIfMissing(client)
        val secretPayload = "{\"value\":\"private-addon-token\"}"
        val legacy = SyncOutboxEntity(
            accountUserId = fixture.user,
            portableProfileId = "legacy-profile",
            entityKey = "b".repeat(64),
            entityType = "profile_runtime",
            operation = "UPSERT",
            entityRevision = 1,
            deviceId = client.deviceId,
            sequence = 1,
            createdAtMillis = 1,
            payloadJson = secretPayload
        )
        database.syncOutboxDao().append(legacy)
        val offline = object : SyncV3Remote {
            override suspend fun push(operations: List<SyncOutboxEntity>): SyncV3PushResult {
                assertTrue(operations.single().payloadJson.startsWith("local-v1:"))
                assertTrue(!operations.single().payloadJson.contains("private-addon-token"))
                throw IOException("offline after local encryption")
            }
            override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage =
                SyncV3PullPage(emptyList(), afterSequence, false)
        }

        try {
            AccountSyncV3Engine(fixture.auth, database, offline).synchronize()
            org.junit.Assert.fail("Offline push should remain retryable")
        } catch (_: IOException) { }

        val stored = database.syncOutboxDao().pending(fixture.user, 1).single()
        assertTrue(stored.payloadJson.startsWith("local-v1:"))
        assertTrue(!stored.payloadJson.contains("private-addon-token"))
        assertEquals(secretPayload, UserStateCommandRepository(database, fixture.auth).pending().single().payloadJson)
    }

    @Test fun pendingWorkIsScopedToTheCurrentlyAuthenticatedAccount() = runBlocking {
        val client = SyncClientStateEntity()
        database.syncOutboxDao().insertClientStateIfMissing(client)
        database.syncOutboxDao().append(SyncOutboxEntity(
            accountUserId = "another-account",
            portableProfileId = "profile",
            entityKey = "a".repeat(64),
            entityType = "profile_state",
            operation = "UPSERT",
            entityRevision = 1,
            deviceId = client.deviceId,
            sequence = 1,
            createdAtMillis = 1,
            payloadJson = "{}"
        ))

        assertEquals(1, database.syncOutboxDao().pendingCount())
        assertEquals(0, database.syncOutboxDao().pendingCount(fixture.user))
        assertEquals(false, AccountSyncV3Engine(fixture.auth, database, RebaseRemote()).hasPendingOperations())
    }

    @Test fun largeOutboxDrainsInQuotaBoundedPasses() = runBlocking {
        val commands = UserStateCommandRepository(database, fixture.auth)
        val remote = ReusableRemote()
        repeat(41) { index ->
            val profile = ProfileEntity(
                id = index + 1,
                portableId = java.util.UUID.randomUUID().toString(),
                name = "Profile $index"
            )
            database.addonDao().insertProfile(profile)
            commands.saveProfile(profile)
        }

        assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
        assertEquals(40, remote.pushedOperations)
        assertEquals(1, database.syncOutboxDao().pendingCount(fixture.user))

        assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
        assertEquals(41, remote.pushedOperations)
        assertEquals(0, database.syncOutboxDao().pendingCount(fixture.user))
    }

    @Test fun profileStateCreatesPortableProfileAndPreservesDeviceLocalRowIdentity() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val source = ProfileEntity(id = 1, portableId = portableId, name = "Shared", titleCardShape = "landscape")
        val destination = ProfileEntity(id = 7, portableId = portableId, name = "Old", isActive = true)
        database.addonDao().insertProfile(source)
        val secondDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            secondDatabase.addonDao().insertProfile(destination)
            val remote = ReusableRemote()
            val commands = UserStateCommandRepository(database, fixture.auth)
            commands.saveProfile(source.copy(titleCardShape = "landscape"))

            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, secondDatabase, remote).synchronize())

            val applied = secondDatabase.addonDao().getProfileByPortableId(portableId)
            assertEquals(7, applied?.id)
            assertEquals("landscape", applied?.titleCardShape)
            assertTrue(applied?.isActive == true)
        } finally {
            secondDatabase.close()
        }
    }

    @Test fun credentialBearingAddonConfigurationSyncsThroughEncryptedV3Payload() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val sourceProfile = ProfileEntity(id = 1, portableId = portableId, name = "Source")
        val destinationProfile = ProfileEntity(id = 7, portableId = portableId, name = "Destination")
        database.addonDao().insertProfile(sourceProfile)
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(destinationProfile)
            database.addonDao().activateProfile(1)
            destination.addonDao().activateProfile(7)
            val addonUrl = "https://catalog.example/private/abCDef0123456789abCDef0123456789/manifest.json"
            val snapshot = ProfileRuntimeSnapshot(addons = listOf(AddonEntity(
                transportUrl = addonUrl, id = "catalog", name = "Private catalog", version = "1",
                description = null, iconUrl = null
            )))
            val commands = UserStateCommandRepository(database, fixture.auth)
            assertTrue(commands.saveProfileRuntime(1, snapshot))
            val stored = database.syncOutboxDao().pending(fixture.user, 1).single()
            assertTrue(stored.payloadJson.startsWith("local-v1:"))
            val remote = ReusableRemote()
            // Publish once, then have a second device consume the encrypted value.
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals(addonUrl, destination.addonDao().getAllAddons().first().single().transportUrl)
        } finally {
            destination.close()
        }
    }

    @Test fun profileRuntimeStateReplacesOnlyTheMatchingActiveProfileRuntime() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        database.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Shared", isActive = true))
        val secondDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            secondDatabase.addonDao().insertProfile(ProfileEntity(id = 7, portableId = portableId, name = "Local", isActive = true))
            secondDatabase.addonDao().insertAddon(AddonEntity(
                transportUrl = "https://old.example/manifest.json", id = "old", name = "Old", version = "1",
                description = null, iconUrl = null
            ))
            val source = UserStateCommandRepository(database, fixture.auth)
            source.saveProfileRuntime(1, ProfileRuntimeSnapshot(addons = listOf(AddonEntity(
                transportUrl = "https://new.example/manifest.json", id = "new", name = "New", version = "2",
                description = null, iconUrl = null
            ))))
            val remote = ReusableRemote()

            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, secondDatabase, remote).synchronize())

            assertEquals(listOf("https://new.example/manifest.json"),
                secondDatabase.addonDao().getAllAddons().first().map { it.transportUrl })
        } finally {
            secondDatabase.close()
        }
    }

    @Test fun profileDeletionTombstoneDeletesRemoteProfileAndItsUserRows() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val sourceProfile = ProfileEntity(id = 1, portableId = portableId, name = "Shared", isActive = true)
        val destinationProfile = sourceProfile.copy(id = 7, name = "Local")
        database.addonDao().insertProfile(sourceProfile)
        val secondDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            secondDatabase.addonDao().insertProfile(destinationProfile)
            secondDatabase.addonDao().insertHistory(WatchHistoryEntity(7, "tt1234567", "Film", null,
                position = 50, duration = 100, lastWatched = 5, type = "movie"))
            val commands = UserStateCommandRepository(database, fixture.auth)
            assertTrue(commands.deleteProfile(1))
            val remote = ReusableRemote()

            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, secondDatabase, remote).synchronize())

            assertEquals(null, secondDatabase.addonDao().getProfileById(7))
            assertEquals(null, secondDatabase.addonDao().getHistoryItemForProfile(7, "tt1234567"))
        } finally {
            secondDatabase.close()
        }
    }

    @Test fun deletingThenRecreatingSamePortableProfilePublishesNewerState() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val sourceProfile = ProfileEntity(id = 1, portableId = portableId, name = "Before delete")
        database.addonDao().insertProfile(sourceProfile)
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(sourceProfile.copy(id = 7))
            val remote = ReusableRemote()
            val sourceCommands = UserStateCommandRepository(database, fixture.auth)
            // Seed the create operation explicitly, then verify delete and re-create ordering.
            sourceCommands.saveProfile(sourceProfile)
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals("Before delete", destination.addonDao().getProfileByPortableId(portableId)?.name)

            assertTrue(sourceCommands.deleteProfile(1))
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals(null, destination.addonDao().getProfileByPortableId(portableId))

            sourceCommands.saveProfile(sourceProfile.copy(id = 0, name = "Recreated"))
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals("Recreated", destination.addonDao().getProfileByPortableId(portableId)?.name)
        } finally {
            destination.close()
        }
    }

    @Test fun seriesNextUpUsesItsOwnPortableEntityAndDeletionTombstone() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        database.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Source"))
        val secondDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            secondDatabase.addonDao().insertProfile(ProfileEntity(id = 7, portableId = portableId, name = "Destination"))
            val commands = UserStateCommandRepository(database, fixture.auth)
            val remote = ReusableRemote()
            val nextUp = SeriesNextUpEntity(
                profileId = 1, seriesId = "tt1234567", title = "Show", poster = null,
                nextSeason = 2, nextEpisode = 5, nextEpisodeTitle = "Five", isComplete = false,
                updatedAt = 100L
            )
            commands.saveSeriesNextUp(1, nextUp.seriesId, nextUp)
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, secondDatabase, remote).synchronize())
            assertEquals(nextUp.copy(profileId = 7),
                secondDatabase.addonDao().getSeriesNextUpForProfile(7).single())

            commands.saveSeriesNextUp(1, nextUp.seriesId, null)
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, secondDatabase, remote).synchronize())
            assertTrue(secondDatabase.addonDao().getSeriesNextUpForProfile(7).isEmpty())
        } finally {
            secondDatabase.close()
        }
    }

    @Test fun playbackProgressUpsertAndClearTombstoneSyncAcrossPortableProfileIds() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        database.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Source"))
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(ProfileEntity(id = 9, portableId = portableId, name = "Destination"))
            val commands = UserStateCommandRepository(database, fixture.auth)
            val remote = ReusableRemote()
            val progress = WatchHistoryEntity(
                profileId = 1, id = "tt1234567:2:5", title = "Episode Five", poster = null,
                position = 123_000, duration = 1_800_000, lastWatched = 100, type = "series"
            )

            assertTrue(commands.savePlaybackProgress(progress))
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals(progress.copy(profileId = 9),
                destination.addonDao().getHistoryItemForProfile(9, progress.id))

            assertTrue(commands.clearPlaybackProgress(1, progress.id, progress.type))
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals(null, destination.addonDao().getHistoryItemForProfile(9, progress.id))
        } finally {
            destination.close()
        }
    }

    @Test fun libraryTombstoneRemovesVerifiedAliasByCanonicalIdentity() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        database.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Source"))
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(ProfileEntity(id = 9, portableId = portableId, name = "Destination"))
            val original = WatchlistEntity(
                profileId = 1, id = "tt1234567", type = "movie", title = "Canonical title",
                poster = null, addedAt = 10
            )
            val commands = UserStateCommandRepository(database, fixture.auth)
            val remote = ReusableRemote()

            assertTrue(commands.toggleLibrary(1, original))
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals(1, destination.addonDao().getWatchlistOnce(9).size)

            // A separately verified provider alias can have a different raw ID but shares the
            // same canonical membership key. A synced remove must clear both identities.
            destination.addonDao().removeFromWatchlist(9, original.id)
            destination.addonDao().addToWatchlist(original.copy(
                profileId = 9,
                id = "tt7654321",
                title = "Alias title",
                canonicalIdentityKey = original.canonicalIdentityKey
            ))
            assertEquals(false, commands.toggleLibrary(1, original))
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertTrue(destination.addonDao().getWatchlistOnce(9).isEmpty())
        } finally {
            destination.close()
        }
    }

    @Test fun clearingSeriesProgressRemovesRemoteEpisodesAndNextUp() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val seriesId = "tt1234567"
        database.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Source"))
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(ProfileEntity(id = 9, portableId = portableId, name = "Destination"))
            val rows = listOf(
                WatchHistoryEntity(1, "$seriesId:1:1", "One", null, position = 50_000,
                    duration = 1_000_000, lastWatched = 1, type = "series"),
                WatchHistoryEntity(1, "$seriesId:2:5", "Five", null, position = 300_000,
                    duration = 1_000_000, lastWatched = 2, type = "series"),
                WatchHistoryEntity(1, seriesId, "Show", null, position = 0,
                    duration = 0, lastWatched = 2, type = "series")
            )
            destination.addonDao().insertHistoryItems(rows.map { it.copy(profileId = 9) })
            destination.addonDao().insertSeriesNextUp(SeriesNextUpEntity(
                profileId = 9, seriesId = seriesId, title = "Show", poster = null,
                nextSeason = 2, nextEpisode = 6, nextEpisodeTitle = "Six", isComplete = false,
                updatedAt = 2
            ))
            val commands = UserStateCommandRepository(database, fixture.auth)
            val remote = ReusableRemote()

            commands.clearTitleProgress(1, seriesId, "series", rows)
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())

            rows.forEach { assertEquals(null, destination.addonDao().getHistoryItemForProfile(9, it.id)) }
            assertTrue(destination.addonDao().getSeriesNextUpForProfile(9).isEmpty())
        } finally {
            destination.close()
        }
    }

    @Test fun concurrentLocalWriteDoesNotAdvancePastRemoteWinner() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val sourceProfile = ProfileEntity(id = 1, portableId = portableId, name = "Source")
        val destinationProfile = ProfileEntity(id = 7, portableId = portableId, name = "Old")
        database.addonDao().insertProfile(sourceProfile)
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(destinationProfile)
            val remote = ReusableRemote()
            UserStateCommandRepository(database, fixture.auth).saveProfile(sourceProfile)
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())

            val localCommands = UserStateCommandRepository(destination, fixture.auth)
            remote.beforeNextPull = {
                localCommands.saveProfile(destinationProfile.copy(name = "Concurrent local edit"))
            }
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())

            // The first pull saw a local outbox write created after push began. It must keep
            // the cursor before the remote row so the next pass can resolve the actual winner.
            assertEquals(0L, destination.syncOutboxDao().getClientState()?.serverCursor)
            assertEquals("Concurrent local edit", destination.addonDao().getProfileById(7)?.name)

            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            val winner = requireNotNull(remote.profileWinner(portableId))
            val expectedName = Gson().fromJson(winner, ProfileStatePayload::class.java).profile.name
            assertEquals(expectedName, destination.addonDao().getProfileById(7)?.name)
            assertTrue((destination.syncOutboxDao().getClientState()?.serverCursor ?: 0L) > 0L)
            assertEquals(0, destination.syncOutboxDao().pendingCount())
        } finally {
            destination.close()
        }
    }

    @Test fun localEditAfterObservedRemoteRevisionGetsHigherCausalRevision() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val profile = ProfileEntity(id = 1, portableId = portableId, name = "Remote value")
        database.addonDao().insertProfile(profile)
        val remote = ReusableRemote()
        val sourceCommands = UserStateCommandRepository(database, fixture.auth)
        sourceCommands.saveProfile(profile)
        val initial = requireNotNull(database.syncOutboxDao().pending(fixture.user, 1).singleOrNull())

        // Model a device that has already edited this entity several times before publishing.
        // The payload and operation ID stay unchanged; only the entity's Lamport revision matters.
        remote.push(listOf(initial.copy(entityRevision = 7L)))
        database.syncOutboxDao().acknowledge(listOf(initial.operationId))

        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(profile.copy(id = 9, name = "Old local value"))
            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            assertEquals("Remote value", destination.addonDao().getProfileById(9)?.name)

            UserStateCommandRepository(destination, fixture.auth).saveProfile(
                profile.copy(id = 9, name = "Causally newer local value")
            )
            val localEdit = requireNotNull(destination.syncOutboxDao().pending(fixture.user, 1).singleOrNull())
            assertEquals(8L, localEdit.entityRevision)

            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())
            val winner = requireNotNull(remote.profileWinner(portableId))
            assertEquals(
                "Causally newer local value",
                Gson().fromJson(winner, ProfileStatePayload::class.java).profile.name
            )
        } finally {
            destination.close()
        }
    }

    @Test fun losingOfflineWriteReplaysCurrentWinnerEvenWhenClientCursorWasAlreadyAhead() = runBlocking {
        val portableId = java.util.UUID.randomUUID().toString()
        val sourceProfile = ProfileEntity(id = 1, portableId = portableId, name = "Cloud winner")
        val destinationProfile = ProfileEntity(id = 7, portableId = portableId, name = "Old local")
        database.addonDao().insertProfile(sourceProfile)
        val destination = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            destination.addonDao().insertProfile(destinationProfile)
            val remote = ReusableRemote()
            UserStateCommandRepository(database, fixture.auth).saveProfile(sourceProfile)
            assertTrue(AccountSyncV3Engine(fixture.auth, database, remote).synchronize())

            destination.syncOutboxDao().insertClientStateIfMissing(SyncClientStateEntity(serverCursor = 1L))
            val commands = UserStateCommandRepository(destination, fixture.auth)
            commands.saveProfile(destinationProfile.copy(name = "Losing offline edit"))
            val outbox = destination.syncOutboxDao()
            val localWrite = requireNotNull(outbox.pending(fixture.user, 1).singleOrNull())
            outbox.acknowledge(listOf(localWrite.operationId))
            // Simulate an offline client that has not observed the winner's revision. The UUID
            // sorts below the winner, making the deterministic equal-revision conflict lose.
            outbox.append(localWrite.copy(
                entityRevision = 1L,
                deviceId = "00000000-0000-0000-0000-000000000000",
                sequence = 1L
            ))

            assertTrue(AccountSyncV3Engine(fixture.auth, destination, remote).synchronize())

            assertEquals("Cloud winner", destination.addonDao().getProfileById(7)?.name)
            assertEquals(0, outbox.pendingCount())
            assertTrue((outbox.getClientState()?.serverCursor ?: 0L) > 1L)
        } finally {
            destination.close()
        }
    }

    private class RebaseRemote : SyncV3Remote {
        val pullCursors = mutableListOf<Long>()

        override suspend fun push(operations: List<SyncOutboxEntity>) =
            SyncV3PushResult(operations.map { it.operationId }.toSet(), 47L)

        override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage {
            pullCursors += afterSequence
            return SyncV3PullPage(emptyList(), 47L, hasMore = false)
        }
    }

    private inner class ReusableRemote : SyncV3Remote {
        private val rows = linkedMapOf<String, SyncV3RemoteOperation>()
        private val deviceSequences = mutableMapOf<String, Long>()
        private var cursor = 0L
        var pushedOperations = 0
        var beforeNextPull: (suspend () -> Unit)? = null

        fun profileWinner(portableId: String): String? = rows.values
            .firstOrNull { it.portableProfileId == portableId && it.entityType == "profile_state" }
            ?.let { SyncV3Protocol.decryptPayload(it, fixture.user, fixture.key) }

        override suspend fun push(operations: List<SyncOutboxEntity>): SyncV3PushResult {
            pushedOperations += operations.size
            val accepted = mutableSetOf<String>()
            operations.forEach { row ->
                val sequenceKey = "${row.accountUserId}/${row.deviceId}"
                if (row.sequence <= (deviceSequences[sequenceKey] ?: 0L)) {
                    accepted += row.operationId
                    return@forEach
                }
                deviceSequences[sequenceKey] = row.sequence
                val wire = SyncV3Protocol.encodeForPush(row, fixture.user, fixture.key)
                val prior = rows[row.entityKey]
                val isWinner = prior == null || row.entityRevision > prior.entityRevision ||
                    (row.entityRevision == prior.entityRevision && row.deviceId > prior.deviceId)
                if (isWinner) {
                    val op = SyncV3RemoteOperation(
                        ++cursor, row.operationId, row.portableProfileId, row.entityKey, row.entityType,
                        row.operation, row.entityRevision, row.deviceId, row.sequence, wire.get("ciphertext").asString
                    )
                    if (row.entityType == "profile_state" && row.operation == "DELETE") {
                        rows.entries.removeAll { it.value.portableProfileId == row.portableProfileId }
                    }
                    rows[row.entityKey] = op
                } else {
                    // Match the server's conflict re-publication so even a client whose cursor
                    // was ahead of the prior winning row receives the current value again.
                    val winner = requireNotNull(prior)
                    rows[row.entityKey] = winner.copy(serverSequence = ++cursor)
                }
                accepted += row.operationId
            }
            return SyncV3PushResult(accepted, cursor)
        }

        override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage {
            beforeNextPull?.also { beforeNextPull = null; it() }
            val operations = rows.values.filter { it.serverSequence > afterSequence }.take(limit)
            return SyncV3PullPage(operations, operations.lastOrNull()?.serverSequence ?: cursor, false)
        }
    }
}
