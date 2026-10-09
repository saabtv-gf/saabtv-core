package com.saab.tv.data.account

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.*
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.security.SecurePreferences
import java.io.File
import java.io.IOException

/** Complete portable account state; never includes videos, thumbnails or login sessions. */
class AccountSnapshotStore(private val context: Context, private val database: SaabTvDatabase) {
    companion object {
        val TABLES = listOf("profiles", "themes", "addons", "catalog_configs", "hub_rows", "hub_row_items",
            "watchlist", "watch_history", "series_next_up")
        private const val SNAPSHOT_SCHEMA = 54
        private const val MIN_SUPPORTED_SNAPSHOT_SCHEMA = 51
        const val MAX_BYTES = 14 * 1024 * 1024
        private val PREFERENCES = listOf("profile_configuration_prefs", "source_selection_prefs",
            "playback_track_selection_prefs", "playback_diagnostics_settings")
        private val SECURE = mapOf("stremio_secure_prefs" to "saabtv_stremio_master_key",
            "trakt_auth" to "saabtv_trakt_master_key", "tmdb_credentials" to "saabtv_tmdb_master_key")
    }

    fun preferenceStores(): List<SharedPreferences> = PREFERENCES.map { AccountStorage.preferences(context, it) } +
        SECURE.map { (name, alias) -> SecurePreferences.create(context, name, alias).preferences }

