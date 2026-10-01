package com.saab.tv.data.account

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

/** Tiny revision probes + immutable encrypted components, committed by one CAS RPC. */
internal class AccountComponentCloud(private val context: Context, private val auth: AccountAuthManager) {
    private val root = File(AccountStorage.files(context), "cloud_components").apply { mkdirs() }
    private val manifestFile = AtomicFile(File(root, "manifest.json"))
    private val mutex = Mutex()
    private var version: Int? = null
    private val userId get() = requireNotNull(auth.userId)

    suspend fun head(): JsonObject? = JsonParser.parseString(auth.dataRequest(
        "saabtv_account_state?select=revision,updated_at&limit=1")).asJsonArray.firstOrNull()?.asJsonObject

    private fun encrypt(bytes: ByteArray, identity: String = userId): String = Base64.encodeToString(
        AccountSnapshotCrypto.encrypt(AccountSyncCodec.pack(bytes), auth.syncKey, identity), Base64.NO_WRAP)
    private fun decrypt(ciphertext: String, identity: String = userId): ByteArray = AccountSyncCodec.unpack(
        AccountSnapshotCrypto.decrypt(Base64.decode(ciphertext, Base64.NO_WRAP), auth.syncKey, identity))
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun cachedRow(): JsonObject? = runCatching {
        JsonParser.parseString(manifestFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).asJsonObject
    }.getOrNull()
    private fun cache(row: JsonObject) = write(manifestFile, row.toString().toByteArray())
    private fun decode(row: JsonObject): JsonObject = JsonParser.parseString(decrypt(row.get("ciphertext").asString)
        .toString(Charsets.UTF_8)).asJsonObject.also { require(it.get("userId").asString == userId) }

