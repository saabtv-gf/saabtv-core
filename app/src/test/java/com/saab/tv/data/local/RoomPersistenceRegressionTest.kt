package com.saab.tv.data.local

import android.app.Application
import androidx.room.Room
import com.saab.tv.data.model.*
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPersistenceRegressionTest {
    private lateinit var db: SaabTvDatabase
    private lateinit var dao: AddonDao
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.addonDao()
    }
    @After fun tearDown() { db.close() }
    private fun history(profile: Int = 1, id: String = "tt1:1:1", time: Long = 100, position: Long = 0) =
        WatchHistoryEntity(profileId = profile, id = id, title = "Title", poster = null,
            position = position, duration = 1_000_000, lastWatched = time, type = "series")
    private fun addon(url: String, order: Int = 1) = AddonEntity(url, url, url, "1", null, null, sortOrder = order)
    private fun config(id: String, url: String = "addon") = CatalogConfigEntity(id, url, "Addon", "movie", "top")

    @Test fun profilesRoundTripAllPlaybackPreferencesAndOnlyOneIsActive() = runBlocking {
        val first = ProfileEntity(id = 1, name = "First", isActive = true, pinHash = "hash",
            seekTimeIntervalSeconds = 20, seekThumbnailIntervalSeconds = 20, sourceLanguagePriority1 = "ml",
            preferredSubtitleLanguage = "kn", watchedThreshold = 95, subtitleOffset = -3)
        dao.insertProfile(first); dao.insertProfile(ProfileEntity(id = 2, name = "Second"))
        assertEquals(first, dao.getProfileById(1)); assertEquals(first, dao.getProfileFlow(1).first())
        dao.activateProfile(2)
        assertEquals(2, dao.getActiveProfileId())
        assertFalse(dao.getProfileById(1)!!.isActive)
        assertEquals(1, dao.getProfiles().first().count { it.isActive })
        val updated = dao.getProfileById(2)!!.copy(name = "Updated")
        dao.updateProfile(updated); assertEquals(updated, dao.getProfileById(2))
        dao.deleteProfile(2); assertNull(dao.getProfileById(2))
    }

    @Test fun zeroPositionAndLargeTimestampsSurviveDatabaseRoundTrip() = runBlocking {
        dao.insertProfile(ProfileEntity(id = 1, name = "One", isActive = true))
        val item = history(time = 9_007_199_254_740_993L)
        dao.upsertHistory(item)
        assertEquals(item, dao.getHistoryItem(item.id))
        assertEquals(0f, dao.getHistoryItem(item.id)!!.progress())
        dao.insertHistory(item.copy(position = 500_000))
        assertEquals(.5f, dao.getHistoryItem(item.id)!!.progress())
    }

    @Test fun historyQueriesUseActiveProfileNotAnotherDevicesOrProfilesProgress() = runBlocking {
        dao.insertProfile(ProfileEntity(id = 1, name = "One", isActive = true))
        dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        dao.insertHistory(history(1, position = 100)); dao.insertHistory(history(2, position = 900))
        assertEquals(100L, dao.getHistoryItem("tt1:1:1")!!.position)
        dao.activateProfile(2)
        assertEquals(900L, dao.getHistoryItem("tt1:1:1")!!.position)
        assertEquals(listOf(history(2, position = 900)), dao.getAllWatchHistoryOnce())
        assertEquals(listOf(history(2, position = 900)), dao.getWatchHistory().first())
        dao.clearWatchHistory()
        assertNull(dao.getHistoryItem("tt1:1:1"))
        assertNotNull(dao.getHistoryItemForProfile(1, "tt1:1:1"))
    }

    @Test fun latestSeriesEpisodeUsesLastWatchedRatherThanFurthestEpisodeOrPosition() = runBlocking {
        dao.insertHistoryItems(listOf(history(id = "tt1:1:1", time = 300, position = 0),
            history(id = "tt1:2:10", time = 200, position = 900_000), history(id = "tt2:1:1", time = 400)))
        assertEquals("tt1:1:1", dao.getLatestSeriesEpisodeHistory("tt1:%")!!.id)
        assertEquals(2, dao.getSeriesEpisodeHistory("tt1:%").size)
        assertEquals(2, dao.getHistoryItemsByPrefix("tt1:").size)
        dao.deleteSeriesHistory("tt1:%")
        assertTrue(dao.getSeriesEpisodeHistory("tt1:%").isEmpty())
        assertNotNull(dao.getHistoryItem("tt2:1:1"))
        dao.deleteHistoryItem("tt2:1:1"); assertTrue(dao.getAllWatchHistoryOnce().isEmpty())
    }

    @Test fun remoteMergeAcceptsOnlyNewerValidRowsAndRespectsAllowMissing() = runBlocking {
        dao.insertProfile(ProfileEntity(id = 1, name = "One", isActive = true))
        val local = history(time = 100, position = 100)
        dao.insertHistory(local)
        dao.mergeRemotePlaybackHistory(listOf(local.copy(lastWatched = 99, position = 999),
            local.copy(lastWatched = 100, position = 999), history(id = "new", time = 200),
            history(profile = 999, id = "missing-profile", time = 200), history(id = "invalid-time", time = 0)), false)
        assertEquals(local, dao.getHistoryItem(local.id)); assertNull(dao.getHistoryItem("new"))
        val newer = local.copy(lastWatched = 101, position = 250, watched = true)
        dao.mergeRemotePlaybackHistory(listOf(newer, history(id = "new", time = 200)), true)
        assertEquals(newer, dao.getHistoryItem(local.id)); assertNotNull(dao.getHistoryItem("new"))
        assertNull(dao.getHistoryItemForProfile(999, "missing-profile")); assertNull(dao.getHistoryItem("invalid-time"))
    }

    @Test fun watchedIdsScrobbleQueriesAndArtworkUpdatesPreservePlaybackState() = runBlocking {
        val watched = history().copy(watched = true, scrobbled = true, position = 950_000)
        val active = history(id = "active").copy(scrobbled = true)
        dao.insertHistoryItems(listOf(watched, active, history(id = "unscrobbled")))
        assertEquals(listOf(watched.id), dao.getWatchedIds().first())
        assertEquals(listOf(watched), dao.getScrobbledWatchedItems())
        assertEquals(listOf(active), dao.getScrobbledInProgressItems())
        dao.updateHistoryImages(watched.id, "poster", "background", "logo")
        assertEquals(watched.copy(poster = "poster", background = "background", logo = "logo"), dao.getHistoryItem(watched.id))
    }

    @Test fun bulkUpsertAssignsActiveProfileAndProfileDeletionDoesNotClearOtherHistory() = runBlocking {
        dao.insertProfile(ProfileEntity(id = 2, name = "Two", isActive = true))
        dao.upsertHistoryItems(listOf(history(id = "first"), history(id = "second")))
        assertEquals(2, dao.getAllWatchHistoryOnce().size)
        assertTrue(dao.getAllWatchHistoryOnce().all { it.profileId == 2 })
        dao.insertHistory(history(1, id = "other"))
        dao.deleteHistoryForProfile(2)
        assertNotNull(dao.getHistoryItemForProfile(1, "other"))
        assertNull(dao.getHistoryItemForProfile(2, "first"))
    }

    @Test fun watchlistIsProfileScopedOrderedAndRemovableByTypeAndIdentity() = runBlocking {
        val movie = WatchlistEntity(1, "tt1", "movie", "Movie", "poster", 100)
        val series = WatchlistEntity(1, "tt2", "series", "Series", null, 200)
        dao.addToWatchlist(movie); dao.addToWatchlist(series); dao.addToWatchlist(movie.copy(profileId = 2))
        assertEquals(listOf(series, movie), dao.getWatchlistOnce(1))
        assertEquals(listOf(series, movie), dao.getWatchlist(1).first())
        assertEquals(listOf(movie), dao.getWatchlistByType(1, "movie").first())
        assertTrue(dao.isInWatchlist(1, "tt1")); assertTrue(dao.isInWatchlistFlow(1, "tt1").first())
        assertEquals(movie, dao.getWatchlistItem(1, "tt1"))
        dao.removeFromWatchlist(1, "tt1"); assertFalse(dao.isInWatchlist(1, "tt1"))
        assertTrue(dao.isInWatchlist(2, "tt1"))
        dao.deleteWatchlistForProfile(1); assertTrue(dao.getWatchlistOnce(1).isEmpty())
    }

    @Test fun addonCatalogCrudAndRuntimeReplacementRemoveStaleRows() = runBlocking {
        dao.insertAddons(listOf(addon("later", 2), addon("first", 1)))
        assertEquals(listOf("first", "later"), dao.getAllAddons().first().map { it.transportUrl })
        assertEquals(addon("first"), dao.getAddon("first"))
        dao.deleteAddonByUrl("later"); assertNull(dao.getAddon("later"))
        dao.saveCatalogConfig(config("old")); assertEquals(config("old"), dao.getCatalogConfig("old"))
        dao.saveCatalogConfigs(listOf(config("another", "other")))
        assertEquals(2, dao.getAllCatalogConfigs().first().size)
        dao.deleteCatalogConfigs("other"); assertNull(dao.getCatalogConfig("another"))
        dao.replaceRuntimeState(listOf(addon("new")), listOf(config("new")), emptyList(), emptyList())
        assertEquals(listOf(addon("new")), dao.getAllAddons().first())
        assertEquals(listOf(config("new")), dao.getAllCatalogConfigs().first())
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(dao.getAllAddons().first().isEmpty()); assertTrue(dao.getAllCatalogConfigs().first().isEmpty())
    }

    @Test fun hubsRoundTripRelationsOrderAndImageUpdates() = runBlocking {
        val row = HubRowEntity("hub", "Title", "poster", homeOrder = 2, moviesOrder = 3, seriesOrder = 4, createdAt = 1)
        val item = HubRowItemEntity("hub", "catalog", "Item", itemOrder = 5)
        dao.insertHubRowWithItems(row, listOf(item))
        assertEquals(listOf(row), dao.getAllHubRows().first()); assertEquals(listOf(item), dao.getAllHubRowItems().first())
        assertEquals(1, dao.getHubRowsWithItems().first().single().items.size)
        assertEquals(2, dao.getMaxHubHomeOrder()); assertEquals(3, dao.getMaxHubMoviesOrder()); assertEquals(4, dao.getMaxHubSeriesOrder())
        assertEquals(5, dao.getMaxHubItemOrder("hub"))
        dao.updateHubItemImage("hub", "catalog", "new-image")
        assertEquals("new-image", dao.getAllHubRowItems().first().single().customImageUrl)
        dao.updateHubRow(row.copy(title = "Updated")); assertEquals("Updated", dao.getAllHubRows().first().single().title)
        dao.updateHubRows(listOf(row)); dao.updateHubRowItem(item); dao.updateHubRowItems(listOf(item.copy(title = "Changed")))
        assertEquals("Changed", dao.getAllHubRowItems().first().single().title)
        dao.deleteHubRowItem("hub", "catalog"); assertTrue(dao.getAllHubRowItems().first().isEmpty())
        dao.insertHubRowItem(item); dao.deleteHubRowWithItems("hub")
        assertTrue(dao.getAllHubRows().first().isEmpty()); assertTrue(dao.getAllHubRowItems().first().isEmpty())
        assertNull(dao.getMaxHubHomeOrder())
    }

    @Test fun themesAreStoredUpdatedAndDeletedWithoutColorPrecisionLoss() = runBlocking {
        val theme = ThemeEntity("id", "Theme", 0xFFFFFFFF, 0xFF000000, 0xFF222222, 0xFFFFFFFF, 0xFFCCCCCC, 0xFFFF0000)
        dao.insertTheme(theme); assertEquals(theme, dao.getThemeById("id")); assertEquals(listOf(theme), dao.getAllThemes().first())
        dao.insertTheme(theme.copy(name = "Updated")); assertEquals("Updated", dao.getThemeById("id")!!.name)
        dao.deleteTheme(theme); assertNull(dao.getThemeById("id"))
    }

    @Test fun nextEpisodeStateIsProfileScopedAndCompleteSeriesAreHidden() = runBlocking {
        dao.insertProfile(ProfileEntity(id = 2, name = "Two", isActive = true))
        val next = SeriesNextUpEntity(seriesId = "tt1", title = "Series", poster = null,
            nextSeason = 2, nextEpisode = 1, nextEpisodeTitle = "Next", updatedAt = 123)
        dao.upsertSeriesNextUp(next)
        assertEquals(next.copy(profileId = 2), dao.getSeriesNextUp("tt1"))
        assertEquals(1, dao.getActiveSeriesNextUp().first().size)
        dao.insertSeriesNextUp(next.copy(profileId = 2, isComplete = true))
        assertTrue(dao.getActiveSeriesNextUp().first().isEmpty())
        dao.insertSeriesNextUp(next.copy(profileId = 1)); dao.deleteSeriesNextUp("tt1")
        assertNull(dao.getSeriesNextUp("tt1")); dao.deleteSeriesNextUpForProfile(1)
    }
}
