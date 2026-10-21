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
        /** Stable encrypted user-state wire rows; Room-only caches/outbox tables never enter snapshots. */
        private val SNAPSHOT_COLUMNS = linkedMapOf(
            "profiles" to listOf(
                "id", "name", "portableId", "themeId", "avatarRef", "pinHash", "isActive", "roundCorners",
                "hubRoundCorners", "continueWatchingShape", "titleCardShape", "navPosition", "splashEnabled",
                "homeTabLayout", "moviesTabLayout", "seriesTabLayout", "homeHeroCategory", "homeHeroPosterCount",
                "homeHeroAutoScrollSeconds", "moviesHeroCategory", "moviesHeroPosterCount", "moviesHeroAutoScrollSeconds",
                "seriesHeroCategory", "seriesHeroPosterCount", "seriesHeroAutoScrollSeconds", "tunnelingEnabled",
                "mapDV7ToHevc", "decoderPriority", "frameRateMatching", "playerPreference", "seekTimeIntervalSeconds",
                "seekThumbnailsEnabled", "seekThumbnailIntervalSeconds", "autoplayNextEpisode", "autoplayThresholdMode",
                "autoplayThresholdPercent", "autoplayThresholdSeconds", "autoSelectSource", "rememberSourceSelection",
                "sourceSortingEnabled", "sourceSortPrimary", "sourceSortSecondary", "sourceEnabledQualities",
                "sourceExcludePhrases", "sourceMaxSizeGb", "sourceExcludedFormats", "sourceSeasonPacksOnly",
                "sourceHideZeroSeeders", "sourceLanguagePriority1", "sourceLanguagePriority2", "sourceLanguagePriority3",
                "skipIntro", "skipRecap", "autoSkipIntro", "introSkipCountdownSeconds", "outroSkipCountdownSeconds",
                "preferredAudioLanguage", "preferredAudioLanguageSecondary", "preferredSubtitleLanguage",
                "preferredSubtitleLanguageSecondary", "subtitleSize", "subtitleOffset", "subtitleTextColor",
                "subtitleBackgroundColor", "assRendererEnabled", "watchedThreshold", "tmdbEnabled", "tmdbLanguage",
                "homeTmdbRails"
            ),
            "themes" to listOf("id", "name", "primaryColor", "backgroundColor", "surfaceColor", "textColor",
                "textMutedColor", "errorColor", "isBuiltIn", "category"),
            "addons" to listOf("transportUrl", "id", "name", "version", "description", "iconUrl", "isTrusted",
                "isEnabled", "nickname", "catalogsJson", "supportsMeta", "supportsStream", "typesJson", "idPrefixesJson", "sortOrder"),
            "catalog_configs" to listOf("uniqueId", "transportUrl", "addonName", "catalogType", "catalogId", "catalogName",
                "customTitle", "showInHome", "showInMovies", "showInSeries", "homeOrder", "moviesOrder", "seriesOrder",
                "isInfiniteLoopEnabled", "visibleItemCount", "isInfiniteScrollingEnabled"),
            "hub_rows" to listOf("id", "title", "shape", "showInHome", "showInMovies", "showInSeries", "homeOrder",
                "moviesOrder", "seriesOrder", "createdAt"),
            "hub_row_items" to listOf("hubRowId", "configUniqueId", "title", "customImageUrl", "itemOrder"),
            "watchlist" to listOf("profileId", "id", "type", "title", "poster", "addedAt", "background", "logo",
                "sourceAddonBaseUrl", "sourceAddonKey", "canonicalIdentityKey"),
            "watch_history" to listOf("profileId", "id", "title", "poster", "background", "logo", "position", "duration",
                "lastWatched", "type", "watched", "scrobbled", "canonicalIdentityKey"),
            "series_next_up" to listOf("profileId", "seriesId", "title", "poster", "nextSeason", "nextEpisode",
                "nextEpisodeTitle", "nextReleased", "isComplete", "isNewEpisode", "updatedAt", "canonicalIdentityKey")
        )
        val TABLES: List<String> = SNAPSHOT_COLUMNS.keys.toList()
        /** Runtime ownership uses v3; schema 63 makes integration credential keys portable. */
        private const val SNAPSHOT_SCHEMA = 64
        private const val MIN_SUPPORTED_SNAPSHOT_SCHEMA = 51
        const val MAX_BYTES = 14 * 1024 * 1024
        private const val LEGACY_PROFILE_OWNER_ID = "legacy-default"
        private const val LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
        private const val PORTABLE_PROFILE_ID_PREFIX = "portable_profile_id_"
        private val RUNTIME_CONFIGURATION_TABLES = setOf("addons", "catalog_configs", "hub_rows", "hub_row_items")
        private val PREFERENCES = listOf("profile_configuration_prefs", "source_selection_prefs",
            "playback_track_selection_prefs")
        private const val LEGACY_DEVICE_PREFERENCES = "playback_diagnostics_settings"
        private val PROFILE_PREFERENCE_KEY = Regex(
            "^(hide_episode_spoilers|trailer_preview|trailer_delay|trailer_muted|trailer_presentation)_(u[0-9a-zA-Z-]+|[0-9]+)$"
        )
        private val LOCAL_PROFILE_PREFERENCE_KEY = Regex(
            "^(hide_episode_spoilers|trailer_preview|trailer_delay|trailer_muted|trailer_presentation)_[0-9]+$"
        )
        private val SECURE = mapOf("stremio_secure_prefs" to "saabtv_stremio_master_key",
            "trakt_auth" to "saabtv_trakt_master_key", "tmdb_credentials" to "saabtv_tmdb_master_key")
        private val STREMIO_PROFILE_KEY = Regex("^(stremio_auth_key|stremio_email)_profile_(u?[0-9a-zA-Z-]+)$")
        private val TRAKT_PROFILE_KEY = Regex("^(access_token|refresh_token|expires_at)_(u?[0-9a-zA-Z-]+)$")
    }

    fun preferenceStores(): List<SharedPreferences> = PREFERENCES.map { AccountStorage.preferences(context, it) } +
        SECURE.map { (name, alias) -> SecurePreferences.create(context, name, alias).preferences }

    fun capture(): ByteArray {
        val sql = database.openHelper.writableDatabase
        val tables = JsonObject()
        sql.beginTransaction()
        try {
            for (table in TABLES) {
                val columns = SNAPSHOT_COLUMNS.getValue(table)
                val rows = JsonArray()
                val select = columns.joinToString(", ") { "`$it`" }
                val activeRuntimeOwnerFilter = if (table in RUNTIME_CONFIGURATION_TABLES) {
                    " WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), '$LEGACY_PROFILE_OWNER_ID')"
                } else ""
                sql.query("SELECT $select FROM `$table`$activeRuntimeOwnerFilter ORDER BY rowid").use { cursor ->
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
                        if (table == "profiles") normalizeProfileMirrorFields(row)
                        rows.add(mapPaths(row, restoring = false))
                    }
                }
                tables.add(table, rows)
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val prefs = JsonObject()
        val portableByProfileId = portableProfileIds(tables)
        val capturedActiveProfileId = activeProfileId(tables)
        PREFERENCES.forEach { name ->
            val values = AccountStorage.preferences(context, name)
            prefs.add(name, if (name == "profile_configuration_prefs") {
                encodeProfilePreferences(values, portableByProfileId)
            } else encodePreferences(values))
        }
        SECURE.forEach { (name, alias) ->
            prefs.add(name, encodeSecurePreferences(
                name,
                SecurePreferences.create(context, name, alias).preferences,
                portableByProfileId,
                capturedActiveProfileId
            ))
        }
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
        normalizePortableProfileIds(tables)
        val activeProfile = preserveLocalActiveProfile(tables, currentActivePortableProfileId())
        val legacyRuntimeOwner = if (snapshotSchema <= SNAPSHOT_SCHEMA) {
            activeProfile?.portableId ?: legacyActiveProfileOwner(tables)
        } else null
        val preferences = snapshot.getAsJsonObject("preferences")
        val currentPreferenceKeys = (PREFERENCES + SECURE.keys).toSet()
        val legacyPreferenceKeys = currentPreferenceKeys + LEGACY_DEVICE_PREFERENCES
        require(preferences.keySet() == currentPreferenceKeys ||
            (snapshotSchema <= 61 && preferences.keySet() == legacyPreferenceKeys))
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
            val columns = SNAPSHOT_COLUMNS.getValue(table)
            val currentColumns = mutableSetOf<String>()
            sql.query("PRAGMA table_info(`$table`)").use { while (it.moveToNext()) currentColumns.add(it.getString(1)) }
            val expectedLocalColumns = if (table in RUNTIME_CONFIGURATION_TABLES) columns + "profileOwnerId" else columns
            require(currentColumns == expectedLocalColumns.toSet()) {
                "Account snapshot schema is out of date for $table; bump and map the snapshot version."
            }
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
                if (table == "profiles" && (!row.has("portableId") || row.get("portableId").isJsonNull ||
                        row.get("portableId").asString.isBlank())) {
                    // A portable identity is deterministic for legacy profiles copied from the
                    // same v1/v2 snapshot; the new profile wizard uses a random UUID thereafter.
                    row.addProperty("portableId", "legacy-profile-${row.get("id").asInt}")
                }
                if (table == "profiles") normalizeProfileMirrorFields(row)
                if (table == "watchlist" && snapshotSchema <= 53) {
                    if (!row.has("background")) row.add("background", JsonNull.INSTANCE)
                    if (!row.has("logo")) row.add("logo", JsonNull.INSTANCE)
                }
                if (table == "watchlist" && snapshotSchema <= 58) {
                    if (!row.has("sourceAddonBaseUrl")) row.add("sourceAddonBaseUrl", JsonNull.INSTANCE)
                    if (!row.has("sourceAddonKey")) row.addProperty("sourceAddonKey", "")
                }
                if (table == "watchlist") {
                    row.addProperty("canonicalIdentityKey", com.saab.tv.domain.schema.CanonicalEntityKey.title(
                        row.get("id").asString, row.get("type").asString,
                        row.get("sourceAddonKey")?.takeUnless { it.isJsonNull }?.asString
                    ))
                }
                if (table == "watch_history") {
                    row.addProperty("canonicalIdentityKey", com.saab.tv.domain.schema.CanonicalEntityKey.playable(
                        row.get("id").asString, row.get("type").asString
                    ))
                }
                if (table == "series_next_up") {
                    row.addProperty("canonicalIdentityKey", com.saab.tv.domain.schema.CanonicalEntityKey.title(
                        row.get("seriesId").asString, "series"
                    ))
                }
                require(row.keySet() == columns.toSet())
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
                    // Room's per-profile runtime owner is an implementation detail added in
                    // schema 64; keep it out of the schema-63 v2 wire contract.
                    if (table in RUNTIME_CONFIGURATION_TABLES) {
                        row.addProperty("profileOwnerId", legacyRuntimeOwner ?: LEGACY_PROFILE_OWNER_ID)
                    }
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
        val restoredPortableByProfileId = portableProfileIds(tables)
        val portableToLocalProfileId = restoredPortableByProfileId.entries.associate { (id, portable) -> portable to id }
        val prefs = snapshot.getAsJsonObject("preferences")
        PREFERENCES.forEach { name ->
            val destination = AccountStorage.preferences(context, name)
            if (name == "profile_configuration_prefs") {
                decodeProfilePreferences(
                    destination,
                    prefs.getAsJsonObject(name),
                    portableToLocalProfileId,
                    restoredPortableByProfileId
                )
            } else decodePreferences(destination, prefs.getAsJsonObject(name))
        }
        restoreDeviceProfileMarkers(activeProfile, tables)
        SECURE.forEach { (name, alias) ->
            decodeSecurePreferences(
                name,
                SecurePreferences.create(context, name, alias).preferences,
                prefs.getAsJsonObject(name),
                portableToLocalProfileId,
                restoredPortableByProfileId,
                activeProfile?.localId
            )
        }
        // A cloud restore replaces, rather than merges, runtime/profile assets.
        // Do not re-upload files belonging to profiles deleted on another TV.
        for (directory in listOf("avatars", "hub_images", "profile_snapshots")) {
            File(root, directory).listFiles()?.filter { it.isFile && it !in decodedFiles.keys }?.forEach {
                check(it.delete()) { "Unable to remove an obsolete account asset." }
            }
        }
    }

    /** Canonical settings win over retired duplicate columns in every portable snapshot. */
    private fun normalizeProfileMirrorFields(row: JsonObject) {
        if (row.has("seekTimeIntervalSeconds") && row.has("seekThumbnailIntervalSeconds")) {
            row.addProperty("seekThumbnailIntervalSeconds", row.get("seekTimeIntervalSeconds").asInt)
        }
        if (row.has("autoSkipIntro") && row.has("introSkipCountdownSeconds") &&
            row.has("outroSkipCountdownSeconds")) {
            val enabledValue = row.get("autoSkipIntro").asJsonPrimitive
            val enabled = if (enabledValue.isBoolean) enabledValue.asBoolean else enabledValue.asInt != 0
            val countdown = if (!enabled) 0
                else if (row.get("introSkipCountdownSeconds").asInt == 10) 10 else 5
            row.addProperty("outroSkipCountdownSeconds", countdown)
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
            preferenceValue(value)?.let { add(key, it) }
        }
    }

    /** Credential keys are portable in the cloud even though local managers use Room IDs. */
    private fun encodeSecurePreferences(
        storeName: String,
        prefs: SharedPreferences,
        portableByProfileId: Map<Int, String>,
        activeProfileId: Int?
    ): JsonObject = JsonObject().apply {
        val pattern = if (storeName == "stremio_secure_prefs") STREMIO_PROFILE_KEY else TRAKT_PROFILE_KEY
        val values = prefs.all.toSortedMap().toMutableMap()
        if (storeName == "stremio_secure_prefs") {
            values.remove("stremio_active_profile_id") // A device-local selector, never account state.
            val activePortableId = activeProfileId?.let(portableByProfileId::get)
            if (activePortableId != null) {
                val authKey = values["stremio_auth_key"] as? String
                val email = values["stremio_email"] as? String
                if (authKey != null && email != null &&
                    values["stremio_auth_key_profile_$activeProfileId"] == null) {
                    values["stremio_auth_key_profile_u$activePortableId"] = authKey
                    values["stremio_email_profile_u$activePortableId"] = email
                }
            }
        }
        values.forEach { (key, value) ->
            val match = pattern.matchEntire(key)
            val portableKey = if (match == null) key else {
                val suffix = match.groupValues[2]
                val localId = suffix.removePrefix("u").toIntOrNull()
                val portableId = localId?.let(portableByProfileId::get)
                    ?: suffix.takeIf { it.startsWith("u") }?.removePrefix("u")
                    ?: return@forEach
                if (storeName == "stremio_secure_prefs")
                    "${match.groupValues[1]}_profile_u$portableId"
                else "${match.groupValues[1]}_u$portableId"
            }
            preferenceValue(value)?.let { add(portableKey, it) }
        }
    }

    private fun activeProfileId(tables: JsonObject): Int? =
        tables.getAsJsonArray("profiles").firstOrNull { value ->
            val active = value.asJsonObject.get("isActive") ?: return@firstOrNull false
            active.isJsonPrimitive && if (active.asJsonPrimitive.isBoolean) active.asBoolean else active.asInt != 0
        }?.asJsonObject?.get("id")?.asInt

    private fun decodeSecurePreferences(
        storeName: String,
        prefs: SharedPreferences,
        values: JsonObject?,
        localIdByPortableId: Map<String, Int>,
        portableBySnapshotProfileId: Map<Int, String>,
        activeProfileId: Int?
    ) {
        val pattern = if (storeName == "stremio_secure_prefs") STREMIO_PROFILE_KEY else TRAKT_PROFILE_KEY
        val localized = JsonObject()
        values?.entrySet()?.forEach { (key, value) ->
            if (storeName == "stremio_secure_prefs" && key == "stremio_active_profile_id") return@forEach
            val match = pattern.matchEntire(key)
            val localKey = if (match == null) key else {
                val suffix = match.groupValues[2]
                val portableId = if (suffix.startsWith('u')) suffix.removePrefix("u") else {
                    suffix.toIntOrNull()?.let(portableBySnapshotProfileId::get) ?: return@forEach
                }
                val localId = localIdByPortableId[portableId] ?: return@forEach
                if (storeName == "stremio_secure_prefs")
                    "${match.groupValues[1]}_profile_$localId"
                else "${match.groupValues[1]}_$localId"
            }
            localized.add(localKey, value.deepCopy())
        }
        decodePreferences(prefs, localized)
        if (storeName == "stremio_secure_prefs") {
            val editor = prefs.edit()
            if (activeProfileId != null) {
                editor.putInt("stremio_active_profile_id", activeProfileId)
                val profileAuth = prefs.getString("stremio_auth_key_profile_$activeProfileId", null)
                val profileEmail = prefs.getString("stremio_email_profile_$activeProfileId", null)
                if (profileAuth != null && profileEmail != null) {
                    editor.putString("stremio_auth_key", profileAuth).putString("stremio_email", profileEmail)
                } else if (hasProfileScopedStremioCredentials(prefs)) {
                    editor.remove("stremio_auth_key").remove("stremio_email")
                } else {
                    // Old snapshots contained only the active unscoped credential pair.
                    val auth = prefs.getString("stremio_auth_key", null)
                    val email = prefs.getString("stremio_email", null)
                    if (auth != null && email != null) {
                        editor.putString("stremio_auth_key_profile_$activeProfileId", auth)
                            .putString("stremio_email_profile_$activeProfileId", email)
                    }
                }
            } else editor.remove("stremio_active_profile_id")
            check(editor.commit())
        }
    }

    private fun hasProfileScopedStremioCredentials(prefs: SharedPreferences): Boolean =
        prefs.all.keys.any { STREMIO_PROFILE_KEY.matches(it) }

    private data class PortableProfile(val localId: Int, val portableId: String)

    private fun currentActivePortableProfileId(): String? {
        val prefs = AccountStorage.preferences(context, "profile_configuration_prefs")
        val selectedId = prefs.getInt(LAST_ACTIVE_PROFILE_ID, -1)
        if (selectedId >= 0) {
            val cached = prefs.getString("$PORTABLE_PROFILE_ID_PREFIX$selectedId", null)
                ?.takeIf(String::isNotBlank)
            val sql = database.openHelper.readableDatabase
            val selected = sql.query("SELECT portableId FROM profiles WHERE id = ?", arrayOf(selectedId.toString())).use {
                if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
            }
            if (selected != null) return selected
            if (cached != null && sql.query("SELECT 1 FROM profiles WHERE portableId = ? LIMIT 1", arrayOf(cached)).use { it.moveToFirst() }) {
                return cached
            }
        }
        return database.openHelper.readableDatabase.query(
            "SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null }
    }

    private fun normalizePortableProfileIds(tables: JsonObject) {
        tables.getAsJsonArray("profiles").forEach { value ->
            val row = value.asJsonObject
            if (row.get("portableId")?.takeUnless { it.isJsonNull }?.asString.isNullOrBlank()) {
                row.addProperty("portableId", "legacy-profile-${row.get("id").asInt}")
            }
        }
    }

    /** Keep active profile selection local when a cloud copy replaces Room's integer IDs. */
    private fun preserveLocalActiveProfile(tables: JsonObject, preferredPortableId: String?): PortableProfile? {
        val profiles = tables.getAsJsonArray("profiles").map { it.asJsonObject }
        if (profiles.isEmpty()) return null
        val selected = profiles.firstOrNull { it.get("portableId")?.asString == preferredPortableId }
            ?: profiles.firstOrNull { row ->
                val active = row.get("isActive") ?: return@firstOrNull false
                active.isJsonPrimitive && if (active.asJsonPrimitive.isBoolean) active.asBoolean else active.asInt != 0
            }
            ?: return null
        val selectedId = selected.get("id").asInt
        profiles.forEach { row -> row.addProperty("isActive", if (row.get("id").asInt == selectedId) 1 else 0) }
        return PortableProfile(selectedId, selected.get("portableId").asString)
    }

    private fun restoreDeviceProfileMarkers(activeProfile: PortableProfile?, tables: JsonObject) {
        val prefs = AccountStorage.preferences(context, "profile_configuration_prefs")
        val editor = prefs.edit()
        prefs.all.keys.filter {
            it.startsWith(PORTABLE_PROFILE_ID_PREFIX) || it.startsWith("splash_enabled_")
        }.forEach(editor::remove)
        if (activeProfile == null) editor.remove(LAST_ACTIVE_PROFILE_ID)
        else editor.putInt(LAST_ACTIVE_PROFILE_ID, activeProfile.localId)
        tables.getAsJsonArray("profiles").forEach { value ->
            val row = value.asJsonObject
            val id = row.get("id").asInt
            val portableId = row.get("portableId").asString
            editor.putString("$PORTABLE_PROFILE_ID_PREFIX$id", portableId)
            val splash = row.get("splashEnabled")?.let {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else it.asInt != 0
            } ?: true
            editor.putBoolean("splash_enabled_$id", splash)
        }
        check(editor.commit())
    }

    /** Only portable, profile-owned values cross devices; local integer-ID bookkeeping stays put. */
    private fun encodeProfilePreferences(
        prefs: SharedPreferences,
        portableByProfileId: Map<Int, String>
    ): JsonObject = JsonObject().apply {
        prefs.all.toSortedMap().forEach { (key, value) ->
            val match = PROFILE_PREFERENCE_KEY.matchEntire(key) ?: return@forEach
            val localId = match.groupValues[2].toIntOrNull() ?: return@forEach
            val portableId = portableByProfileId[localId]?.takeIf(String::isNotBlank) ?: return@forEach
            val item = preferenceValue(value) ?: return@forEach
            add("${match.groupValues[1]}_u$portableId", item)
        }
    }

    /** Restore portable profile values onto this device's local Room profile IDs. */
    private fun decodeProfilePreferences(
        prefs: SharedPreferences,
        values: JsonObject,
        localIdByPortableId: Map<String, Int>,
        portableBySnapshotProfileId: Map<Int, String>
    ) {
        val editor = prefs.edit()
        prefs.all.keys.filter { LOCAL_PROFILE_PREFERENCE_KEY.matches(it) }.forEach(editor::remove)
        values.entrySet().forEach { (key, item) ->
            val match = PROFILE_PREFERENCE_KEY.matchEntire(key) ?: return@forEach
            val suffix = match.groupValues[2]
            val localId = if (suffix.startsWith('u')) {
                localIdByPortableId[suffix.removePrefix("u")]
            } else {
                suffix.toIntOrNull()?.let(portableBySnapshotProfileId::get)?.let(localIdByPortableId::get)
            } ?: return@forEach
            putPreference(editor, "${match.groupValues[1]}_$localId", item.asJsonObject)
        }
        check(editor.commit())
    }

    private fun portableProfileIds(tables: JsonObject): Map<Int, String> =
        tables.getAsJsonArray("profiles").mapNotNull { value ->
            val row = value.asJsonObject
            val id = row.get("id")?.asInt ?: return@mapNotNull null
            val portableId = row.get("portableId")?.takeUnless { it.isJsonNull }?.asString
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            id to portableId
        }.toMap()

    private fun legacyActiveProfileOwner(tables: JsonObject): String? {
        val profiles = tables.getAsJsonArray("profiles") ?: return null
        val selected = profiles.firstOrNull { value ->
            val row = value.asJsonObject
            val active = row.get("isActive") ?: return@firstOrNull false
            when {
                active.isJsonPrimitive && active.asJsonPrimitive.isBoolean -> active.asBoolean
                active.isJsonPrimitive && active.asJsonPrimitive.isNumber -> active.asInt != 0
                else -> false
            }
        }?.asJsonObject ?: profiles.firstOrNull()?.asJsonObject ?: return null
        selected.get("portableId")?.takeUnless { it.isJsonNull }?.asString
            ?.takeIf(String::isNotBlank)?.let { return it }
        return selected.get("id")?.takeUnless { it.isJsonNull }?.asInt?.let { "legacy-profile-$it" }
    }

    private fun preferenceValue(value: Any?): JsonObject? = JsonObject().apply {
        when (value) {
            is String -> { addProperty("type", "string"); addProperty("value", value) }
            is Boolean -> { addProperty("type", "boolean"); addProperty("value", value) }
            is Int -> { addProperty("type", "int"); addProperty("value", value) }
            is Long -> { addProperty("type", "long"); addProperty("value", value) }
            is Float -> { addProperty("type", "float"); addProperty("value", value) }
            is Set<*> -> { addProperty("type", "set"); add("value", Gson().toJsonTree(value.filterIsInstance<String>().sorted())) }
            else -> return null
        }
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, item: JsonObject) {
        val value = item.get("value")
        when (item.get("type").asString) {
            "string" -> editor.putString(key, value.asString)
            "boolean" -> editor.putBoolean(key, value.asBoolean)
            "int" -> editor.putInt(key, value.asInt)
            "long" -> editor.putLong(key, value.asLong)
            "float" -> editor.putFloat(key, value.asFloat)
            "set" -> editor.putStringSet(key, value.asJsonArray.map { it.asString }.toSet())
            else -> throw IOException("Invalid account preference type.")
        }
    }

    private fun decodePreferences(prefs: SharedPreferences, objectValue: JsonObject?) {
        val editor = prefs.edit().clear()
        objectValue?.entrySet()?.forEach { (key, item) ->
            putPreference(editor, key, item.asJsonObject)
        }
        check(editor.commit())
    }
}