    private suspend fun row(): JsonObject? {
        val head = head() ?: return null
        cachedRow()?.takeIf { it.get("revision").asLong == head.get("revision").asLong }
            ?.takeIf { runCatching { decode(it) }.isSuccess }?.let { return it }
        return JsonParser.parseString(auth.dataRequest(
            "saabtv_account_state?select=revision,ciphertext,updated_at&limit=1")).asJsonArray.firstOrNull()?.asJsonObject
            ?.also { decode(it); cache(it) }
    }
    private fun objectFile(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(root, "$id.bin"))
    }
    private suspend fun loadObjects(manifest: JsonObject, keys: Collection<String>): Map<String, com.google.gson.JsonElement> {
        val refs = manifest.getAsJsonObject("objects")
        val missing = keys.map { refs.getAsJsonObject(it).get("id").asString }
            .distinct().filter { !objectFile(it).baseFile.exists() }
        for (batch in missing.chunked(50)) {
            val rows = JsonParser.parseString(auth.dataRequest(
                "saabtv_account_objects?select=object_id,ciphertext&object_id=in.(${batch.joinToString(",")})&limit=50")).asJsonArray
            val received = mutableSetOf<String>()
            rows.forEach {
                val item = it.asJsonObject; val id = item.get("object_id").asString
                require(id in batch)
                val ciphertext = item.get("ciphertext").asString
                decrypt(ciphertext, "$userId|component|$id") // Validate authentication before caching.
                write(objectFile(id), ciphertext.toByteArray()); received.add(id)
            }
            if (received.size != batch.size) throw IOException("Cloud components changed during read. Please retry.")
        }
        var total = 0L
        return keys.associateWith { key ->
            val ref = refs.getAsJsonObject(key); val id = ref.get("id").asString
            val file = objectFile(id)
            val plain = try {
                decrypt(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }, "$userId|component|$id")
                    .also { require(AccountSyncCodec.hash(it) == ref.get("hash").asString) }
            } catch (e: Exception) { file.delete(); throw e }
            total += plain.size
            require(total <= AccountSyncCodec.MAX_PLAIN) { "Cloud account exceeds size limit." }
            JsonParser.parseString(plain.toString(Charsets.UTF_8))
        }
    }

    suspend fun fullRemote(): JsonObject? = mutex.withLock {
        val row = row() ?: return@withLock null
        val manifest = decode(row)
        if (manifest.get("format").asInt != 2) return@withLock row
        val objects = loadObjects(manifest, manifest.getAsJsonObject("objects").keySet())
        val snapshot = AccountPartitions.join(manifest.getAsJsonObject("layout")) { requireNotNull(objects[it]) }
        // Existing restore journaling remains unchanged: locally materialize a legacy-compatible envelope.
        row.deepCopy().apply {
            addProperty("ciphertext", Base64.encodeToString(AccountSnapshotCrypto.encrypt(snapshot.toString().toByteArray(),
                auth.syncKey, userId), Base64.NO_WRAP))
        }
    }

    suspend fun history(): Pair<Long, JsonArray> = mutex.withLock {
        val row = row() ?: return@withLock 0L to JsonArray()
        val manifest = decode(row)
        val rows = if (manifest.get("format").asInt == 2) {
            val refs = manifest.getAsJsonObject("layout").getAsJsonObject("tables").getAsJsonArray("watch_history")
            val values = loadObjects(manifest, refs.map { it.asString })
            JsonArray().apply { refs.forEach { add(values.getValue(it.asString)) } }
        } else manifest.getAsJsonObject("tables").getAsJsonArray("watch_history")
        row.get("revision").asLong to rows
    }

    private suspend fun supportsComponents(): Boolean {
        if (version == null) version = try {
            JsonParser.parseString(auth.dataRequest("rpc/saabtv_sync_version", JsonObject())).asInt
        } catch (e: AccountApiException) { if (e.status == 404) 1 else throw e }
        return version == 2
    }

    suspend fun save(snapshot: ByteArray, expectedRevision: Long): JsonObject = mutex.withLock {
        if (!supportsComponents()) {
            // Pre-migration server: preserve compatibility, but retain metadata-only read savings.
            return@withLock JsonParser.parseString(auth.dataRequest("rpc/saabtv_save_account", JsonObject().apply {
                addProperty("expected_revision", expectedRevision)
                addProperty("encrypted_state", Base64.encodeToString(AccountSnapshotCrypto.encrypt(snapshot,
                    auth.syncKey, userId), Base64.NO_WRAP))
            })).asJsonObject
        }
        val baseline = if (expectedRevision > 0) row() else null
        if (baseline != null && baseline.get("revision").asLong != expectedRevision)
            return@withLock JsonObject().apply { addProperty("conflict", true) }
        val prior = baseline?.let(::decode)?.takeIf { it.get("format").asInt == 2 }?.getAsJsonObject("objects")
        val partitions = AccountPartitions.split(JsonParser.parseString(snapshot.toString(Charsets.UTF_8)).asJsonObject)
        require(partitions.values.size <= 20000)
        val refs = JsonObject(); val changes = JsonArray(); val retained = JsonArray()
        val cacheWrites = linkedMapOf<String, String>()
        partitions.values.forEach { (key, value) ->
            val bytes = value.toString().toByteArray(); val hash = AccountSyncCodec.hash(bytes)
            val old = prior?.getAsJsonObject(key)?.takeIf { it.get("hash").asString == hash }
            val ref = old?.deepCopy() ?: JsonObject().apply {
                val id = UUID.randomUUID().toString()
                addProperty("id", id); addProperty("hash", hash)
                val cipher = encrypt(bytes, "$userId|component|$id")
                changes.add(JsonObject().apply { addProperty("object_id", id); addProperty("ciphertext", cipher) })
                cacheWrites[id] = cipher
            }
            refs.add(key, ref); retained.add(ref.get("id"))
        }
        val manifest = JsonObject().apply {
            addProperty("format", 2); addProperty("userId", userId)
            add("layout", partitions.layout); add("objects", refs)
        }
        val ciphertext = encrypt(manifest.toString().toByteArray())
        val result = JsonParser.parseString(auth.dataRequest("rpc/saabtv_save_components", JsonObject().apply {
            addProperty("expected_revision", expectedRevision); addProperty("encrypted_state", ciphertext)
            add("changed_objects", changes); add("retained_ids", retained)
        })).asJsonObject
        if (!result.get("conflict").asBoolean) {
            AccountTransferMetrics.snapshot(context, snapshot.size.toLong(), changes.toString().toByteArray().size.toLong(),
                changes.size(), refs.size())
            cacheWrites.forEach { (id, cipher) -> write(objectFile(id), cipher.toByteArray()) }
            cache(JsonObject().apply {
                addProperty("revision", result.get("revision").asLong); addProperty("ciphertext", ciphertext)
                addProperty("updated_at", java.time.Instant.now().toString())
            })
            val ids = retained.map { it.asString }.toSet()
            root.listFiles()?.filter { it.extension == "bin" && it.nameWithoutExtension !in ids }?.forEach { it.delete() }
        }
        result
    }
}
