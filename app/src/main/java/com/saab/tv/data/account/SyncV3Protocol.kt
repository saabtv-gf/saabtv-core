package com.saab.tv.data.account

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.model.SyncOutboxEntity
import java.util.UUID

internal data class SyncV3RemoteOperation(
    val serverSequence: Long,
    val operationId: String,
    val portableProfileId: String,
    val entityKey: String,
    val entityType: String,
    val operation: String,
    val entityRevision: Long,
    val deviceId: String,
    val sequence: Long,
    val encryptedPayload: String
)

internal data class SyncV3PullPage(
    val operations: List<SyncV3RemoteOperation>,
    val cursor: Long,
    val hasMore: Boolean
)

internal data class SyncV3PushResult(val acceptedOperationIds: Set<String>, val cursor: Long)

internal interface SyncV3Remote {
    suspend fun push(operations: List<SyncOutboxEntity>): SyncV3PushResult?
    suspend fun pull(afterSequence: Long, limit: Int): SyncV3PullPage?
}

/** Wire codec: user payloads are independently authenticated and encrypted client-side. */
internal object SyncV3Protocol {
    const val MAX_BATCH = 500
    const val MAX_PUSH_BATCH = 10
    const val MAX_PUSH_BODY_BYTES = 4 * 1024 * 1024
    const val MAX_CIPHERTEXT_CHARS = 400_000
    private const val LOCAL_PAYLOAD_PREFIX = "local-v1:"
    /** Local marker meaning a v2 snapshot was restored and the old v3 cursor must be rebased. */
    const val REBASE_CURSOR_REQUIRED = Long.MAX_VALUE

