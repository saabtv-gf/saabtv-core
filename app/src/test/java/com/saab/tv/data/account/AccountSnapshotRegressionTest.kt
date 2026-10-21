package com.saab.tv.data.account

import android.app.Application
import android.util.Base64
import androidx.room.Room
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.HubRowEntity
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.domain.schema.CanonicalEntityKey
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountSnapshotRegressionTest {
    private lateinit var db: SaabTvDatabase
    private lateinit var store: AccountSnapshotStore
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setUp() {
        AccountStorage.setUserId(context, UUID.randomUUID().toString())
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
        assertEquals(64, JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject["schema"].asInt)
        db.addonDao().updateProfile(profile.copy(name = "Changed")); db.addonDao().clearWatchHistory()
        store.restore(bytes)
        assertEquals(profile, db.addonDao().getProfileById(1))
        assertEquals(history, db.addonDao().getHistoryItem(history.id))
        assertEquals(CanonicalEntityKey.playable(history.id, history.type),
            db.addonDao().getHistoryItem(history.id)?.canonicalIdentityKey)
    }

    @Test fun schema62And63RuntimeRowsAcquireTheActivePortableProfileOwnerOnRestore() = runBlocking {
        val profile = ProfileEntity(id = 1, name = "Portable", portableId = "portable-profile", isActive = true)
        db.addonDao().insertProfile(profile)
        db.addonDao().insertAddon(AddonEntity("https://legacy.invalid", "legacy", "Legacy", "1", null, null))
        db.addonDao().saveCatalogConfig(CatalogConfigEntity(
            "legacy/movie/top", "https://legacy.invalid", "Legacy", "movie", "top"
        ))
        db.addonDao().insertHubRowWithItems(
            HubRowEntity("legacy-hub", "Legacy Hub", "poster"),
            listOf(HubRowItemEntity("legacy-hub", "legacy/movie/top", "Legacy Item"))
        )
        val captured = snapshot()
        for (schema in 62..63) {
            val incoming = captured.deepCopy().apply {
                addProperty("schema", schema)
                getAsJsonObject("tables").let { tables ->
                    listOf("addons", "catalog_configs", "hub_rows", "hub_row_items").forEach { table ->
                        tables.getAsJsonArray(table).forEach { it.asJsonObject.remove("profileOwnerId") }
                    }
                }
            }
            db.addonDao().deleteRuntimeState("portable-profile")

            restore(incoming)

            assertEquals("portable-profile", db.addonDao().getAddonsForProfile("portable-profile").single().profileOwnerId)
            assertEquals("legacy", db.addonDao().getAddonsForProfile("portable-profile").single().id)
            assertEquals("portable-profile", db.addonDao().getCatalogConfigsForProfile("portable-profile").single().profileOwnerId)
            assertEquals("portable-profile", db.addonDao().getHubRowsForProfile("portable-profile").single().profileOwnerId)
            assertEquals("portable-profile", db.addonDao().getHubRowItemsForProfile("portable-profile").single().profileOwnerId)
        }
    }

    @Test fun legacySnapshotProjectsOnlyActiveRuntimeRowsWithoutChangingTheV2WireContract() = runBlocking {
        db.addonDao().insertProfile(ProfileEntity(id = 1, name = "One", portableId = "profile-one", isActive = true))
        db.addonDao().insertProfile(ProfileEntity(id = 2, name = "Two", portableId = "profile-two"))
        db.addonDao().insertAddon(AddonEntity("https://one.invalid", "one", "One", "1", null, null))
        db.addonDao().activateProfile(2)
        db.addonDao().insertAddon(AddonEntity("https://two.invalid", "two", "Two", "1", null, null))

        val portable = snapshot()
        val rows = portable.getAsJsonObject("tables").getAsJsonArray("addons")
        assertEquals(64, portable["schema"].asInt)
        assertEquals(1, rows.size())
        assertEquals("two", rows[0].asJsonObject["id"].asString)
        assertFalse(rows[0].asJsonObject.has("profileOwnerId"))
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
                remove("canonicalIdentityKey")
            }
        }
        db.addonDao().removeFromWatchlist(1, "tt-legacy")

        restore(legacySnapshot)

        val restored = db.addonDao().getWatchlistItem(1, "tt-legacy")!!
        assertNull(restored.background)
        assertNull(restored.logo)
        assertEquals(CanonicalEntityKey.title(restored.id, restored.type, restored.sourceAddonKey), restored.canonicalIdentityKey)
    }

    @Test fun legacySnapshotRebuildsCanonicalHistoryIdentityFromTheExactPlaybackId() = runBlocking {
        val history = WatchHistoryEntity(
            id = "tt7654321:2:5", title = "Legacy episode", poster = null,
            position = 200, duration = 900, lastWatched = 10, type = "series"
        )
        db.addonDao().insertProfile(ProfileEntity(id = 1, name = "Legacy"))
        db.addonDao().insertHistory(history)
        val legacy = snapshot().apply {
            addProperty("schema", 59)
            getAsJsonObject("tables").getAsJsonArray("watch_history")[0].asJsonObject.remove("canonicalIdentityKey")
        }
        db.addonDao().clearWatchHistory()

        restore(legacy)

        assertEquals(history.id, db.addonDao().getHistoryItem(history.id)?.id)
        assertEquals(CanonicalEntityKey.playable(history.id, history.type),
            db.addonDao().getHistoryItem(history.id)?.canonicalIdentityKey)
    }

    @Test fun everySupportedLegacySnapshotVersion51Through60RestoresAndRepairsAddedFields() = runBlocking {
        val profile = ProfileEntity(
            id = 1, name = "Legacy profile", continueWatchingShape = "landscape", titleCardShape = "landscape",
            skipRecap = true
        )
        val watchlist = WatchlistEntity(
            profileId = 1, id = "addon-title", type = "series", title = "Legacy show", poster = "poster",
            addedAt = 20, background = "backdrop", logo = "logo",
            sourceAddonBaseUrl = "https://addon.example/catalog", sourceAddonKey = "catalog-instance"
        )
        val history = WatchHistoryEntity(
            profileId = 1, id = "tt7654321:2:5", title = "Legacy episode", poster = null,
            position = 200, duration = 900, lastWatched = 30, type = "series"
        )
        val nextUp = SeriesNextUpEntity(
            profileId = 1, seriesId = "tt7654321", title = "Legacy show", poster = null,
            nextSeason = 2, nextEpisode = 6, nextEpisodeTitle = "Next", updatedAt = 31
        )
        db.addonDao().insertProfile(profile)
        db.addonDao().addToWatchlist(watchlist)
        db.addonDao().insertHistory(history)
        db.addonDao().insertSeriesNextUp(nextUp)
        val currentSnapshot = snapshot()

        for (version in 51..60) {
            val legacy = currentSnapshot.deepCopy().apply {
                addProperty("schema", version)
                getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.remove("portableId")
                getAsJsonObject("tables").getAsJsonArray("watch_history")[0].asJsonObject.remove("canonicalIdentityKey")
                getAsJsonObject("tables").getAsJsonArray("series_next_up")[0].asJsonObject.remove("canonicalIdentityKey")
                getAsJsonObject("tables").getAsJsonArray("watchlist")[0].asJsonObject.apply {
                    if (version <= 53) {
                        remove("background")
                        remove("logo")
                    }
                    if (version <= 58) {
                        remove("sourceAddonBaseUrl")
                        remove("sourceAddonKey")
                    }
                    remove("canonicalIdentityKey")
                }
                getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.apply {
                    if (version <= 51) remove("skipRecap")
                    if (version <= 52) remove("titleCardShape")
                }
            }

            restore(legacy)

            val restoredProfile = db.addonDao().getProfileById(1)!!
            assertEquals("legacy-profile-1", restoredProfile.portableId)
            assertEquals("landscape", restoredProfile.titleCardShape)
            assertTrue(restoredProfile.skipRecap)
            val restoredLibrary = db.addonDao().getWatchlistItem(1, watchlist.id)!!
            assertEquals(if (version <= 53) null else "backdrop", restoredLibrary.background)
            assertEquals(if (version <= 53) null else "logo", restoredLibrary.logo)
            assertEquals(if (version <= 58) "" else "catalog-instance", restoredLibrary.sourceAddonKey)
            assertEquals(CanonicalEntityKey.title(watchlist.id, watchlist.type, restoredLibrary.sourceAddonKey),
                restoredLibrary.canonicalIdentityKey)
            assertEquals(CanonicalEntityKey.playable(history.id, history.type),
                db.addonDao().getHistoryItem(history.id)?.canonicalIdentityKey)
            assertEquals(CanonicalEntityKey.title(nextUp.seriesId, "series"),
                db.addonDao().getSeriesNextUpForProfile(1, nextUp.seriesId)?.canonicalIdentityKey)
        }
    }

    @Test fun snapshotCaptureAndRestoreNormalizeRetiredDuplicateProfileSettings() = runBlocking {
        val profile = ProfileEntity(
            id = 1,
            name = "Canonical settings",
            seekTimeIntervalSeconds = 20,
            seekThumbnailIntervalSeconds = 20,
            autoSkipIntro = true,
            introSkipCountdownSeconds = 10,
            outroSkipCountdownSeconds = 10
        )
        db.addonDao().insertProfile(profile)
        val mismatchedSnapshot = snapshot().apply {
            getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.apply {
                addProperty("seekThumbnailIntervalSeconds", 30)
                addProperty("outroSkipCountdownSeconds", 5)
            }
        }

        restore(mismatchedSnapshot)

        val restored = db.addonDao().getProfileById(1)!!
        assertEquals(20, restored.seekTimeIntervalSeconds)
        assertEquals(20, restored.seekThumbnailIntervalSeconds)
        assertEquals(10, restored.introSkipCountdownSeconds)
        assertEquals(10, restored.outroSkipCountdownSeconds)
        val capturedProfile = snapshot().getAsJsonObject("tables")
            .getAsJsonArray("profiles")[0].asJsonObject
        assertEquals(20, capturedProfile["seekThumbnailIntervalSeconds"].asInt)
        assertEquals(10, capturedProfile["outroSkipCountdownSeconds"].asInt)
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
        tmdb.edit().putString("api_key", "tmdb-secret").putString("torbox_api_key", "torbox-secret").commit()
        val portable = store.capture()
        stremio.edit().clear().commit(); trakt.edit().clear().commit(); tmdb.edit().clear().commit()

        store.restore(portable)

        assertEquals("stremio-secret", stremio.getString("auth_key", null))
        assertEquals("trakt-secret", trakt.getString("access_token", null))
        assertEquals("tmdb-secret", tmdb.getString("api_key", null))
        assertEquals("torbox-secret", tmdb.getString("torbox_api_key", null))
    }

    @Test fun portableIntegrationCredentialsAndActiveProfileSurviveRoomIdRemapping() = runBlocking {
        val profileA = ProfileEntity(id = 1, portableId = "portable-a", name = "A")
        val profileB = ProfileEntity(id = 2, portableId = "portable-b", name = "B", isActive = true)
        db.addonDao().insertProfile(profileA)
        db.addonDao().insertProfile(profileB)
        db.addonDao().activateProfile(2)
        val accountPrefs = AccountStorage.preferences(context, "profile_configuration_prefs")
        accountPrefs.edit().putInt("last_active_profile_id", 2)
            .putString("portable_profile_id_2", profileB.portableId).commit()

        val stremio = SecurePreferences.create(context, "stremio_secure_prefs", "saabtv_stremio_master_key").preferences
        val trakt = SecurePreferences.create(context, "trakt_auth", "saabtv_trakt_master_key").preferences
        stremio.edit().clear()
            .putString("stremio_auth_key_profile_1", "stremio-a")
            .putString("stremio_email_profile_1", "a@example.test")
            .putString("stremio_auth_key_profile_2", "stremio-b")
            .putString("stremio_email_profile_2", "b@example.test")
            .putString("stremio_auth_key", "stremio-b")
            .putString("stremio_email", "b@example.test")
            .putInt("stremio_active_profile_id", 2).commit()
        trakt.edit().clear()
            .putString("access_token_1", "trakt-a")
            .putString("access_token_2", "trakt-b")
            .putString("refresh_token_2", "refresh-b")
            .putLong("expires_at_2", 1234L).commit()

        val remote = snapshot()
        val wirePreferences = remote.getAsJsonObject("preferences")
        val stremioWire = wirePreferences.getAsJsonObject("stremio_secure_prefs")
        val traktWire = wirePreferences.getAsJsonObject("trakt_auth")
        assertTrue(stremioWire.has("stremio_auth_key_profile_uportable-a"))
        assertTrue(stremioWire.has("stremio_auth_key_profile_uportable-b"))
        assertTrue(traktWire.has("access_token_uportable-a"))
        assertTrue(traktWire.has("refresh_token_uportable-b"))
        assertFalse("The device-local active ID is not cloud state", stremioWire.has("stremio_active_profile_id"))

        // Another TV may have materialized these same portable profiles under different Room IDs,
        // and its active profile must win over the sender's isActive marker.
        val remoteProfiles = remote.getAsJsonObject("tables").getAsJsonArray("profiles")
        remoteProfiles[0].asJsonObject.addProperty("id", 101)
        remoteProfiles[0].asJsonObject.addProperty("isActive", 1)
        remoteProfiles[1].asJsonObject.addProperty("id", 202)
        remoteProfiles[1].asJsonObject.addProperty("isActive", 0)
        stremio.edit().clear().commit()
        trakt.edit().clear().commit()
        store.restore(remote.toString().toByteArray())

        assertFalse(db.addonDao().getProfileById(101)!!.isActive)
        assertTrue(db.addonDao().getProfileById(202)!!.isActive)
        assertEquals(202, accountPrefs.getInt("last_active_profile_id", -1))
        assertEquals("portable-b", accountPrefs.getString("portable_profile_id_202", null))
        assertFalse(accountPrefs.contains("portable_profile_id_2"))
        assertEquals(202, stremio.getInt("stremio_active_profile_id", -1))
        assertEquals("stremio-b", stremio.getString("stremio_auth_key", null))
        assertEquals("stremio-a", stremio.getString("stremio_auth_key_profile_101", null))
        assertEquals("stremio-b", stremio.getString("stremio_auth_key_profile_202", null))
        assertEquals("trakt-a", trakt.getString("access_token_101", null))
        assertEquals("trakt-b", trakt.getString("access_token_202", null))
        assertEquals("refresh-b", trakt.getString("refresh_token_202", null))
        assertEquals(1234L, trakt.getLong("expires_at_202", 0L))
    }

    @Test fun legacyUnscopedStremioCredentialsAreBoundToTheCapturedActivePortableProfile() = runBlocking {
        db.addonDao().insertProfile(ProfileEntity(id = 1, portableId = "first-profile", name = "First"))
        db.addonDao().insertProfile(ProfileEntity(id = 2, portableId = "active-profile", name = "Active"))
        db.addonDao().activateProfile(2)
        val secure = SecurePreferences.create(context, "stremio_secure_prefs", "saabtv_stremio_master_key").preferences
        secure.edit().clear()
            .putString("stremio_auth_key", "legacy-auth")
            .putString("stremio_email", "active@example.test")
            .putInt("stremio_active_profile_id", 1)
            .commit()

        val wire = snapshot()
        val secureWire = wire.getAsJsonObject("preferences").getAsJsonObject("stremio_secure_prefs")

        assertEquals(64, wire["schema"].asInt)
        assertTrue(secureWire.has("stremio_auth_key_profile_uactive-profile"))
        assertTrue(secureWire.has("stremio_email_profile_uactive-profile"))
        assertFalse(secureWire.has("stremio_auth_key_profile_ufirst-profile"))
        assertFalse("Active integer IDs must never be sent as account state", secureWire.has("stremio_active_profile_id"))
    }

    @Test fun selectedAudioAndSubtitlePreferencesSyncButFourKCapabilityStaysDeviceLocal() {
        val trackPreferences = AccountStorage.preferences(context, "playback_track_selection_prefs")
        val devicePreferences = AccountStorage.preferences(context, "device_display_settings")
        trackPreferences.edit()
            .putString("audio_movie-1", "audio-track-id")
            .putString("subtitle_movie-1", "subtitle-track-id")
            .putBoolean("subtitleExplicit_movie-1", true)
            .putLong("subtitleDelay_movie-1", 125L)
            .commit()
        devicePreferences.edit().putBoolean("is_4k", false).commit()

        val portable = store.capture()
        trackPreferences.edit().clear().commit()
        devicePreferences.edit().putBoolean("is_4k", true).commit()

        store.restore(portable)

        assertEquals("audio-track-id", trackPreferences.getString("audio_movie-1", null))
        assertEquals("subtitle-track-id", trackPreferences.getString("subtitle_movie-1", null))
        assertTrue(trackPreferences.getBoolean("subtitleExplicit_movie-1", false))
        assertEquals(125L, trackPreferences.getLong("subtitleDelay_movie-1", 0L))
        assertTrue("Restoring another device must not replace this TV's 4K capability", devicePreferences.getBoolean("is_4k", false))
    }

    @Test fun profilePreferencesUsePortableIdentityAndDiagnosticsRemainDeviceLocal() = runBlocking {
        val portableId = "dcb97f8f-bf5e-48cb-b034-522b92218195"
        db.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Portable"))
        val accountPrefs = AccountStorage.preferences(context, "profile_configuration_prefs")
        accountPrefs.edit()
            .putBoolean("hide_episode_spoilers_1", true)
            .putBoolean("trailer_muted_1", false)
            .putInt("trailer_delay_1", 10)
            .putString("trailer_presentation_1", "fullscreen")
            .putInt("last_active_profile_id", 1)
            .putString("portable_profile_id_1", portableId)
            .commit()
        val diagnostics = AccountStorage.preferences(context, "playback_diagnostics_settings")
        diagnostics.edit().putBoolean("basic", true).putBoolean("detailed", false).commit()

        val portable = snapshot()
        val wirePreferences = portable.getAsJsonObject("preferences")
        val profileWire = wirePreferences.getAsJsonObject("profile_configuration_prefs")
        assertEquals(64, portable["schema"].asInt)
        assertFalse(wirePreferences.has("playback_diagnostics_settings"))
        assertTrue(profileWire.has("hide_episode_spoilers_u$portableId"))
        assertTrue(profileWire.has("trailer_muted_u$portableId"))
        assertTrue(profileWire.has("trailer_delay_u$portableId"))
        assertTrue(profileWire.has("trailer_presentation_u$portableId"))
        assertFalse(profileWire.has("last_active_profile_id"))
        assertFalse(profileWire.has("portable_profile_id_1"))
        assertFalse(profileWire.has("hide_episode_spoilers_1"))

        // Model an older encrypted snapshot that included diagnostics. Import remains compatible,
        // but the receiving TV's local diagnostics choice must not be overwritten.
        portable.addProperty("schema", 61)
        wirePreferences.add("playback_diagnostics_settings", JsonParser.parseString(
            """{"basic":{"type":"boolean","value":false},"detailed":{"type":"boolean","value":true}}"""
        ))
        accountPrefs.edit().putBoolean("hide_episode_spoilers_1", false).putInt("last_active_profile_id", 9).commit()
        diagnostics.edit().putBoolean("basic", true).putBoolean("detailed", false).commit()

        restore(portable)

        assertTrue(accountPrefs.getBoolean("hide_episode_spoilers_1", false))
        assertEquals(10, accountPrefs.getInt("trailer_delay_1", 0))
        assertEquals("fullscreen", accountPrefs.getString("trailer_presentation_1", null))
        assertEquals("An invalid stale local ID is cleared when no active profile exists", -1,
            accountPrefs.getInt("last_active_profile_id", -1))
        assertTrue(diagnostics.getBoolean("basic", false))
        assertFalse(diagnostics.getBoolean("detailed", true))
    }

    @Test fun profilePreferenceRestoreMapsPortableSettingsToTheReceivingRoomId() = runBlocking {
        val portableId = "73152724-1df3-4d67-88cb-7dff128eb5cd"
        db.addonDao().insertProfile(ProfileEntity(id = 1, portableId = portableId, name = "Portable"))
        val prefs = AccountStorage.preferences(context, "profile_configuration_prefs")
        prefs.edit().putBoolean("hide_episode_spoilers_1", true).putInt("trailer_delay_1", 15).commit()
        val remote = snapshot()
        remote.getAsJsonObject("tables").getAsJsonArray("profiles")[0].asJsonObject.addProperty("id", 42)
        remote.getAsJsonObject("preferences").getAsJsonObject("profile_configuration_prefs")
            .add("hide_episode_spoilers_u00000000-0000-4000-8000-000000000000",
                JsonParser.parseString("""{"type":"boolean","value":true}"""))

        prefs.edit().putInt("last_active_profile_id", 1).putString("portable_profile_id_1", portableId)
            .putBoolean("hide_episode_spoilers_1", false).commit()
        restore(remote)

        assertNotNull(db.addonDao().getProfileById(42))
        assertTrue(prefs.getBoolean("hide_episode_spoilers_42", false))
        assertEquals(15, prefs.getInt("trailer_delay_42", 0))
        assertFalse("Orphaned/local-ID preferences are cleared on restore", prefs.contains("hide_episode_spoilers_1"))
        assertFalse("Unknown portable profiles are ignored", prefs.contains("hide_episode_spoilers_43"))
        assertEquals("Active profile remains a local device choice", 42, prefs.getInt("last_active_profile_id", -1))

        // Pre-v62 snapshots stored the account preference under the sender's integer Room ID.
        val legacy = remote.deepCopy().apply {
            addProperty("schema", 61)
            val profilePrefs = getAsJsonObject("preferences").getAsJsonObject("profile_configuration_prefs")
            val portableSetting = profilePrefs.remove("hide_episode_spoilers_u$portableId")
            profilePrefs.add("hide_episode_spoilers_42", portableSetting)
        }
        restore(legacy)
        assertTrue(prefs.getBoolean("hide_episode_spoilers_42", false))
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
        assertFalse(prefs.contains("stale")); assertEquals(6, store.preferenceStores().size)
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
