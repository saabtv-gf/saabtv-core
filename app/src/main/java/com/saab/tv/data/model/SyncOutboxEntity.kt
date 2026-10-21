package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

/** Local durable command awaiting encrypted account-sync acknowledgement. */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["sequence"]),
        Index(value = ["accountUserId", "sequence"]),
        Index(value = ["portableProfileId", "sequence"]),
        Index(value = ["entityKey", "entityRevision"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey val operationId: String = UUID.randomUUID().toString(),
    /** Prevents queued state from one signed-in account being uploaded after an account switch. */
    @ColumnInfo(defaultValue = "''") val accountUserId: String = "",
    val portableProfileId: String,
    /** SHA-256 of profile + semantic entity key; titles/IDs are not exposed to SQL. */
    val entityKey: String,
    val entityType: String,
    val operation: String,
    val entityRevision: Long,
    val deviceId: String,
    val sequence: Long,
    val createdAtMillis: Long,
    /** AES-GCM local envelope for new rows; legacy plaintext rows remain readable for compatibility. */
    val payloadJson: String
)

@Entity(tableName = "sync_entity_revisions")
data class SyncEntityRevisionEntity(
    @PrimaryKey val entityKey: String,
    val revision: Long
)

@Entity(tableName = "sync_client_state")
data class SyncClientStateEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String = UUID.randomUUID().toString(),
    val nextSequence: Long = 1L,
    @ColumnInfo(defaultValue = "0")
    val serverCursor: Long = 0L
)
