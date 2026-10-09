package com.saab.tv.data.account

import android.app.Application
import android.util.Base64
import androidx.room.Room
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.security.SecurePreferences
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountSnapshotRegressionTest {
    private lateinit var db: SaabTvDatabase
    private lateinit var store: AccountSnapshotStore
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setUp() {
        AccountStorage.setUserId(context, "snapshot-test-account")
        db = Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java).allowMainThreadQueries().build()
        store = AccountSnapshotStore(context, db)
    }
    @After fun tearDown() { db.close() }
    private fun snapshot(): JsonObject = JsonParser.parseString(store.capture().toString(Charsets.UTF_8)).asJsonObject
    private fun restore(json: JsonObject) = store.restore(json.toString().toByteArray())
    private fun rejects(json: JsonObject) {
        try { restore(json); fail("Invalid snapshot was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun profileAndProgressRoundTripPreservesZeroAndLongPrecision() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Original", isActive = true, sourceLanguagePriority1 = "ml")
        val history = WatchHistoryEntity(id = "tt1:2:3", title = "Episode", poster = null, position = 0,
            duration = 1_000_000, lastWatched = 9_007_199_254_740_993L, type = "series", watched = false)
        db.addonDao().insertProfile(profile); db.addonDao().insertHistory(history)
        val bytes = store.capture()
        assertEquals(54, JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject["schema"].asInt)
        db.addonDao().updateProfile(profile.copy(name = "Changed")); db.addonDao().clearWatchHistory()
        store.restore(bytes)
        assertEquals(profile, db.addonDao().getProfileById(1))
        assertEquals(history, db.addonDao().getHistoryItem(history.id))
    }

    @Test fun legacySchema51SnapshotRestoresThePreviousSkipRecapDefault() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Legacy", skipRecap = false)
        db.addonDao().insertProfile(profile)
        val legacySnapshot = snapshot().apply {
            addProperty("schema", 51)
            getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.remove("skipRecap")
        }
        db.addonDao().updateProfile(profile.copy(name = "Changed"))

        restore(legacySnapshot)

        val restored = db.addonDao().getProfileById(1)!!
        assertEquals("Legacy", restored.name)
        assertTrue(restored.skipRecap)
    }

    @Test fun legacySchema52CopiesTheSavedContinueWatchingShapeIntoGlobalTitleCards() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Landscape profile", continueWatchingShape = "landscape")
        db.addonDao().insertProfile(profile)
        val legacySnapshot = snapshot().apply {
            addProperty("schema", 52)
            getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.remove("titleCardShape")
        }
        db.addonDao().updateProfile(profile.copy(name = "Changed"))

        restore(legacySnapshot)

        assertEquals("landscape", db.addonDao().getProfileById(1)?.titleCardShape)
    }

    @Test fun legacySchema53RestoresWatchlistRowsWithoutLandscapeArtwork() = runBlocking {
        db.addonDao().insertProfile(ProfileEntity(id = 1, name = "Legacy"))
        db.addonDao().addToWatchlist(WatchlistEntity(1, "tt-legacy", "movie", "Legacy title", "poster", 1))
        val legacySnapshot = snapshot().apply {
            addProperty("schema", 53)
            getAsJsonObject("tables").getAsJsonArray("watchlist")[0].asJsonObject.apply {
                remove("background")
                remove("logo")
            }
        }
        db.addonDao().removeFromWatchlist(1, "tt-legacy")

        restore(legacySnapshot)

        val restored = db.addonDao().getWatchlistItem(1, "tt-legacy")!!
        assertNull(restored.background)
        assertNull(restored.logo)
    }

    @Test fun portableSnapshotContainsLibraryHistoryIntegrationsAndAllProfileSettings() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Portable", isActive = true, titleCardShape = "landscape",
            sourceLanguagePriority1 = "ml", tmdbEnabled = true)
        val watchlist = WatchlistEntity(1, "tt-watchlisted", "movie", "Saved title", "poster.jpg", 20,
            background = "backdrop.jpg", logo = "logo.png")
        val history = WatchHistoryEntity(id = "tt-watched", title = "Watched title", poster = null,
            position = 950, duration = 1_000, lastWatched = 100, type = "movie", watched = true)
        db.addonDao().insertProfile(profile)
        db.addonDao().addToWatchlist(watchlist)
        db.addonDao().insertHistory(history)
        AccountStorage.preferences(context, "profile_configuration_prefs").edit().putString("onboarding", "complete").commit()
        AccountStorage.preferences(context, "source_selection_prefs").edit().putString("preferred", "torrentio").commit()
        val portable = snapshot()
        val tables = portable.getAsJsonObject("tables")
        assertEquals(1, tables.getAsJsonArray("watchlist").size())
        assertEquals("backdrop.jpg", tables.getAsJsonArray("watchlist")[0].asJsonObject["background"].asString)
        assertEquals("logo.png", tables.getAsJsonArray("watchlist")[0].asJsonObject["logo"].asString)
        assertEquals(1, tables.getAsJsonArray("watch_history").size())
        assertTrue(tables.getAsJsonArray("profiles")[0].asJsonObject["titleCardShape"].asString == "landscape")
        val prefs = portable.getAsJsonObject("preferences")
        assertTrue(prefs.has("stremio_secure_prefs"))
        assertTrue(prefs.has("trakt_auth"))
        assertTrue(prefs.has("tmdb_credentials"))
        assertTrue(prefs.has("profile_configuration_prefs"))
        assertTrue(prefs.has("source_selection_prefs"))
    }

    @Test fun integrationCredentialsAreRehydratedIntoTheCurrentDeviceSecureStores() {
        val stremio = SecurePreferences.create(context, "stremio_secure_prefs", "saabtv_stremio_master_key").preferences
        val trakt = SecurePreferences.create(context, "trakt_auth", "saabtv_trakt_master_key").preferences
        val tmdb = SecurePreferences.create(context, "tmdb_credentials", "saabtv_tmdb_master_key").preferences
        stremio.edit().putString("auth_key", "stremio-secret").commit()
        trakt.edit().putString("access_token", "trakt-secret").commit()
        tmdb.edit().putString("api_key", "tmdb-secret").commit()
        val portable = store.capture()
        stremio.edit().clear().commit(); trakt.edit().clear().commit(); tmdb.edit().clear().commit()

        store.restore(portable)

        assertEquals("stremio-secret", stremio.getString("auth_key", null))
        assertEquals("trakt-secret", trakt.getString("access_token", null))
        assertEquals("tmdb-secret", tmdb.getString("api_key", null))
    }

    @Test fun everyPortablePreferenceTypeRoundTripsAndStaleValuesAreRemoved() {
        val prefs = AccountStorage.preferences(context, "source_selection_prefs")
        prefs.edit().putString("string", "source").putInt("int", 30).putLong("long", 9_007_199_254_740_993L)
            .putBoolean("boolean", true).putFloat("float", .5f).putStringSet("set", setOf("te", "en")).commit()
        val bytes = store.capture()
        prefs.edit().clear().putString("stale", "removed").commit()
        store.restore(bytes)
        assertEquals("source", prefs.getString("string", null)); assertEquals(30, prefs.getInt("int", 0))
        assertEquals(9_007_199_254_740_993L, prefs.getLong("long", 0)); assertTrue(prefs.getBoolean("boolean", false))
        assertEquals(.5f, prefs.getFloat("float", 0f)); assertEquals(setOf("te", "en"), prefs.getStringSet("set", null))
        assertFalse(prefs.contains("stale")); assertEquals(7, store.preferenceStores().size)
    }

    @Test fun wrongAccountSchemaAndFormatFailBeforeChangingDatabase() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Safe")
        db.addonDao().insertProfile(profile)
        val original = snapshot()
        rejects(original.deepCopy().apply { addProperty("userId", "another-account") })
        rejects(original.deepCopy().apply { addProperty("schema", 999) })
        rejects(original.deepCopy().apply { addProperty("format", 999) })
        assertEquals(profile, db.addonDao().getProfileById(1))
    }

    @Test fun missingTablesOrPreferenceStoresAreRejected() {
        rejects(snapshot().apply { getAsJsonObject("tables").remove("profiles") })
        rejects(snapshot().apply { getAsJsonObject("preferences").remove("source_selection_prefs") })
    }

    @Test fun unexpectedDatabaseColumnIsRejected() = runBlocking {
        db.addonDao().insertProfile(ProfileEntity(id = 1, name = "One"))
        rejects(snapshot().apply { getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.addProperty("unexpected", "value") })
        assertEquals("One", db.addonDao().getProfileById(1)!!.name)
    }

    @Test fun unknownPreferenceTypeIsRejectedBeforePreferencesAreCleared() {
        val prefs = AccountStorage.preferences(context, "source_selection_prefs")
        prefs.edit().putString("keep", "safe").commit()
        val json = snapshot()
        json.getAsJsonObject("preferences").getAsJsonObject("source_selection_prefs")
            .getAsJsonObject("keep").addProperty("type", "unsupported")
        try { restore(json); fail() } catch (_: java.io.IOException) { }
        assertEquals("safe", prefs.getString("keep", null))
    }

    @Test fun traversalHiddenAndUnapprovedAssetPathsAreRejected() {
        listOf("avatars/../outside.png", "avatars/.hidden", "/absolute.png", "thumbnails/frame.png", "avatars/sub/path.png")
            .forEach { path -> rejects(snapshot().apply {
                getAsJsonObject("files").addProperty(path, Base64.encodeToString(byteArrayOf(1), Base64.NO_WRAP))
            }) }
    }

    @Test fun avatarPathsBecomePortableAndRestoreToCurrentAccount() = runBlocking {
        val avatar = File(AccountStorage.files(context), "avatars/avatar.png")
        avatar.parentFile!!.mkdirs(); avatar.writeBytes(byteArrayOf(1, 2, 3))
        val profile = ProfileEntity(id = 1, name = "One", avatarRef = "custom:${avatar.absolutePath}")
        db.addonDao().insertProfile(profile)
        val json = snapshot()
        assertEquals("custom:account-file:avatars/avatar.png", json.getAsJsonObject("tables")
            .getAsJsonArray("profiles")[0].asJsonObject["avatarRef"].asString)
        avatar.writeBytes(byteArrayOf(9)); restore(json)
        assertArrayEquals(byteArrayOf(1, 2, 3), avatar.readBytes())
        assertEquals(profile, db.addonDao().getProfileById(1))
    }

    @Test fun cacheMediaAndTemporaryBackupFilesAreNotUploaded() {
        val root = AccountStorage.files(context)
        File(root, "avatars").mkdirs(); File(root, "thumbnails").mkdirs()
        File(root, "avatars/temporary.png.bak").writeBytes(byteArrayOf(1))
        File(root, "avatars/temporary.png.new").writeBytes(byteArrayOf(1))
        File(root, "thumbnails/frame.png").writeBytes(byteArrayOf(1))
        assertTrue(snapshot().getAsJsonObject("files").entrySet().isEmpty())
    }

    @Test fun oversizedRestoreIsRejectedBeforeParsing() {
        try { store.restore(ByteArray(AccountSnapshotStore.MAX_BYTES + 1)); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