    fun encodeForPush(operation: SyncOutboxEntity, userId: String, key: ByteArray): JsonObject {
        require(operation.accountUserId == userId) { "Operation belongs to a different signed-in account" }
        requireUuid(operation.operationId)
        requireUuid(operation.deviceId)
        require(operation.portableProfileId.isNotBlank() && operation.entityKey.matches(Regex("[a-f0-9]{64}")))
        require(operation.sequence > 0 && operation.entityRevision > 0)
        require(isValidOperation(operation.entityType, operation.operation)) { "Unsupported sync v3 operation" }
        val localPayload = decryptLocalPayload(operation, userId, key)
        val plainBytes = localPayload.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            Base64.encodeToString(
                AccountSnapshotCrypto.encrypt(
                    plainBytes, key, "$userId|sync-v3|${operation.operationId}"
                ), Base64.NO_WRAP
            )
        } finally { plainBytes.fill(0) }
        require(encrypted.length <= MAX_CIPHERTEXT_CHARS)
        return JsonObject().apply {
            addProperty("operation_id", operation.operationId)
            addProperty("profile_id", operation.portableProfileId)
            addProperty("entity_key", operation.entityKey)
            addProperty("entity_type", operation.entityType)
            addProperty("operation", operation.operation)
            addProperty("entity_revision", operation.entityRevision)
            addProperty("device_id", operation.deviceId)
            addProperty("sequence", operation.sequence)
            addProperty("ciphertext", encrypted)
        }
    }

    /** Encrypts the durable Room journal too; the transport layer re-encrypts with its own AAD. */
    fun encryptLocalPayload(operationId: String, payloadJson: String, userId: String, key: ByteArray): String =
        LOCAL_PAYLOAD_PREFIX + Base64.encodeToString(
            AccountSnapshotCrypto.encrypt(
                payloadJson.toByteArray(Charsets.UTF_8), key,
                "$userId|sync-v3-local|$operationId"
            ),
            Base64.NO_WRAP
        )

    fun isLocallyEncrypted(operation: SyncOutboxEntity): Boolean =
        operation.payloadJson.startsWith(LOCAL_PAYLOAD_PREFIX)

    /** Reads pre-upgrade plaintext rows, while authenticating all newly encrypted local rows. */
    fun decryptLocalPayload(operation: SyncOutboxEntity, userId: String, key: ByteArray): String {
        if (!operation.payloadJson.startsWith(LOCAL_PAYLOAD_PREFIX)) return operation.payloadJson
        val encrypted = Base64.decode(operation.payloadJson.removePrefix(LOCAL_PAYLOAD_PREFIX), Base64.NO_WRAP)
        val plain = AccountSnapshotCrypto.decrypt(
            encrypted, key, "$userId|sync-v3-local|${operation.operationId}"
        )
        try {
            require(plain.size <= 256 * 1024)
            return plain.toString(Charsets.UTF_8).also {
                require(JsonParser.parseString(it).isJsonObject)
            }
        } finally { plain.fill(0) }
    }

    fun parsePullPage(json: String): SyncV3PullPage {
        val root = JsonParser.parseString(json).asJsonObject
        val cursor = root.get("cursor")?.asLong ?: error("Sync v3 response omitted cursor")
        require(cursor >= 0)
        val rows = root.getAsJsonArray("operations") ?: JsonArray()
        require(rows.size() <= MAX_BATCH)
        val operations = rows.map { value ->
            val row = value.asJsonObject
            val op = SyncV3RemoteOperation(
                serverSequence = row.get("server_sequence").asLong,
                operationId = row.get("operation_id").asString,
                portableProfileId = row.get("profile_id").asString,
                entityKey = row.get("entity_key").asString,
                entityType = row.get("entity_type").asString,
                operation = row.get("operation").asString,
                entityRevision = row.get("entity_revision").asLong,
                deviceId = row.get("device_id").asString,
                sequence = row.get("sequence").asLong,
                encryptedPayload = row.get("ciphertext").asString
            )
            validate(op)
            op
        }
        require(operations.zipWithNext().all { (a, b) -> a.serverSequence < b.serverSequence })
        require(operations.lastOrNull()?.serverSequence?.let { cursor >= it } ?: true)
        return SyncV3PullPage(operations, cursor, root.get("has_more")?.asBoolean ?: false)
    }

    fun decryptPayload(operation: SyncV3RemoteOperation, userId: String, key: ByteArray): String {
        val bytes = AccountSnapshotCrypto.decrypt(
            Base64.decode(operation.encryptedPayload, Base64.NO_WRAP), key,
            "$userId|sync-v3|${operation.operationId}"
        )
        try {
            require(bytes.size <= 256 * 1024)
            return bytes.toString(Charsets.UTF_8).also {
                // Reject malformed JSON before the operation reaches the local mutation layer.
                require(JsonParser.parseString(it).isJsonObject)
            }
        } finally { bytes.fill(0) }
    }

    fun validate(operation: SyncV3RemoteOperation) {
        require(operation.serverSequence > 0 && operation.sequence > 0 && operation.entityRevision > 0)
        requireUuid(operation.operationId); requireUuid(operation.deviceId)
        require(operation.portableProfileId.isNotBlank() && operation.portableProfileId.length <= 128)
        require(operation.entityKey.matches(Regex("[a-f0-9]{64}")))
        require(isValidOperation(operation.entityType, operation.operation))
        require(operation.encryptedPayload.isNotBlank() && operation.encryptedPayload.length <= MAX_CIPHERTEXT_CHARS)
    }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false))
    }

    // Only commands with a local atomic apply path are accepted. Runtime addon/configuration
    // state and secret credentials remain on the encrypted compatibility snapshot path.
    private fun isValidOperation(entityType: String, operation: String): Boolean = when (entityType) {
        "profile_state" -> operation == "UPSERT" || operation == "DELETE"
        "profile_runtime" -> operation == "UPSERT"
        "library" -> operation == "UPSERT" || operation == "DELETE"
        "watched_state" -> operation == "UPSERT"
        "series_next_up" -> operation == "UPSERT" || operation == "DELETE"
        "playback_progress" -> operation == "UPSERT" || operation == "DELETE"
        "source_selection" -> operation == "UPSERT" || operation == "DELETE"
        else -> false
    }
}
