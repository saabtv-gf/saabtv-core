package com.saab.tv.data.account

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Compression happens before encryption. Never attempt to compress ciphertext. */
internal object AccountSyncCodec {
    private val magic = byteArrayOf(83, 84, 90, 49)
    const val MAX_PLAIN = 14 * 1024 * 1024 + 1024
    fun pack(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_PLAIN)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        val compressed = output.toByteArray()
        return if (compressed.size + magic.size < bytes.size) magic + compressed else bytes
    }
    fun unpack(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_PLAIN)
        if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(magic)) return bytes
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes, 4, bytes.size - 4)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_PLAIN) { "Cloud component is too large." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Logical component names/hashes stay inside the encrypted manifest. */
internal data class AccountPartitions(val layout: JsonObject, val values: Map<String, JsonElement>) {
    companion object {
        fun split(snapshot: JsonObject): AccountPartitions {
            val values = linkedMapOf<String, JsonElement>()
            val layout = snapshot.deepCopy()
            val tables = JsonObject()
            snapshot.getAsJsonObject("tables").entrySet().forEach { (table, rows) ->
                if (table == "watch_history") {
                    val refs = JsonArray()
                    rows.asJsonArray.forEach { row ->
                        val item = row.asJsonObject
                        val identity = JsonArray().apply { add(item.get("profileId")); add(item.get("id")) }
                        val key = "history:" + AccountSyncCodec.hash(identity.toString().toByteArray())
                        require(!values.containsKey(key)) { "Duplicate progress identity." }
                        values[key] = row.deepCopy(); refs.add(key)
                    }
                    tables.add(table, refs)
                } else {
                    val key = "table:$table"
                    values[key] = rows.deepCopy(); tables.addProperty(table, key)
                }
            }
            layout.add("tables", tables)
            val preferences = JsonObject()
            snapshot.getAsJsonObject("preferences").entrySet().forEach { (store, prefs) ->
                val refs = JsonObject()
                prefs.asJsonObject.entrySet().forEach { (name, value) ->
                    val identity = JsonArray().apply { add(store); add(name) }
                    val key = "pref:" + AccountSyncCodec.hash(identity.toString().toByteArray())
                    values[key] = value.deepCopy(); refs.addProperty(name, key)
                }
                preferences.add(store, refs)
            }
            layout.add("preferences", preferences)
            val files = JsonObject()
            snapshot.getAsJsonObject("files").entrySet().forEach { (path, value) ->
                val key = "file:$path"
                values[key] = value.deepCopy(); files.addProperty(path, key)
            }
            layout.add("files", files)
            return AccountPartitions(layout, values)
        }

        fun join(layout: JsonObject, load: (String) -> JsonElement): JsonObject = layout.deepCopy().apply {
            val tables = JsonObject()
            layout.getAsJsonObject("tables").entrySet().forEach { (table, refs) ->
                tables.add(table, if (table == "watch_history") JsonArray().apply {
                    refs.asJsonArray.forEach { add(load(it.asString)) }
                } else load(refs.asString))
            }
            add("tables", tables)
            val preferences = JsonObject()
            layout.getAsJsonObject("preferences").entrySet().forEach { (store, refs) ->
                preferences.add(store, JsonObject().apply {
                    refs.asJsonObject.entrySet().forEach { (key, ref) -> add(key, load(ref.asString)) }
                })
            }
            add("preferences", preferences)
            add("files", JsonObject().apply {
                layout.getAsJsonObject("files").entrySet().forEach { (key, ref) -> add(key, load(ref.asString)) }
            })
        }
    }
}
