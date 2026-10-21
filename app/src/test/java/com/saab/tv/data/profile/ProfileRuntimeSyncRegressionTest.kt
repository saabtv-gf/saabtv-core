package com.saab.tv.data.profile

import android.app.Application
import com.saab.tv.data.account.UserStateCommandRepository
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.remote.StremioAuthService
import com.saab.tv.testing.AccountTransportFixture
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.*
import androidx.room.withTransaction
import com.saab.tv.data.account.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileRuntimeSyncRegressionTest {
    @Test fun serverEchoOfIdenticalRuntimeDoesNotReplaceRowsAgain() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val app = OfflineAppFixture(context)
        val account = AccountTransportFixture(context)
        try {
            var replacements = 0
            val dao = java.lang.reflect.Proxy.newProxyInstance(
                com.saab.tv.data.local.AddonDao::class.java.classLoader,
                arrayOf(com.saab.tv.data.local.AddonDao::class.java)
            ) { _, method, args ->
                if (method.name == "replaceRuntimeState") replacements++
                method.invoke(app.dao, *(args ?: emptyArray()))
            } as com.saab.tv.data.local.AddonDao
            val manager = ProfileConfigurationManager(context, dao,
                StremioAuthManager(context, StremioAuthService()), app.repository, app.display,
                UserStateCommandRepository(app.db, account.auth))
            val profile = ProfileEntity(id = 1, name = "Room", isActive = true)
            app.dao.insertProfile(profile)
            val snapshot = ProfileRuntimeSnapshot(addons = listOf(
                AddonEntity("https://fixture.invalid", "fixture", "Runtime", "1", null, null)
            ))
            repeat(2) { manager.withRuntimeStateLock { manager.applyRuntimeSnapshotLocked(1, snapshot) } }
            assertEquals(1, replacements)
            assertEquals("Runtime", app.dao.getRuntimeStateForProfile(profile.portableId).addons.single().name)
        } finally { app.close() }
    }

    @Test fun remoteApplyDoesNotHoldRoomWriteTransactionWhileWaitingForLocalRuntimeLock() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val app = OfflineAppFixture(context)
        val account = AccountTransportFixture(context)
        var sync: Deferred<Boolean>? = null
        try {
            val commands = UserStateCommandRepository(app.db, account.auth)
            val manager = ProfileConfigurationManager(context, app.dao,
                StremioAuthManager(context, StremioAuthService()), app.repository, app.display, commands)
            app.dao.insertProfile(ProfileEntity(id = 1, name = "Room", isActive = true))
            commands.saveProfileRuntime(1, ProfileRuntimeSnapshot(addons = listOf(
                AddonEntity("https://fixture.invalid", "fixture", "Remote", "1", null, null)
            )))
            val pulled = CompletableDeferred<Unit>()
            val remote = object : SyncV3Remote {
                private lateinit var page: SyncV3PullPage
                override suspend fun push(operations: List<com.saab.tv.data.model.SyncOutboxEntity>): SyncV3PushResult {
                    val row = SyncV3Protocol.encodeForPush(operations.single(), account.user, account.key)
                        .apply { addProperty("server_sequence", 1) }
                    page = SyncV3Protocol.parsePullPage(com.google.gson.JsonObject().apply {
                        addProperty("cursor", 1); addProperty("has_more", false)
                        add("operations", com.google.gson.JsonArray().apply { add(row) })
                    }.toString())
                    return SyncV3PushResult(operations.map { it.operationId }.toSet(), 1)
                }
                override suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage {
                    pulled.complete(Unit)
                    return page
                }
            }
            manager.withRuntimeStateLock {
                sync = async(Dispatchers.Default) { AccountSyncV3Engine(account.auth, app.db, remote, manager).synchronize() }
                withTimeout(2_000) { pulled.await() }
                // Allow the remote task to reach the held runtime lock before
                // committing a local write in the opposite coroutine.
                delay(100)
                withTimeout(2_000) {
                    app.db.withTransaction {
                        app.dao.updateProfile(requireNotNull(app.dao.getProfileById(1)).copy(name = "Local edit"))
                    }
                }
            }
            assertTrue(withTimeout(2_000) { requireNotNull(sync).await() })
            assertEquals("Local edit", app.dao.getProfileById(1)?.name)
        } finally { sync?.cancelAndJoin(); app.close() }
    }

    @Test fun failedJournalIsRetriedBeforeAnUnchangedSnapshotBecomesTheBaseline() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val account = AccountTransportFixture(context)
        val app = OfflineAppFixture(context)
        try {
            val commands = UserStateCommandRepository(app.db, account.auth)
            val manager = ProfileConfigurationManager(context, app.dao,
                StremioAuthManager(context, StremioAuthService()), app.repository, app.display, commands)
            app.dao.insertProfile(ProfileEntity(id = 1, name = "Room", isActive = true))
            account.prefs.edit().remove("sync_key").commit()
            try { manager.saveRuntimeState(1); fail("Missing key should fail journaling") }
            catch (_: java.io.IOException) { }
            account.prefs.edit().putString("sync_key", android.util.Base64.encodeToString(account.key, android.util.Base64.NO_WRAP)).commit()
            manager.saveRuntimeState(1)
            assertEquals(1, commands.pending().size)
        } finally { app.close() }
    }

    @Test fun unchangedRuntimeSaveDoesNotJournalOrAdvanceRevisionAfterAcknowledgement() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val account = AccountTransportFixture(context)
        val app = OfflineAppFixture(context)
        try {
            val commands = UserStateCommandRepository(app.db, account.auth)
            val manager = ProfileConfigurationManager(context, app.dao,
                StremioAuthManager(context, StremioAuthService()), app.repository, app.display, commands)
            val profile = ProfileEntity(id = 1, name = "Room", isActive = true)
            app.dao.insertProfile(profile)
            manager.saveRuntimeState(1)
            val initial = commands.pending().single()
            assertEquals(1L, initial.entityRevision)
            commands.acknowledge(listOf(initial.operationId))
            repeat(3) { manager.saveActiveRuntimeState() }
            assertTrue(commands.pending().isEmpty())

            app.dao.insertAddon(AddonEntity(transportUrl = "https://fixture.invalid", id = "fixture",
                name = "Changed", version = "1", description = "", iconUrl = null,
                profileOwnerId = profile.portableId))
            manager.saveActiveRuntimeState()
            val changed = commands.pending().single()
            assertEquals(2L, changed.entityRevision)
            assertTrue(changed.payloadJson.contains("Changed"))
        } finally { app.close() }
    }
}
