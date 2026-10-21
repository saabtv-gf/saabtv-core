package com.saab.tv.data.account

import android.app.Application
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.SyncOutboxEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.player.SourceSelectionStore
import com.saab.tv.testing.AccountTransportFixture
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV3ProtocolTest {
    private lateinit var dbA: SaabTvDatabase
    private lateinit var dbB: SaabTvDatabase
    private lateinit var account: AccountTransportFixture
    private val portableProfileId = UUID.randomUUID().toString()
    private lateinit var remote: FakeRemote

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbA = androidx.room.Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java).allowMainThreadQueries().build()
        dbB = androidx.room.Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java).allowMainThreadQueries().build()
        account = AccountTransportFixture(context)
        remote = FakeRemote(account.user, account.key)
    }

    @After fun close() { dbA.close(); dbB.close() }

    @Test fun encryptedPayloadRoundTripsAndAuthenticatesOperationIdentity() {
        val operation = outbox()
        val wire = SyncV3Protocol.encodeForPush(operation, account.user, account.key)
        val decoded = SyncV3Protocol.decryptPayload(remote.toRemote(operation, wire), account.user, account.key)
        assertEquals(operation.payloadJson, decoded)

        val tampered = remote.toRemote(operation, wire).copy(operationId = UUID.randomUUID().toString())
        try { SyncV3Protocol.decryptPayload(tampered, account.user, account.key); fail("Wrong operation AAD accepted") }
        catch (_: Exception) { }
    }

    @Test fun localOutboxEnvelopeRoundTripsAndBindsCiphertextToOperationIdAndAccount() {
        val plain = "{\"token\":\"private-addon-token\"}"
        val original = outbox()
        val encrypted = SyncV3Protocol.encryptLocalPayload(original.operationId, plain, account.user, account.key)
        val row = original.copy(payloadJson = encrypted)
        assertFalse(encrypted.contains("private-addon-token"))
        assertEquals(plain, SyncV3Protocol.decryptLocalPayload(row, account.user, account.key))
        try {
            SyncV3Protocol.decryptLocalPayload(row, "another-account", account.key)
            fail("Local payload decrypted for another account")
        } catch (_: Exception) { }
    }

    @Test fun remoteStateMapsPortableProfileIdentityAndWatchedCommandAcrossDevices() = runBlocking {
        dbA.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableProfileId, name = "Source"))
        dbB.addonDao().insertProfile(ProfileEntity(id = 7, portableId = portableProfileId, name = "Destination"))
        val commands = UserStateCommandRepository(dbA, account.auth)
        val entry = WatchlistEntity(1, "tt1234567", "movie", "Film", null, 10L)
        assertTrue(commands.toggleLibrary(1, entry))
        val engineA = AccountSyncV3Engine(account.auth, dbA, remote)
        val engineB = AccountSyncV3Engine(account.auth, dbB, remote)
        assertTrue(engineA.synchronize())
        assertTrue(engineB.synchronize())
        assertEquals(entry.copy(profileId = 7), dbB.addonDao().getWatchlistItem(7, entry.id))

        val history = WatchHistoryEntity(1, entry.id, "Film", null, position = 900_000,
            duration = 900_000, lastWatched = 30, type = "movie", watched = true)
        commands.markWatched(1, entry.id, "movie", listOf(history))
        assertTrue(engineA.synchronize())
        assertTrue(engineB.synchronize())
        assertEquals(null, dbB.addonDao().getWatchlistItem(7, entry.id))
        assertTrue(dbB.addonDao().getHistoryItemForProfile(7, entry.id)?.watched == true)
    }

    @Test fun sourceSelectionSyncResolvesTorrentAcrossDifferentLocalProfileIdsWithoutUrls() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sourceApp = OfflineAppFixture(context)
        val destinationApp = OfflineAppFixture(context)
        val sharedProfileId = UUID.randomUUID().toString()
        try {
            sourceApp.dao.insertProfile(ProfileEntity(id = 1, portableId = sharedProfileId, name = "Shared"))
            destinationApp.dao.insertProfile(ProfileEntity(id = 7, portableId = sharedProfileId, name = "Shared"))
            sourceApp.configuration.saveRuntimeState(1)
            destinationApp.configuration.saveRuntimeState(7)
            val hash = "a".repeat(40)
            val selection = SourceSelectionStatePayload("tt1234567", "movie", hash)
            val commands = UserStateCommandRepository(sourceApp.db, account.auth)
            commands.saveSourceSelection(1, selection)
            assertTrue(commands.pending().any { it.entityType == "source_selection" })
            val remote = FakeRemote(account.user, account.key)
            val sourceStore = SourceSelectionStore(context, sourceApp.configuration)
            val destinationStore = SourceSelectionStore(context, destinationApp.configuration)
            val stale = Stream(url = "https://media.example/stale", infoHash = "b".repeat(40))
            destinationStore.rememberSelection("tt1234567", stale, "movie")

            assertTrue(AccountSyncV3Engine(account.auth, sourceApp.db, remote, sourceApp.configuration, sourceStore).synchronize())
            assertTrue(AccountSyncV3Engine(account.auth, destinationApp.db, remote, destinationApp.configuration, destinationStore).synchronize())

            val lowerRanked = Stream(url = "https://media.example/other", infoHash = "b".repeat(40))
            val selected = Stream(url = "https://media.example/secret/signed-token", infoHash = hash)
            assertEquals(selected, destinationStore.findPreferredStream("tt1234567", listOf(lowerRanked, selected)))
            assertFalse(commands.pending().any { it.payloadJson.contains("signed-token") })
        } finally {
            sourceApp.close()
            destinationApp.close()
        }
    }

    @Test fun pullPageRequiresOrderedBoundedOperationsAndValidCursor() {
        val firstRow = outbox(sequence = 1)
        val first = remote.toRemote(firstRow, SyncV3Protocol.encodeForPush(firstRow, account.user, account.key))
        val second = first.copy(operationId = UUID.randomUUID().toString(), serverSequence = 2)
        val pageJson = JsonObject().apply {
            addProperty("cursor", 2); addProperty("has_more", false)
            add("operations", JsonArray().apply { add(first.toJson()); add(second.toJson()) })
        }
        assertEquals(listOf(1L, 2L), SyncV3Protocol.parsePullPage(pageJson.toString()).operations.map { it.serverSequence })

        val unordered = pageJson.deepCopy().apply {
            add("operations", JsonArray().apply { add(second.toJson()); add(first.toJson()) })
        }
        try { SyncV3Protocol.parsePullPage(unordered.toString()); fail("Out-of-order page accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidEntityKeyAndOversizedBatchAreRejectedBeforeTransport() {
        try { SyncV3Protocol.encodeForPush(outbox().copy(entityKey = "not-a-hash"), account.user, account.key); fail() }
        catch (_: IllegalArgumentException) { }
        try { SyncV3Protocol.encodeForPush(outbox().copy(entityType = "series_next_up", operation = "PATCH"), account.user, account.key); fail() }
        catch (_: IllegalArgumentException) { }
        val nextUpDelete = outbox().copy(entityType = "series_next_up", operation = "DELETE")
        val nextUpWire = SyncV3Protocol.encodeForPush(nextUpDelete, account.user, account.key)
        SyncV3Protocol.validate(remote.toRemote(nextUpDelete, nextUpWire))
        try { SyncV3Protocol.parsePullPage(JsonObject().apply {
            addProperty("cursor", 0); add("operations", JsonArray().apply {
                repeat(SyncV3Protocol.MAX_BATCH + 1) { add(JsonObject()) }
            })
        }.toString()); fail() } catch (_: IllegalArgumentException) { }
    }

    private fun outbox(sequence: Long = 1) = SyncOutboxEntity(
        operationId = UUID.randomUUID().toString(), accountUserId = account.user, portableProfileId = portableProfileId,
        entityKey = "a".repeat(64), entityType = "library", operation = "UPSERT",
        entityRevision = sequence, deviceId = UUID.randomUUID().toString(), sequence = sequence,
        createdAtMillis = 1, payloadJson = "{\"id\":\"tt1234567\"}"
    )

    private class FakeRemote(private val user: String, private val key: ByteArray) : SyncV3Remote {
        private val state = linkedMapOf<String, SyncV3RemoteOperation>()
        private var cursor = 0L

        override suspend fun push(operations: List<SyncOutboxEntity>): SyncV3PushResult {
            val accepted = mutableSetOf<String>()
            operations.sortedBy { it.sequence }.forEach { operation ->
                val wire = SyncV3Protocol.encodeForPush(operation, user, key)
                cursor += 1
                val remoteOperation = toRemote(operation, wire, cursor)
                state[remoteOperation.entityKey] = remoteOperation
                accepted += operation.operationId
            }
            return SyncV3PushResult(accepted, cursor)
        }

        override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage {
            val rows = state.values.filter { it.serverSequence > afterSequence }
                .sortedBy { it.serverSequence }.take(limit)
            val more = state.values.any { it.serverSequence > (rows.lastOrNull()?.serverSequence ?: afterSequence) }
            return SyncV3PullPage(rows, rows.lastOrNull()?.serverSequence ?: cursor, more)
        }

        fun toRemote(row: SyncOutboxEntity, wire: JsonObject, serverSequence: Long = 1) = SyncV3RemoteOperation(
            serverSequence, row.operationId, row.portableProfileId, row.entityKey, row.entityType,
            row.operation, row.entityRevision, row.deviceId, row.sequence, wire.get("ciphertext").asString
        )
    }
}

private fun SyncV3RemoteOperation.toJson() = JsonObject().apply {
    addProperty("server_sequence", serverSequence); addProperty("operation_id", operationId)
    addProperty("profile_id", portableProfileId); addProperty("entity_key", entityKey)
    addProperty("entity_type", entityType); addProperty("operation", operation)
    addProperty("entity_revision", entityRevision); addProperty("device_id", deviceId)
    addProperty("sequence", sequence); addProperty("ciphertext", encryptedPayload)
}
