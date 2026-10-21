package com.saab.tv.data.account

import android.app.Application
import androidx.room.Room
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.repository.CanonicalMetadataRepository
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.profile.ProfileRuntimeSnapshot
import com.google.gson.Gson
import com.saab.tv.testing.AccountTransportFixture
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.CanonicalEntityKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UserStateCommandRepositoryTest {
    private lateinit var db: SaabTvDatabase
    private lateinit var commands: UserStateCommandRepository
    private lateinit var account: AccountTransportFixture

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        account = AccountTransportFixture(RuntimeEnvironment.getApplication())
        commands = UserStateCommandRepository(db, account.auth)
    }

    @After fun close() = db.close()

    @Test fun libraryToggleIsImmediateAndProducesExplicitUpsertThenTombstone() = runBlocking {
        profile(1)
        val entry = WatchlistEntity(1, "tmdb:42", "movie", "A title", "poster", 10)
        assertTrue(commands.toggleLibrary(1, entry))
        assertEquals(entry, db.addonDao().getWatchlistItem(1, entry.id))
        assertFalse(commands.toggleLibrary(1, entry))
        assertEquals(null, db.addonDao().getWatchlistItem(1, entry.id))

        val events = commands.pending()
        assertEquals(1, events.size) // superseded local add was coalesced, not uploaded
        assertEquals("DELETE", events.single().operation)
        assertEquals(2L, events.single().entityRevision)
        assertTrue(events.single().entityKey != entry.id)
    }

    @Test fun deletingProfileDropsItsQueuedChildWritesAndKeepsOneNewerProfileTombstone() = runBlocking {
        profile(1)
        val profile = requireNotNull(db.addonDao().getProfileById(1))
        commands.saveProfile(profile.copy(name = "Edited"))
        commands.toggleLibrary(1, WatchlistEntity(1, "tt1234567", "movie", "Film", null, 10))
        commands.savePlaybackProgress(history(position = 25_000, time = 25))
        assertTrue(commands.pending().any { it.entityType == "playback_progress" })

        assertTrue(commands.deleteProfile(1))

        val remaining = commands.pending()
        assertEquals(1, remaining.size)
        assertEquals("profile_state", remaining.single().entityType)
        assertEquals("DELETE", remaining.single().operation)
        assertEquals(2L, remaining.single().entityRevision)
        assertTrue(db.addonDao().getWatchlistOnce(1).isEmpty())
        assertTrue(db.addonDao().getWatchHistoryForProfileOnce(1).isEmpty())
    }

    @Test fun verifiedAliasesShareLibraryAndEpisodeProgressCommandKeys() = runBlocking {
        profile(1)
        val canonical = CanonicalMetadataRepository(db.canonicalMetadataDao())
        val tmdbSeries = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 42))
        val imdbSeries = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt1234567"))
        canonical.linkVerifiedIdentities(tmdbSeries, imdbSeries)
        val aliasAwareCommands = UserStateCommandRepository(db, account.auth, canonical)

        val tmdbLibraryItem = WatchlistEntity(1, "tmdb:42", "series", "Series", null, 10)
        assertTrue(aliasAwareCommands.toggleLibrary(1, tmdbLibraryItem))
        val storedLibrary = db.addonDao().getWatchlistItem(1, "tmdb:42")
        assertEquals(imdbSeries.stableKey, storedLibrary?.canonicalIdentityKey)

        // A later action through the verified IMDb alias must find and remove the same row.
        assertFalse(aliasAwareCommands.toggleLibrary(1, tmdbLibraryItem.copy(id = "tt1234567")))
        assertTrue(db.addonDao().getWatchlistOnce(1).isEmpty())

        val episode = history(position = 12_000, time = 500).copy(
            id = "tmdb:42:2:5", type = "series"
        )
        aliasAwareCommands.savePlaybackProgress(episode)
        val storedEpisode = db.addonDao().getHistoryItemForProfile(1, episode.id)
        assertEquals(
            CanonicalEntityKey.playable(episode.id, "series", canonicalSeriesIdentityKey = imdbSeries.stableKey),
            storedEpisode?.canonicalIdentityKey
        )
        assertTrue(aliasAwareCommands.pending().any { it.entityType == "playback_progress" && it.entityKey != episode.id })

        // A tombstone through the IMDb alias clears the same locally stored TMDB episode.
        assertTrue(aliasAwareCommands.clearPlaybackProgress(1, "tt1234567:2:5", "series"))
        assertEquals(null, db.addonDao().getHistoryItemForProfile(1, episode.id))
        val pendingProgress = aliasAwareCommands.pending().single { it.entityType == "playback_progress" }
        assertEquals("DELETE", pendingProgress.operation)
        assertTrue(pendingProgress.payloadJson.contains(imdbSeries.stableKey))
    }

    @Test fun markingWatchedRemovesLibraryAndCommitsStateAndTombstoneTogether() = runBlocking {
        profile(1)
        val library = WatchlistEntity(1, "tt1234567", "movie", "Film", null, 10)
        db.addonDao().addToWatchlist(library)
        val history = WatchHistoryEntity(1, "tt1234567", "Film", null, position = 900_000,
            duration = 900_000, lastWatched = 20, type = "movie", watched = true)

        commands.markWatched(1, "tt1234567", "movie", listOf(history))

        assertEquals(null, db.addonDao().getWatchlistItem(1, "tt1234567"))
        assertTrue(db.addonDao().getHistoryItemForProfile(1, history.id)?.watched == true)
        val events = commands.pending()
        assertEquals(setOf("library", "watched_state"), events.map { it.entityType }.toSet())
        assertTrue(events.all { it.portableProfileId == db.addonDao().getProfileById(1)?.portableId })
    }

    @Test fun playbackCheckpointCoalescesAndSequenceSurvivesAcknowledgement() = runBlocking {
        profile(1)
        val first = history(position = 10_000, time = 100)
        val second = first.copy(position = 20_000, lastWatched = 200)
        commands.savePlaybackProgress(first)
        commands.savePlaybackProgress(second)
        val event = commands.pending().single()
        assertEquals(2L, event.entityRevision)
        assertEquals(2L, event.sequence)
        assertTrue(event.payloadJson.contains("20000"))
        assertEquals(second, db.addonDao().getHistoryItemForProfile(1, first.id))
        assertEquals(1, commands.acknowledge(listOf(event.operationId)))
        commands.savePlaybackProgress(second.copy(position = 30_000, lastWatched = 300))
        assertEquals(3L, commands.pending().single().sequence)
    }

    @Test fun outboxPayloadIsEncryptedAtRestAndDecryptedOnlyForTheCommandBoundary() = runBlocking {
        profile(1)
        val history = history(position = 22_000, time = 200)
        commands.savePlaybackProgress(history)

        val stored = db.syncOutboxDao().pending(account.user, 1).single()
        assertTrue(stored.payloadJson.startsWith("local-v1:"))
        assertFalse(stored.payloadJson.contains(history.title))
        assertTrue(commands.pending().single().payloadJson.contains(history.title))
    }

    @Test fun sourceSelectionJournalsOnlyStableTorrentIdentityAndCoalescesClear() = runBlocking {
        profile(1)
        val hash = "a".repeat(40)
        val selection = SourceSelectionStatePayload("tt1234567", "movie", hash)

        commands.saveSourceSelection(1, selection)

        val upsert = commands.pending().single()
        assertEquals("source_selection", upsert.entityType)
        assertEquals("UPSERT", upsert.operation)
        assertEquals(selection, Gson().fromJson(upsert.payloadJson, SourceSelectionStatePayload::class.java))
        assertFalse(upsert.payloadJson.contains("https://"))
        assertFalse(upsert.payloadJson.contains("token"))

        commands.clearSourceSelection(1, selection.titleId, selection.type)
        assertEquals("DELETE", commands.pending().single().operation)
    }

    @Test fun sourceSelectionRejectsUrlLikeAndMalformedTorrentIdentities() = runBlocking {
        profile(1)
        listOf("https://media.example/file.mkv", "not-a-torrent-hash").forEach { unsafe ->
            try {
                commands.saveSourceSelection(1, SourceSelectionStatePayload("tt1234567", "movie", unsafe))
                throw AssertionError("Unsafe source identity was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, commands.pendingCount())
    }

    @Test fun profilesHavePortableIdentityAndNeverShareAnEntityRevision() = runBlocking {
        profile(1)
        profile(2)
        val first = db.addonDao().getProfileById(1)!!
        val second = db.addonDao().getProfileById(2)!!
        assertNotEquals(first.portableId, second.portableId)
        commands.savePlaybackProgress(history(profileId = 1))
        commands.savePlaybackProgress(history(profileId = 2))
        val events = commands.pending()
        assertEquals(2, events.size)
        assertNotEquals(events[0].entityKey, events[1].entityKey)
        assertTrue(events.map { it.portableProfileId }.toSet().containsAll(setOf(first.portableId, second.portableId)))
    }

    @Test fun profilePreferenceChangeWritesPortableEncryptedSyncOperation() = runBlocking {
        val profile = ProfileEntity(id = 1, portableId = UUID.randomUUID().toString(), name = "Profile")
        db.addonDao().insertProfile(profile)

        val stored = commands.saveProfile(profile.copy(titleCardShape = "landscape", isActive = false))

        assertEquals(1, stored.id)
        assertEquals("landscape", db.addonDao().getProfileById(1)?.titleCardShape)
        val event = commands.pending().single()
        assertEquals("profile_state", event.entityType)
        assertEquals("UPSERT", event.operation)
        assertTrue(event.payloadJson.contains(profile.portableId))
        assertTrue(event.payloadJson.contains("landscape"))
        assertFalse(event.payloadJson.contains("\"id\":1"))
    }

    @Test fun deletingProfileAtomicallyRemovesLocalRowsAndQueuesPortableTombstone() = runBlocking {
        profile(1)
        val owner = db.addonDao().getProfileById(1)!!
        db.addonDao().addToWatchlist(WatchlistEntity(1, "tt1234567", "movie", "Film", null, 1))
        db.addonDao().insertHistory(history(profileId = 1))

        assertTrue(commands.deleteProfile(1))

        assertEquals(null, db.addonDao().getProfileById(1))
        assertTrue(db.addonDao().getWatchlistOnce(1).isEmpty())
        assertTrue(commands.pending().single().let {
            it.portableProfileId == owner.portableId && it.entityType == "profile_state" &&
                it.operation == "DELETE" && it.payloadJson.contains(owner.portableId)
        })
    }

    @Test fun runtimeConfigurationIsOneBoundedPortableProfileCommand() = runBlocking {
        profile(1)
        val owner = db.addonDao().getProfileById(1)!!
        val snapshot = ProfileRuntimeSnapshot(addons = listOf(AddonEntity(
            transportUrl = "https://catalog.example/manifest.json", id = "catalog", name = "Catalog",
            version = "1", description = null, iconUrl = null
        )))

        commands.saveProfileRuntime(1, snapshot)

        val event = commands.pending().single()
        assertEquals("profile_runtime", event.entityType)
        assertEquals("UPSERT", event.operation)
        assertEquals(owner.portableId, event.portableProfileId)
        assertTrue(event.payloadJson.contains("catalog.example"))
    }

    @Test fun oversizedRuntimeSnapshotStaysOnCompatibilityPathWithoutPartialOutboxRow() = runBlocking {
        profile(1)
        val tooLarge = ProfileRuntimeSnapshot(addons = listOf(AddonEntity(
            transportUrl = "https://catalog.example/manifest.json", id = "catalog", name = "Catalog",
            version = "1", description = "x".repeat(270_000), iconUrl = null
        )))

        assertFalse(commands.saveProfileRuntime(1, tooLarge))
        assertEquals(0, commands.pendingCount())
    }

    @Test fun credentialBearingRuntimeUrlUsesEncryptedOutboxAndSupersedesPriorConfiguration() = runBlocking {
        profile(1)
        val safeSnapshot = ProfileRuntimeSnapshot(addons = listOf(AddonEntity(
            transportUrl = "https://catalog.example/manifest.json", id = "catalog", name = "Catalog",
            version = "1", description = null, iconUrl = null
        )))
        assertTrue(commands.saveProfileRuntime(1, safeSnapshot))
        assertEquals(1, commands.pendingCount())

        val credentialUrl = "https://catalog.example/private/abCDef0123456789abCDef0123456789/manifest.json"
        val credentialSnapshot = safeSnapshot.copy(addons = listOf(safeSnapshot.addons.single().copy(
            transportUrl = credentialUrl
        )))
        assertTrue(commands.saveProfileRuntime(1, credentialSnapshot))
        assertEquals(1, commands.pendingCount())
        val stored = db.syncOutboxDao().pending(account.user, 1).single()
        assertTrue(stored.payloadJson.startsWith("local-v1:"))
        assertFalse(stored.payloadJson.contains(credentialUrl))
        assertTrue(commands.pending().single().payloadJson.contains(credentialUrl))

        val querySecret = safeSnapshot.copy(addons = listOf(safeSnapshot.addons.single().copy(
            transportUrl = "https://catalog.example/manifest.json?api_key=secret"
        )))
        assertTrue(commands.saveProfileRuntime(1, querySecret))
        assertEquals(1, commands.pendingCount())
        assertFalse(db.syncOutboxDao().pending(account.user, 1).single().payloadJson.contains("api_key=secret"))
    }

    @Test fun seriesNextUpAndDeletionAreJournaledAndCompletionRemovesLibraryAtomically() = runBlocking {
        profile(1)
        val nextUp = SeriesNextUpEntity(
            profileId = 1, seriesId = "tt1234567", title = "Series", poster = null,
            nextSeason = 2, nextEpisode = 4, nextEpisodeTitle = "Next", nextReleased = null, updatedAt = 1L
        )
        db.addonDao().addToWatchlist(WatchlistEntity(1, "tt1234567", "series", "Series", null, 1))

        commands.saveSeriesNextUp(1, nextUp.seriesId, nextUp.copy(isComplete = true))

        assertEquals(null, db.addonDao().getWatchlistItem(1, nextUp.seriesId))
        assertEquals(2, commands.pending().size)
        assertTrue(commands.pending().any { it.entityType == "library" && it.operation == "DELETE" })
        assertTrue(commands.pending().any {
            it.entityType == "series_next_up" && it.operation == "UPSERT" &&
                it.payloadJson.contains("\"isComplete\":true")
        })

        commands.saveSeriesNextUp(1, nextUp.seriesId, null)
        val seriesState = commands.pending().single { it.entityType == "series_next_up" }
        assertEquals("DELETE", seriesState.operation)
    }

    @Test fun failedMutationDoesNotLeaveAnOutboxRecord() = runBlocking {
        profile(1)
        try {
            commands.execute(1, "library", "tt1234567", "UPSERT", mapOf("title" to "x")) {
                error("abort")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(0, commands.pendingCount())
    }

    @Test fun signedOutLocalMutationNeverQueuesForTheNextAccount() = runBlocking {
        account.auth.clearSession()
        profile(1)
        val entry = WatchlistEntity(1, "tt7654321", "movie", "Private local", null, 10)
        assertTrue(commands.toggleLibrary(1, entry))
        assertEquals(entry, db.addonDao().getWatchlistItem(1, entry.id))
        assertEquals(0, db.syncOutboxDao().pendingCount())
    }

    @Test fun opaqueLibraryIdsStaySeparatedByAddonOrigin() = runBlocking {
        profile(1)
        val first = WatchlistEntity(1, "opaque-7", "movie", "Film A", null, 10,
            sourceAddonBaseUrl = "https://provider-a.example/manifest.json")
        val second = first.copy(title = "Film B", sourceAddonBaseUrl = "https://provider-b.example/manifest.json")
        commands.toggleLibrary(1, first)
        commands.toggleLibrary(1, second)
        assertEquals(2, db.addonDao().getWatchlistOnce(1).size)
        assertEquals(2, commands.pending().map { it.entityKey }.distinct().size)

        commands.toggleLibrary(1, first)
        val remaining = db.addonDao().getWatchlistOnce(1)
        assertEquals(1, remaining.size)
        assertEquals(second.sourceAddonBaseUrl, remaining.single().sourceAddonBaseUrl)
        assertEquals(setOf("UPSERT", "DELETE"), commands.pending().map { it.operation }.toSet())
    }

    @Test fun episodeWatchCommandUsesTheReceiverPayloadContract() = runBlocking {
        profile(1)
        val episode = history().copy(watched = true, lastWatched = 300)

        commands.setEpisodeWatched(1, episode.id, true, listOf(episode))

        val event = commands.pending().single()
        assertEquals("watched_state", event.entityType)
        val payload = Gson().fromJson(event.payloadJson, WatchedStatePayload::class.java)
        assertEquals(episode.id, payload.titleId)
        assertEquals("series", payload.type)
        assertTrue(payload.watched)
        assertEquals(listOf(episode.id), payload.history.map { it.id })
    }

    @Test fun libraryDeleteCommandCarriesOriginForRemoteTombstone() = runBlocking {
        profile(1)
        val originKey = "a".repeat(24)
        val entry = WatchlistEntity(1, "opaque-8", "movie", "Provider title", null, 10,
            sourceAddonBaseUrl = "https://provider.example/manifest.json", sourceAddonKey = originKey)
        db.addonDao().addToWatchlist(entry)

        commands.removeFromLibrary(1, entry.id, entry.type, originKey) {
            db.addonDao().removeWatchlistItemFromAddon(1, entry.id, originKey)
        }

        val payload = Gson().fromJson(commands.pending().single().payloadJson, LibraryStatePayload::class.java)
        assertEquals(entry.id, payload.id)
        assertEquals(entry.type, payload.type)
        assertEquals(originKey, payload.sourceAddonKey)
    }

    @Test fun clearingSeriesProgressDeletesEveryKnownRowAndJournalsRemoteClear() = runBlocking {
        profile(1)
        val aggregate = history().copy(id = "tt1234567", watched = true)
        val episode = history().copy(watched = true)
        db.addonDao().insertHistoryItems(listOf(aggregate, episode))
        val nextUp = com.saab.tv.data.model.SeriesNextUpEntity(
            profileId = 1, seriesId = "tt1234567", title = "Series", poster = null,
            nextSeason = 2, nextEpisode = 1, nextEpisodeTitle = null, nextReleased = null,
            isComplete = false, updatedAt = 100
        )
        db.addonDao().insertSeriesNextUp(nextUp)

        commands.clearTitleProgress(1, "tt1234567", "series", listOf(aggregate, episode))

        assertEquals(null, db.addonDao().getHistoryItemForProfile(1, aggregate.id))
        assertEquals(null, db.addonDao().getHistoryItemForProfile(1, episode.id))
        assertTrue(db.addonDao().getSeriesNextUpForProfile(1).isEmpty())
        val clear = commands.pending().first { it.entityType == "watched_state" }
        val payload = Gson().fromJson(clear.payloadJson, WatchedStatePayload::class.java)
        assertTrue(payload.clearTitleHistory)
        assertTrue(payload.clearSeriesNextUp)
        assertEquals(setOf(aggregate.id, episode.id), payload.clearHistoryIds.toSet())
    }

    private suspend fun profile(id: Int) {
        db.addonDao().insertProfile(ProfileEntity(id = id, name = "Profile $id"))
    }

    private fun history(profileId: Int = 1, position: Long = 10_000, time: Long = 100) =
        WatchHistoryEntity(profileId, "tt1234567:1:2", "Episode", null, position = position,
            duration = 900_000, lastWatched = time, type = "series")
}
