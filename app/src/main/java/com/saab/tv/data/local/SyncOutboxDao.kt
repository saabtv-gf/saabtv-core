package com.saab.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.saab.tv.data.model.SyncClientStateEntity
import com.saab.tv.data.model.SyncEntityRevisionEntity
import com.saab.tv.data.model.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_client_state WHERE id = 1 LIMIT 1")
    suspend fun getClientState(): SyncClientStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClientStateIfMissing(state: SyncClientStateEntity)

    @Query("UPDATE sync_client_state SET nextSequence = :nextSequence WHERE id = 1")
    suspend fun setNextSequence(nextSequence: Long)

    @Query("UPDATE sync_client_state SET serverCursor = :cursor WHERE id = 1")
    suspend fun setServerCursor(cursor: Long)

    @Query("SELECT * FROM sync_entity_revisions WHERE entityKey = :entityKey LIMIT 1")
    suspend fun getRevision(entityKey: String): SyncEntityRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRevision(revision: SyncEntityRevisionEntity)

    /** Remember the largest server-issued revision before creating the next local command. */
    @Query("INSERT INTO sync_entity_revisions(entityKey, revision) VALUES(:entityKey, :revision) " +
        "ON CONFLICT(entityKey) DO UPDATE SET revision = MAX(sync_entity_revisions.revision, excluded.revision)")
    suspend fun observeRevision(entityKey: String, revision: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(operation: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE entityKey = :entityKey")
    suspend fun coalesceEntity(entityKey: String): Int

    @Query("SELECT * FROM sync_outbox WHERE accountUserId = :accountUserId ORDER BY sequence ASC LIMIT :limit")
    suspend fun pending(accountUserId: String, limit: Int): List<SyncOutboxEntity>

    /** Compare-and-set migration for pre-encryption journal rows; never overwrites a newer command. */
    @Query("UPDATE sync_outbox SET payloadJson = :encryptedPayload WHERE operationId = :operationId AND payloadJson = :expectedPayload")
    suspend fun replacePayload(operationId: String, expectedPayload: String, encryptedPayload: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountUserId = :accountUserId")
    suspend fun pendingCount(accountUserId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE accountUserId = :accountUserId AND entityKey = :entityKey)")
    suspend fun hasPendingEntity(accountUserId: String, entityKey: String): Boolean

    @Query("DELETE FROM sync_outbox WHERE operationId IN (:operationIds)")
    suspend fun acknowledge(operationIds: List<String>): Int

    @Query("DELETE FROM sync_outbox WHERE accountUserId = :accountUserId")
    suspend fun clearPendingForAccount(accountUserId: String): Int

    /** A profile deletion supersedes its unsent child mutations on this device. */
    @Query("DELETE FROM sync_outbox WHERE accountUserId = :accountUserId AND portableProfileId = :portableProfileId")
    suspend fun clearPendingForProfile(accountUserId: String, portableProfileId: String): Int
}
