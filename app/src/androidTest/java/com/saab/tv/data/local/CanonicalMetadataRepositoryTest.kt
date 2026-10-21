package com.saab.tv.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.repository.CanonicalMetadataRepository
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.IdentityNamespace
import com.saab.tv.domain.schema.CanonicalEntityKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalMetadataRepositoryTest {
    private lateinit var database: SaabTvDatabase
    private lateinit var dao: CanonicalMetadataDao
    private lateinit var repository: CanonicalMetadataRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), SaabTvDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.canonicalMetadataDao()
        repository = CanonicalMetadataRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun verifiedAliasPromotionIsTransitiveAndMovesLocaleMetadataToStableRoot() = runBlocking {
        val addon = requireNotNull(ContentIdentity.addon("catalog-one", ContentKind.SERIES, "opaque:17"))
        val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 17))
        val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt1234567"))
        val now = System.currentTimeMillis()

        repository.putTitle(CanonicalTitleEntity(
            identityKey = tmdb.stableKey,
            locale = "en-us",
            kind = "series",
            title = "Series title",
            titleSource = "TMDB",
            lastFetchedAtMillis = now
        ))
        repository.putTitle(CanonicalTitleEntity(
            identityKey = tmdb.stableKey,
            locale = "te-in",
            kind = "series",
            title = "స్థానిక శీర్షిక",
            titleSource = "TMDB",
            titleLanguage = "te-IN",
            lastFetchedAtMillis = now
        ))

        repository.linkVerifiedIdentities(addon, tmdb)
        repository.linkVerifiedIdentities(tmdb, imdb)

        assertEquals(imdb, repository.resolveAlias(addon, IdentityNamespace.IMDB))
        assertEquals(tmdb, repository.resolveAlias(imdb, IdentityNamespace.TMDB))
        assertEquals("Series title", repository.getTitle(addon, "EN_us")?.title)
        assertEquals("స్థానిక శీర్షిక", repository.getTitle(imdb, "te-IN")?.title)
        assertNull(dao.getTitle(tmdb.stableKey, "en-us"))
        assertEquals(imdb.stableKey, dao.getAlias(addon.stableKey)?.canonicalIdentityKey)
    }

    @Test
    fun identicalProviderIdsRemainDistinctAcrossKindAndAddonInstallation() = runBlocking {
        val movie = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 42))
        val series = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 42))
        val movieImdb = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt1111111"))
        val seriesImdb = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt2222222"))
        val addonOne = requireNotNull(ContentIdentity.addon("one", ContentKind.MOVIE, "42"))
        val addonTwo = requireNotNull(ContentIdentity.addon("two", ContentKind.MOVIE, "42"))

        repository.linkVerifiedIdentities(movie, movieImdb)
        repository.linkVerifiedIdentities(series, seriesImdb)
        repository.linkVerifiedIdentities(addonOne, movie)

        assertEquals(movieImdb, repository.resolveAlias(movie, IdentityNamespace.IMDB))
        assertEquals(seriesImdb, repository.resolveAlias(series, IdentityNamespace.IMDB))
        assertNull(repository.resolveAlias(addonTwo, IdentityNamespace.IMDB))
        assertNotEquals(dao.getAlias(movie.stableKey)?.canonicalIdentityKey, dao.getAlias(series.stableKey)?.canonicalIdentityKey)
    }

    @Test
    fun identityMergeIsOrderIndependentAndCacheRowsCanBePruned() = runBlocking {
        val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt3333333"))
        val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 33))
        repository.linkVerifiedIdentities(tmdb, imdb)
        val firstRoot = dao.getAlias(tmdb.stableKey)?.canonicalIdentityKey
        repository.linkVerifiedIdentities(imdb, tmdb)
        val secondRoot = dao.getAlias(tmdb.stableKey)?.canonicalIdentityKey
        assertEquals(imdb.stableKey, firstRoot)
        assertEquals(firstRoot, secondRoot)

        val now = System.currentTimeMillis()
        listOf("old", "middle", "new").forEachIndexed { index, id ->
            dao.putTitle(CanonicalTitleEntity(
                identityKey = id,
                locale = "",
                kind = "movie",
                lastFetchedAtMillis = now + index
            ))
        }
        dao.retainNewestTitles(2)
        assertNull(dao.getTitle("old", ""))
        assertNotNull(dao.getTitle("new", ""))
        assertNotNull(dao.getTitle("middle", ""))
    }

    @Test
    fun completeSeriesEpisodeListIsStoredAndReplacedAtomically() = runBlocking {
        val identity = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt4444444"))
        val observed = System.currentTimeMillis()
        val title = CanonicalTitleEntity(
            identityKey = identity.stableKey, locale = "", kind = "series",
            title = "Show", titleSource = "CINEMETA", lastFetchedAtMillis = observed
        )
        fun episode(id: String, order: Int) = CanonicalEpisodeEntity(
            seriesIdentityKey = identity.stableKey, locale = "", episodeKey = id, id = id,
            title = "Episode $order", released = null, thumbnail = null, overview = null,
            season = 1, episode = order, episodeOrder = order, observedAtMillis = observed
        )
        val firstManifest = CanonicalEpisodeListEntity(identity.stableKey, "", "CINEMETA", 2, observed)
        repository.putSeriesSnapshot(title, firstManifest, listOf(episode("s1e1", 1), episode("s1e2", 2)))
        assertEquals(2, repository.getSeriesSnapshot(identity)?.episodes?.size)

        val secondManifest = firstManifest.copy(episodeCount = 1, observedAtMillis = observed + 1)
        repository.putSeriesSnapshot(
            title.copy(lastFetchedAtMillis = observed + 1),
            secondManifest,
            listOf(episode("s1e3", 3).copy(observedAtMillis = observed + 1))
        )
        val snapshot = requireNotNull(repository.getSeriesSnapshot(identity))
        assertEquals(1, snapshot.manifest.episodeCount)
        assertEquals(listOf("s1e3"), snapshot.episodes.map { it.id })

        val emptyManifest = secondManifest.copy(episodeCount = 0, observedAtMillis = observed + 2)
        repository.putSeriesSnapshot(title, emptyManifest, emptyList())
        assertEquals(0, repository.getSeriesSnapshot(identity)?.episodes?.size)
    }

    @Test
    fun episodeCacheEvictsWholeOldSnapshotsAndNeverLeavesOrphanRows() = runBlocking {
        val now = System.currentTimeMillis()
        val identities = (1..3).map { index ->
            requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt${1000000 + index}"))
        }
        identities.forEachIndexed { index, identity ->
            val observed = now + index
            val title = CanonicalTitleEntity(
                identityKey = identity.stableKey, locale = "", kind = "series",
                title = "Series $index", titleSource = "CINEMETA", lastFetchedAtMillis = observed
            )
            val episodes = (1..2).map { episode ->
                CanonicalEpisodeEntity(
                    seriesIdentityKey = identity.stableKey, locale = "", episodeKey = "e$episode",
                    id = "${identity.externalId}:1:$episode", title = "Episode $episode",
                    released = null, thumbnail = null, overview = null, season = 1, episode = episode,
                    episodeOrder = episode - 1, observedAtMillis = observed
                )
            }
            repository.putSeriesSnapshot(
                title,
                CanonicalEpisodeListEntity(identity.stableKey, "", "CINEMETA", 2, observed),
                episodes
            )
        }

        dao.retainNewestEpisodeSnapshots(maxRows = 4)

        assertNull(repository.getSeriesSnapshot(identities.first()))
        assertEquals(2, repository.getSeriesSnapshot(identities[1])?.episodes?.size)
        assertEquals(2, repository.getSeriesSnapshot(identities[2])?.episodes?.size)
        assertEquals(4, dao.countEpisodes())

        dao.deleteEpisodeListsOlderThan(now + 3)
        dao.deleteOrphanEpisodes()
        assertEquals(0, dao.countEpisodes())
    }

    @Test
    fun userStateRowsPersistAndQueryByCanonicalIdentityWhileKeepingProviderIds() = runBlocking {
        database.addonDao().insertProfile(ProfileEntity(id = 1, name = "Identity"))
        val episode = WatchHistoryEntity(
            profileId = 1, id = "tt1234567:2:5", title = "Episode", poster = null,
            position = 10, duration = 100, lastWatched = 20, type = "series"
        )
        val library = WatchlistEntity(
            profileId = 1, id = "opaque-42", type = "series", title = "Opaque series",
            poster = null, addedAt = 30, sourceAddonKey = "installation-one"
        )
        val nextUp = com.saab.tv.data.model.SeriesNextUpEntity(
            profileId = 1, seriesId = "tt7654321", title = "Next", poster = null,
            nextSeason = 2, nextEpisode = 3, nextEpisodeTitle = null, updatedAt = 40
        )
        database.addonDao().insertHistory(episode)
        database.addonDao().addToWatchlist(library)
        database.addonDao().insertSeriesNextUp(nextUp)

        assertEquals(episode.id, database.addonDao()
            .getHistoryItemByCanonicalIdentity(1, CanonicalEntityKey.playable(episode.id, episode.type))?.id)
        assertEquals(library.id, database.addonDao()
            .getWatchlistItemsByCanonicalIdentity(1, CanonicalEntityKey.title(library.id, library.type, library.sourceAddonKey))
            .single().id)
        assertEquals(nextUp.seriesId, database.addonDao()
            .getSeriesNextUpByCanonicalIdentity(1, CanonicalEntityKey.title(nextUp.seriesId, "series"))?.seriesId)
    }

    @Test
    fun verifiedProviderAliasesAreIncludedInWatchedAndWatchlistBadgeKeys() = runBlocking {
        val addonDao = database.addonDao()
        addonDao.insertProfile(ProfileEntity(id = 1, name = "Badges"))
        val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt1234567"))
        val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 42))
        repository.linkVerifiedIdentities(tmdb, imdb)

        addonDao.insertHistory(WatchHistoryEntity(
            profileId = 1,
            id = imdb.externalId,
            title = "Watched movie",
            poster = null,
            position = 100,
            duration = 100,
            lastWatched = 1,
            type = "movie",
            watched = true,
            canonicalIdentityKey = imdb.stableKey
        ))
        addonDao.addToWatchlist(WatchlistEntity(
            profileId = 1,
            id = imdb.externalId,
            type = "movie",
            title = "Saved movie",
            poster = null,
            addedAt = 1,
            canonicalIdentityKey = imdb.stableKey
        ))

        val watchedKeys = addonDao.getWatchedBadgeKeysForProfile(1).first().toSet()
        val watchlistKeys = addonDao.getWatchlistBadgeKeysForProfile(1).first().toSet()
        assertTrue(watchedKeys.contains(imdb.externalId))
        assertTrue(watchedKeys.contains(imdb.stableKey))
        assertTrue("TMDB alias should match a card with a different provider ID", watchedKeys.contains(tmdb.stableKey))
        assertTrue(watchlistKeys.contains(imdb.externalId))
        assertTrue(watchlistKeys.contains(imdb.stableKey))
        assertTrue("TMDB alias should match a card with a different provider ID", watchlistKeys.contains(tmdb.stableKey))
    }

    @Test
    fun aliasPromotionRekeysExistingLibraryProgressAndNextUpRowsAtomically() = runBlocking {
        val addonDao = database.addonDao()
        addonDao.insertProfile(ProfileEntity(id = 1, name = "Alias migration"))
        val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 42))
        val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt1234567"))
        val oldEpisodeKey = CanonicalEntityKey.playable("tmdb:42:2:5", "series")
        val newEpisodeKey = CanonicalEntityKey.playable(
            "tmdb:42:2:5", "series", canonicalSeriesIdentityKey = imdb.stableKey
        )
        addonDao.addToWatchlist(WatchlistEntity(
            profileId = 1, id = "tmdb:42", type = "series", title = "Show", poster = null,
            addedAt = 1, canonicalIdentityKey = tmdb.stableKey
        ))
        addonDao.insertHistory(WatchHistoryEntity(
            profileId = 1, id = "tmdb:42:2:5", title = "Episode", poster = null,
            position = 50, duration = 100, lastWatched = 2, type = "series",
            canonicalIdentityKey = oldEpisodeKey
        ))
        addonDao.insertSeriesNextUp(SeriesNextUpEntity(
            profileId = 1, seriesId = "tmdb:42", title = "Show", poster = null,
            nextSeason = 2, nextEpisode = 6, nextEpisodeTitle = null, updatedAt = 3,
            canonicalIdentityKey = tmdb.stableKey
        ))

        repository.linkVerifiedIdentities(tmdb, imdb)

        assertEquals(imdb.stableKey, addonDao.getWatchlistItem(1, "tmdb:42")?.canonicalIdentityKey)
        assertEquals(newEpisodeKey, addonDao.getHistoryItemForProfile(1, "tmdb:42:2:5")?.canonicalIdentityKey)
        assertEquals(
            listOf("tmdb:42:2:5"),
            addonDao.getSeriesEpisodeHistoryByCanonicalPrefix(1, "episode/${imdb.stableKey}/").map { it.id }
        )
        assertEquals(imdb.stableKey,
            addonDao.getSeriesNextUpForProfile(1, "tmdb:42")?.canonicalIdentityKey)
        assertNotEquals(oldEpisodeKey, newEpisodeKey)
    }
}