    fun capture(): ByteArray {
        val sql = database.openHelper.writableDatabase
        val tables = JsonObject()
        sql.beginTransaction()
        try {
            for (table in TABLES) {
                val rows = JsonArray()
                sql.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JsonObject()
                        for (column in 0 until cursor.columnCount) {
                            val key = cursor.getColumnName(column)
                            when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> row.add(key, JsonNull.INSTANCE)
                                Cursor.FIELD_TYPE_INTEGER -> row.addProperty(key, cursor.getLong(column))
                                Cursor.FIELD_TYPE_FLOAT -> row.addProperty(key, cursor.getDouble(column))
                                else -> row.addProperty(key, cursor.getString(column))
                            }
                        }
                        rows.add(mapPaths(row, restoring = false))
                    }
                }
                tables.add(table, rows)
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val prefs = JsonObject()
        PREFERENCES.forEach { prefs.add(it, encodePreferences(AccountStorage.preferences(context, it))) }
        SECURE.forEach { (name, alias) -> prefs.add(name, encodePreferences(SecurePreferences.create(context, name, alias).preferences)) }
        val files = JsonObject()
        for (directory in listOf("avatars", "hub_images", "profile_snapshots")) {
            File(AccountStorage.files(context), directory).listFiles()?.filter {
                it.isFile && !it.name.endsWith(".bak") && !it.name.endsWith(".new")
            }
                ?.sortedBy { it.name }?.forEach {
                    check(it.length() <= MAX_BYTES) { "Account file is too large to sync." }
                    val data = if (directory == "profile_snapshots") {
                        mapPaths(JsonParser.parseString(it.readText()), restoring = false).toString().toByteArray()
                    } else it.readBytes()
                    files.addProperty("$directory/${it.name}", Base64.encodeToString(data, Base64.NO_WRAP))
                }
        }
        val snapshot = JsonObject().apply {
            addProperty("format", 1)
            addProperty("schema", SNAPSHOT_SCHEMA)
            addProperty("userId", AccountStorage.userId(context))
            add("tables", tables); add("preferences", prefs); add("files", files)
        }
        return snapshot.toString().toByteArray().also {
            if (it.size > MAX_BYTES) throw IOException("Account backup exceeds 14 MB. Reduce custom avatar sizes.")
        }
    }

    fun restore(bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES)
        val snapshot = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        val snapshotSchema = snapshot.get("schema")?.asInt ?: 0
        require(snapshot.get("format").asInt == 1 && snapshotSchema in MIN_SUPPORTED_SNAPSHOT_SCHEMA..SNAPSHOT_SCHEMA) {
            "Unsupported account backup version."
        }
        require(snapshot.get("userId").asString == AccountStorage.userId(context)) { "Account backup belongs to another user." }
        val tables = snapshot.getAsJsonObject("tables")
        require(tables.keySet() == TABLES.toSet())
        val preferences = snapshot.getAsJsonObject("preferences")
        require(preferences.keySet() == (PREFERENCES + SECURE.keys).toSet())
        preferences.entrySet().forEach { (_, values) ->
            values.asJsonObject.entrySet().forEach { (_, value) ->
                val entry = value.asJsonObject
                val data = entry.get("value")
                when (entry.get("type").asString) {
                    "string" -> data.asString
                    "boolean" -> data.asBoolean
                    "int" -> data.asInt
                    "long" -> data.asLong
                    "float" -> data.asFloat
                    "set" -> data.asJsonArray.forEach { it.asString }
                    else -> throw IOException("Invalid account preference type.")
                }
            }
        }
        val root = AccountStorage.files(context)
        // Validate every file path and decoded payload before changing any state.
        val decodedFiles = snapshot.getAsJsonObject("files").entrySet().associate { (path, value) ->
            require(path.matches(Regex("(avatars|hub_images|profile_snapshots)/[a-zA-Z0-9_.-]+")))
            require(!path.substringAfter('/').startsWith("."))
            val data = Base64.decode(value.asString, Base64.NO_WRAP)
            File(root, path) to if (path.startsWith("profile_snapshots/")) {
                mapPaths(JsonParser.parseString(data.toString(Charsets.UTF_8)), restoring = true).toString().toByteArray()
            } else data
        }
        val sql = database.openHelper.writableDatabase
        for (table in TABLES) {
            val columns = mutableSetOf<String>()
            sql.query("PRAGMA table_info(`$table`)").use { while (it.moveToNext()) columns.add(it.getString(1)) }
            tables.getAsJsonArray(table).forEach {
                val row = it.asJsonObject
                if (table == "profiles" && snapshotSchema <= 51 && !row.has("skipRecap")) {
                    // Version 51 used Skip Intro as the shared IntroDB control;
                    // retain its previous recap behavior for upgraded profiles.
                    // The Room column is INTEGER. Store legacy booleans using
                    // SQLite's 1/0 representation so restore binds the right type.
                    row.addProperty("skipRecap", 1)
                }
                if (table == "profiles" && snapshotSchema <= 52 && !row.has("titleCardShape")) {
                    row.addProperty("titleCardShape", row.get("continueWatchingShape")?.asString ?: "poster")
                }
                if (table == "watchlist" && snapshotSchema <= 53) {
                    if (!row.has("background")) row.add("background", JsonNull.INSTANCE)
                    if (!row.has("logo")) row.add("logo", JsonNull.INSTANCE)
                }
                require(row.keySet() == columns)
                mapPaths(row, restoring = true)
                row.entrySet().forEach { (_, value) -> require(value.isJsonNull || value.isJsonPrimitive) }
            }
        }
        decodedFiles.forEach { (file, data) ->
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(data); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        sql.beginTransaction()
        try {
            TABLES.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
            TABLES.forEach { table ->
                tables.getAsJsonArray(table).forEach { item ->
                    val row = mapPaths(item, restoring = true).asJsonObject
                    val columns = row.keySet().toList()
                    val stmt = sql.compileStatement("INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) VALUES (${columns.joinToString(",") { "?" }})")
                    try {
                        columns.forEachIndexed { index, column ->
                            val value = row.get(column)
                            when {
                                value.isJsonNull -> stmt.bindNull(index + 1)
                                value.asJsonPrimitive.isNumber -> stmt.bindLong(index + 1, value.asLong)
                                else -> stmt.bindString(index + 1, value.asString)
                            }
                        }
                        stmt.executeInsert()
                    } finally { stmt.close() }
                }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val prefs = snapshot.getAsJsonObject("preferences")
        PREFERENCES.forEach { decodePreferences(AccountStorage.preferences(context, it), prefs.getAsJsonObject(it)) }
        SECURE.forEach { (name, alias) -> decodePreferences(SecurePreferences.create(context, name, alias).preferences, prefs.getAsJsonObject(name)) }
        // A cloud restore replaces, rather than merges, runtime/profile assets.
        // Do not re-upload files belonging to profiles deleted on another TV.
        for (directory in listOf("avatars", "hub_images", "profile_snapshots")) {
            File(root, directory).listFiles()?.filter { it.isFile && it !in decodedFiles.keys }?.forEach {
                check(it.delete()) { "Unable to remove an obsolete account asset." }
            }
        }
    }

    private fun mapPaths(value: JsonElement, restoring: Boolean): JsonElement = when {
        value.isJsonObject -> JsonObject().apply { value.asJsonObject.entrySet().forEach { (k, v) -> add(k, mapPaths(v, restoring)) } }
        value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(mapPaths(it, restoring)) } }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
            val text = value.asString
            val custom = text.startsWith("custom:")
            val path = if (custom) text.removePrefix("custom:") else text
            val prefix = if (custom) "custom:" else ""
            if (restoring && path.startsWith("account-file:")) {
                val relative = path.removePrefix("account-file:")
                require(relative.matches(Regex("(avatars|hub_images)/[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|webp)")))
                JsonPrimitive(prefix + File(AccountStorage.files(context), relative).absolutePath)
            } else if (!restoring) {
                val currentRoot = AccountStorage.files(context).absolutePath + "/"
                val legacyRoot = context.filesDir.absolutePath + "/"
                val relative = when {
                    path.startsWith(currentRoot) -> path.removePrefix(currentRoot)
                    path.startsWith(legacyRoot + "avatars/") -> path.removePrefix(legacyRoot)
                    path.startsWith(legacyRoot + "hub_item_") -> "hub_images/${File(path).name}"
                    else -> null
                }
                if (relative != null && relative.matches(Regex("(avatars|hub_images)/[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|webp)")))
                    JsonPrimitive(prefix + "account-file:$relative") else value.deepCopy()
            } else value.deepCopy()
        }
        else -> value.deepCopy()
    }

    private fun encodePreferences(prefs: SharedPreferences): JsonObject = JsonObject().apply {
        prefs.all.toSortedMap().forEach { (key, value) ->
            val item = JsonObject()
            when (value) {
                is String -> { item.addProperty("type", "string"); item.addProperty("value", value) }
                is Boolean -> { item.addProperty("type", "boolean"); item.addProperty("value", value) }
                is Int -> { item.addProperty("type", "int"); item.addProperty("value", value) }
                is Long -> { item.addProperty("type", "long"); item.addProperty("value", value) }
                is Float -> { item.addProperty("type", "float"); item.addProperty("value", value) }
                is Set<*> -> { item.addProperty("type", "set"); item.add("value", Gson().toJsonTree(value.filterIsInstance<String>().sorted())) }
                else -> return@forEach
            }
            add(key, item)
        }
    }

    private fun decodePreferences(prefs: SharedPreferences, objectValue: JsonObject?) {
        val editor = prefs.edit().clear()
        objectValue?.entrySet()?.forEach { (key, item) ->
            val entry = item.asJsonObject; val value = entry.get("value")
            when (entry.get("type").asString) {
                "string" -> editor.putString(key, value.asString)
                "boolean" -> editor.putBoolean(key, value.asBoolean)
                "int" -> editor.putInt(key, value.asInt)
                "long" -> editor.putLong(key, value.asLong)
                "float" -> editor.putFloat(key, value.asFloat)
                "set" -> editor.putStringSet(key, value.asJsonArray.map { it.asString }.toSet())
                else -> throw IOException("Invalid account preference type.")
            }
        }
        check(editor.commit())
    }
}
