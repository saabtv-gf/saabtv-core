package com.saab.tv.data.account

import android.app.Application
import android.util.Base64
import com.google.gson.*
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CloudOrchestrationP0Test {
    private lateinit var transport: AccountTransportFixture
    private lateinit var app: OfflineAppFixture
    private lateinit var cloud: AccountCloudStore
    private var row: JsonObject? = null
    private val objects = mutableMapOf<String,String>()
    private val changedCounts = mutableListOf<Int>()
    private var failWrites = false
    private var failedWriteStatus = 503
    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        transport = AccountTransportFixture(context)
        transport.handler = { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/rpc/saabtv_sync_version") -> 200 to "2"
                path.endsWith("/rpc/saabtv_save_components") -> {
                    if (failWrites) failedWriteStatus to "{}" else {
                        val buffer=Buffer(); request.body!!.writeTo(buffer)
                        val body=JsonParser.parseString(buffer.readUtf8()).asJsonObject
                        val expected=body.get("expected_revision").asLong
                        val revision=row?.get("revision")?.asLong ?: 0L
                        if(expected!=revision) 200 to """{"conflict":true}""" else {
                            val changed=body.getAsJsonArray("changed_objects"); changedCounts+=changed.size()
                            changed.forEach { objects[it.asJsonObject.get("object_id").asString]=it.asJsonObject.get("ciphertext").asString }
                            row=JsonObject().apply { addProperty("revision",revision+1);add("ciphertext",body.get("encrypted_state"));addProperty("updated_at",java.time.Instant.now().toString()) }
                            200 to """{"conflict":false,"revision":${revision+1}}"""
                        }
                    }
                }
                path.endsWith("/saabtv_account_state") -> 200 to JsonArray().apply { row?.let { add(it.deepCopy()) } }.toString()
                path.endsWith("/saabtv_account_objects") -> 200 to JsonArray().apply {
                    objects.forEach { (id,cipher) -> add(JsonObject().apply {addProperty("object_id",id);addProperty("ciphertext",cipher)}) }
                }.toString()
                else -> error("Unmocked cloud request $path")
            }
        }
        app = OfflineAppFixture(context)
        // OfflineAppFixture's signed-out auth manager clears the process account
        // marker. Restore the authenticated test identity before taking cloud snapshots.
        AccountStorage.setUserId(context, transport.user)
        app.dao.insertProfile(ProfileEntity(id=1,name="Local"))
        cloud = AccountCloudStore(context,transport.auth,app.db)
    }
    @After fun cleanup() { app.close() }

    @Test fun startupRestoresRuntimeThroughTheSharedDatabaseAndLeavesItOpen() = runBlocking {
        app.dao.activateProfile(1)
        val profile = requireNotNull(app.dao.getProfileById(1))
        val snapshot = com.saab.tv.data.profile.ProfileRuntimeSnapshot(addons = listOf(
            com.saab.tv.data.model.AddonEntity("https://fixture.invalid", "fixture", "Restored", "1", null, null)
        ))
        UserStateCommandRepository(app.db, transport.auth).saveProfileRuntime(1, snapshot)
        val baselineHandler = transport.handler
        var pushedRows = JsonArray()
        transport.handler = { request ->
            when {
                request.url.encodedPath.endsWith("saabtv_sync_version") -> 200 to "3"
                request.url.encodedPath.endsWith("saabtv_sync_v3_push") -> {
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    pushedRows = JsonParser.parseString(buffer.readUtf8()).asJsonObject.getAsJsonArray("operations")
                    assertTrue("Unchanged startup must not send an empty push", pushedRows.size() > 0)
                    200 to JsonObject().apply {
                        addProperty("cursor", 1)
                        add("accepted_operation_ids", JsonArray().apply {
                            pushedRows.forEach { add(it.asJsonObject.get("operation_id")) }
                        })
                    }.toString()
                }
                request.url.encodedPath.endsWith("saabtv_sync_v3_pull") -> {
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    val after = JsonParser.parseString(buffer.readUtf8()).asJsonObject.get("after_sequence").asLong
                    200 to JsonObject().apply {
                        addProperty("cursor", 1); addProperty("has_more", false)
                        add("operations", JsonArray().apply {
                            if (after == 0L) pushedRows.forEach {
                                add(it.asJsonObject.deepCopy().apply { addProperty("server_sequence", 1) })
                            }
                        })
                    }.toString()
                }
                else -> baselineHandler(request)
            }
        }
        assertFalse(AccountSyncManager.prepareAccountData(transport.context, transport.auth, app.db, app.configuration))
        assertTrue(app.db.isOpen)
        assertEquals("Restored", app.dao.getRuntimeStateForProfile(profile.portableId).addons.single().name)
        assertEquals(1L, app.db.syncOutboxDao().getClientState()?.serverCursor)
        assertFalse(AccountSyncManager.prepareAccountData(transport.context, transport.auth, app.db, app.configuration))
        assertTrue(app.db.isOpen)
    }

    private fun remoteSnapshot(name: String, changedAt: Long, revision: Long) {
        val snapshot=JsonParser.parseString(AccountSnapshotStore(transport.context,app.db).capture().toString(Charsets.UTF_8)).asJsonObject
        snapshot.getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.addProperty("name",name)
        snapshot.addProperty("changedAt",changedAt)
        row=JsonObject().apply {
            addProperty("revision",revision);addProperty("updated_at",java.time.Instant.now().toString())
            addProperty("ciphertext",Base64.encodeToString(AccountSnapshotCrypto.encrypt(snapshot.toString().toByteArray(),transport.key,transport.user),Base64.NO_WRAP))
        }
    }
    @Test fun initialUploadAndUnchangedSecondUploadAvoidDuplicateTraffic() = runBlocking {
        assertTrue(cloud.upload()); val calls=transport.requests.size
        assertFalse(cloud.upload()); assertEquals(calls,transport.requests.size)
        assertTrue(changedCounts.single()>0)
    }
    @Test fun initializeWithCurrentSnapshotBaselineDoesNotUploadCompatibilitySnapshot() = runBlocking {
        assertTrue(cloud.upload())
        val calls = transport.requests.size

        assertFalse(cloud.initialize())
        assertEquals(calls + 1, transport.requests.size) // only the remote-head probe
    }
    @Test fun oneProfileChangeUploadsFewerComponentsThanFullSnapshot() = runBlocking {
        cloud.upload(); app.dao.insertProfile(ProfileEntity(id=1,name="Changed"));cloud.noteLocalChange()
        assertTrue(cloud.upload()); assertTrue(changedCounts.last()<changedCounts.first())
    }
    @Test fun newerCloudAutomaticallyReplacesOlderLocalCopy() = runBlocking {
        cloud.upload(); remoteSnapshot("Cloud Newer",System.currentTimeMillis()+60_000,2)
        assertTrue(cloud.initialize()); assertEquals("Cloud Newer",app.dao.getProfileById(1)?.name)
    }
    @Test fun newerLocalCopyUploadsBeforeAppContinues() = runBlocking {
        cloud.upload(); remoteSnapshot("Older Cloud",1,2)
        app.dao.insertProfile(ProfileEntity(id=1,name="Local Newer"));cloud.noteLocalChange()
        assertFalse(cloud.initialize()); assertEquals("Local Newer",app.dao.getProfileById(1)?.name)
        assertEquals(3L,row?.get("revision")?.asLong)
    }
    @Test fun staleDeviceRevisionConflictPreservesDirtyLocalUntilNewerCloudIsApplied() = runBlocking {
        cloud.upload()
        app.dao.insertProfile(ProfileEntity(id=1,name="Unsynced older device")); cloud.noteLocalChange()
        remoteSnapshot("Concurrent newer device",System.currentTimeMillis()+60_000,2)

        try { cloud.upload(); fail("A stale revision must not overwrite the newer cloud snapshot") }
        catch (_: java.io.IOException) { }
        assertEquals("Unsynced older device",app.dao.getProfileById(1)?.name)
        assertEquals(2L,row?.get("revision")?.asLong)

        cloud.initialize()
        assertEquals("Concurrent newer device",app.dao.getProfileById(1)?.name)
        assertEquals(2L,row?.get("revision")?.asLong)
    }
    @Test fun uninitializedDeviceDoesNotTreatLocalDefaultsAsNewerThanCloud() = runBlocking {
        remoteSnapshot("Account Profile",System.currentTimeMillis(),1)
        cloud.noteLocalChange();cloud.initialize()
        assertEquals("Account Profile",app.dao.getProfileById(1)?.name)
    }
    @Test fun failedUploadPreservesDirtyLocalDataAndCanRetry() = runBlocking {
        cloud.upload();app.dao.insertProfile(ProfileEntity(id=1,name="Dirty"));cloud.noteLocalChange();failWrites=true
        try { cloud.upload();fail("Expected HTTP error") } catch(_:AccountApiException) { }
        assertEquals("Dirty",app.dao.getProfileById(1)?.name)
        failWrites=false;assertTrue(cloud.upload())
    }
    @Test fun missingRemoteAfterPriorSyncPreservesLocalAndReportsError() = runBlocking {
        cloud.upload();row=null
        try { cloud.initialize();fail("Expected missing cloud error") } catch(_:java.io.IOException) { }
        assertEquals("Local",app.dao.getProfileById(1)?.name)
    }
    @Test fun tamperedRemoteCiphertextCannotMutateLocalProfiles() = runBlocking {
        cloud.upload(); row!!.addProperty("revision",2);row!!.addProperty("ciphertext","invalid")
        try { cloud.initialize();fail("Expected authentication failure") } catch(_:Exception) { }
        assertEquals("Local",app.dao.getProfileById(1)?.name)
    }

    @Test fun syncManagerReportsSuccessThenKeepsDirtyLocalDataOnRetryableFailure() = runBlocking {
        val manager = AccountSyncManager(transport.context, transport.auth, app.db, app.configuration)
        try {
            assertTrue(manager.syncNow())
            assertEquals("Synced", manager.status.value)

            app.dao.insertProfile(ProfileEntity(id = 1, name = "Pending local change"))
            cloud.noteLocalChange()
            failWrites = true
            assertFalse(manager.syncNow())
            assertTrue(manager.status.value.isNotBlank())
            assertEquals("Pending local change", app.dao.getProfileById(1)?.name)

            failWrites = false
            assertTrue(manager.syncNow())
            assertEquals("Synced", manager.status.value)
        } finally {
            manager.stop()
        }
    }

    @Test fun syncManagerWaitsForQuotaWindowAfter429InsteadOfRetryingEarly() = runBlocking {
        val manager = AccountSyncManager(transport.context, transport.auth, app.db, app.configuration)
        try {
            assertTrue(manager.syncNow())
            app.dao.insertProfile(ProfileEntity(id = 1, name = "Rate-limited local change"))
            cloud.noteLocalChange()
            failWrites = true
            failedWriteStatus = 429
            assertFalse(manager.syncNow())
            val writesAtLimit = transport.requests.count { it.url.encodedPath.endsWith("/rpc/saabtv_save_components") }

            assertTrue(manager.syncNow(automatic = true))
            assertEquals(writesAtLimit, transport.requests.count { it.url.encodedPath.endsWith("/rpc/saabtv_save_components") })
            assertTrue(manager.status.value.contains("Too many attempts"))
        } finally {
            manager.stop()
        }
    }

    @Test fun syncManagerDefersOversizedPayloadUntilAnExplicitRetryOrNewChange() = runBlocking {
        val manager = AccountSyncManager(transport.context, transport.auth, app.db, app.configuration)
        try {
            assertTrue(manager.syncNow())
            app.dao.insertProfile(ProfileEntity(id = 1, name = "Oversized local change"))
            cloud.noteLocalChange()
            failWrites = true
            failedWriteStatus = 413
            assertFalse(manager.syncNow())
            val writesAtLimit = transport.requests.count { it.url.encodedPath.endsWith("/rpc/saabtv_save_components") }

            assertTrue(manager.syncNow(automatic = true))
            assertEquals(writesAtLimit, transport.requests.count { it.url.encodedPath.endsWith("/rpc/saabtv_save_components") })
            assertTrue(manager.status.value.contains("larger than the server accepts"))
        } finally {
            manager.stop()
        }
    }

    @Test fun syncManagerDetectsNewerCloudAndResolvesConflictBothWays() = runBlocking {
        val manager = AccountSyncManager(transport.context, transport.auth, app.db, app.configuration)
        try {
            remoteSnapshot("Cloud copy", System.currentTimeMillis() + 60_000, 1)
            assertTrue(manager.newerCloudBackupAvailable())

            // Cross-device startup reconciliation is timestamp-directed and seamless:
            // a newer cloud copy replaces this device before sync reports success.
            assertTrue(manager.syncNow())
            assertEquals("Cloud copy", app.dao.getProfileById(1)?.name)
            assertEquals("Synced", manager.status.value)

            app.dao.insertProfile(ProfileEntity(id = 1, name = "Keep local"))
            cloud.noteLocalChange()
            remoteSnapshot("Older cloud copy", 1, (row?.get("revision")?.asLong ?: 1) + 1)
            assertTrue(manager.syncNow())
            assertEquals("Keep local", app.dao.getProfileById(1)?.name)
            assertEquals("Synced", manager.status.value)
        } finally {
            manager.stop()
        }
    }

    @Test fun accountPageSyncRestoresNewerCloudInsteadOfOnlyUploadingThisDevice() = runBlocking {
        val manager = AccountSyncManager(transport.context, transport.auth, app.db, app.configuration)
        try {
            remoteSnapshot("Newer device settings", System.currentTimeMillis() + 60_000, 1)
            assertTrue(manager.syncAndReconcile())
            assertEquals("Newer device settings", app.dao.getProfileById(1)?.name)
            assertEquals("Cloud copy restored; reopening…", manager.status.value)
        } finally {
            manager.stop()
        }
    }
}
