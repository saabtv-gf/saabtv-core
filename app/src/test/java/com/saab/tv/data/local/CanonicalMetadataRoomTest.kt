package com.saab.tv.data.local

import android.app.Application
import androidx.room.Room
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.repository.CanonicalMetadataRepository
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.IdentityNamespace
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.tmdb.TmdbService
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
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalMetadataRoomTest {
    private lateinit var db: SaabTvDatabase
    private lateinit var dao: CanonicalMetadataDao
    private lateinit var repository: CanonicalMetadataRepository
    private val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 42))
    private val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt1234567"))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.canonicalMetadataDao()
        repository = CanonicalMetadataRepository(dao)
    }
    @After fun close() { db.close() }

    @Test fun writesAfterAliasPromotionUseCanonicalRootAndNormalizeLocale() = runBlocking {
        repository.linkVerifiedIdentities(tmdb, imdb)
        repository.putTitle(row(tmdb, " EN_us ", "English"))
        assertEquals("English", repository.getTitle(imdb, "en-US")?.title)
        assertEquals("English", repository.getTitle(tmdb, "EN_us")?.title)
        assertNull(dao.getTitle(tmdb.stableKey, "en-us"))
        assertEquals(imdb.stableKey, repository.getTitle(tmdb, "en-us")?.identityKey)
        assertNotNull(repository.getFreshTitle(tmdb, "en-US", 60_000))
        assertNull(repository.getTitle(tmdb))
    }

    @Test fun transitiveAliasPromotionPreservesComplementaryFieldsAndLocaleRows() = runBlocking {
        val addon = requireNotNull(ContentIdentity.addon("addon", ContentKind.MOVIE, "42"))
        repository.putTitle(row(tmdb, "en", "English").copy(poster = "poster", posterSource = "TMDB"))
        repository.putTitle(row(imdb, "en", "Override").copy(titleSource = "USER_OVERRIDE", logo = "logo", logoSource = "CINEMETA"))
        repository.putTitle(row(tmdb, "te", "తెలుగు"))
        repository.linkVerifiedIdentities(addon, tmdb)
        repository.linkVerifiedIdentities(tmdb, imdb)
        assertEquals(imdb, repository.resolveAlias(addon, IdentityNamespace.IMDB))
        assertEquals(tmdb, repository.resolveAlias(imdb, IdentityNamespace.TMDB))
        assertEquals(addon, repository.resolveAlias(imdb, IdentityNamespace.ADDON))
        assertEquals("Override", repository.getTitle(addon, "en")?.title)
        assertEquals("poster", repository.getTitle(imdb, "en")?.poster)
        assertEquals("logo", repository.getTitle(tmdb, "en")?.logo)
        assertEquals("తెలుగు", repository.getTitle(addon, "te")?.title)
        assertNull(repository.getTitle(addon, "fr"))
        assertNull(dao.getTitle(tmdb.stableKey, "en"))
        repository.linkVerifiedIdentities(imdb, tmdb)
        assertEquals(imdb, repository.resolveAlias(tmdb, IdentityNamespace.IMDB))
    }

    @Test fun seriesAliasPromotionMovesOnlyCompleteEpisodeSnapshotsToCanonicalRoot() = runBlocking {
        val tmdbSeries = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 4242))
        val imdbSeries = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt4242424"))
        val oldManifest = CanonicalEpisodeListEntity(tmdbSeries.stableKey, "en", "CINEMETA", 2, 100)
        val oldEpisodes = listOf(
            episode(tmdbSeries.stableKey, "en", "tt4242424:1:1", 1, 1, 0, 100),
            episode(tmdbSeries.stableKey, "en", "tt4242424:1:2", 1, 2, 1, 100)
        )
        dao.putEpisodeList(oldManifest)
        dao.putEpisodes(oldEpisodes)
        val emptyManifest = CanonicalEpisodeListEntity(tmdbSeries.stableKey, "fr", "CINEMETA", 0, 101)
        dao.putEpisodeList(emptyManifest)
        val incomplete = CanonicalEpisodeListEntity(imdbSeries.stableKey, "en", "CINEMETA", 3, 200)
        dao.putEpisodeList(incomplete)
        dao.putEpisodes(listOf(episode(imdbSeries.stableKey, "en", "partial", 1, 1, 0, 200)))

        repository.linkVerifiedIdentities(tmdbSeries, imdbSeries)

        val migrated = requireNotNull(repository.getSeriesSnapshot(imdbSeries, "EN"))
        assertEquals(imdbSeries.stableKey, migrated.manifest.seriesIdentityKey)
        assertEquals(2, migrated.manifest.episodeCount)
        assertEquals(listOf("tt4242424:1:1", "tt4242424:1:2"), migrated.episodes.map { it.id })
        assertEquals(listOf(0, 1), migrated.episodes.map { it.episodeOrder })
        assertEquals(0, requireNotNull(repository.getSeriesSnapshot(tmdbSeries, "fr")).manifest.episodeCount)
        assertNull(dao.getEpisodeList(tmdbSeries.stableKey, "en"))
        assertNull(dao.getEpisodeList(imdbSeries.stableKey, "en").takeIf { it?.episodeCount == 3 })
    }

    @Test fun idsRemainIsolatedAndCrossKindLinksAreRejected() = runBlocking {
        val series = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 42))
        val unknown = requireNotNull(ContentIdentity.addon("different", ContentKind.MOVIE, "42"))
        assertNull(repository.resolveAlias(tmdb, IdentityNamespace.IMDB))
        repository.linkVerifiedIdentities(tmdb, imdb)
        assertNull(repository.resolveAlias(series, IdentityNamespace.IMDB))
        assertNull(repository.resolveAlias(unknown, IdentityNamespace.IMDB))
        assertNull(repository.resolveAlias(tmdb, IdentityNamespace.ADDON))
        try {
            repository.linkVerifiedIdentities(tmdb, series)
            fail("Cross-kind aliases must not merge")
        } catch (_: IllegalArgumentException) { }
        assertNull(dao.getAlias(series.stableKey))
    }

    @Test fun rowBudgetCountsLocalesRatherThanOnlyUniqueTitleKeysAndAgesOutOldRows() = runBlocking {
        val now = System.currentTimeMillis()
        dao.putTitle(row(tmdb, "en", "Old").copy(lastFetchedAtMillis = now - 40L * 24 * 60 * 60 * 1_000))
        repository.putTitle(row(tmdb, "te", "Telugu"))
        assertNull(repository.getTitle(tmdb, "en"))
        val afterWrite = System.currentTimeMillis() + 1_000
        dao.putTitle(row(tmdb, "en", "English").copy(lastFetchedAtMillis = afterWrite))
        dao.putTitle(row(tmdb, "fr", "French").copy(lastFetchedAtMillis = afterWrite + 1))
        dao.retainNewestTitles(2)
        assertEquals(2, dao.getTitles(listOf(tmdb.stableKey)).size)
        assertNull(repository.getTitle(tmdb, "te"))
        assertNotNull(repository.getTitle(tmdb, "fr"))
        dao.deleteTitles(listOf(tmdb.stableKey))
        assertNull(repository.getTitle(tmdb, "fr"))
    }

    @Test fun detailCreditsHaveTheirOwnSmallBudgetWithoutEvictingCanonicalFacts() = runBlocking {
        val older = row(tmdb, "en", "Older details").copy(
            ageRating = "PG", ageRatingRegion = "US", ageRatingSource = "TMDB", ageRatingObservedAtMillis = 10,
            creditsJson = "[]", creditsSource = "TMDB", creditsObservedAtMillis = 10
        )
        val newerIdentity = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 43))
        val newer = row(newerIdentity, "en", "Newer details").copy(
            creditsJson = "[]", creditsSource = "TMDB", creditsObservedAtMillis = 20
        )
        repository.putTitle(older)
        repository.putTitle(newer)

        assertEquals(1, dao.retainNewestTitleDetails(1))

        val evicted = requireNotNull(repository.getTitle(tmdb, "en"))
        val retained = requireNotNull(repository.getTitle(newerIdentity, "en"))
        assertEquals("Older details", evicted.title)
        assertNull(evicted.creditsJson)
        assertNull(evicted.creditsSource)
        assertEquals(0L, evicted.creditsObservedAtMillis)
        assertEquals("PG", evicted.ageRating)
        assertEquals("Newer details", retained.title)
        assertEquals("[]", retained.creditsJson)
    }

    @Test fun tmdbIdConversionsSurviveServiceRestartWithoutNetworkRequests() = runBlocking {
        var requests = 0
        val api = Proxy.newProxyInstance(TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)) { _, method, _ ->
            requests++
            error("Unexpected request ${method.name}")
        } as TmdbApiService
        val first = TmdbService(RuntimeEnvironment.getApplication(), api, repository)
        first.preCacheMapping(imdb.externalId, 42, "movie")
        val restarted = TmdbService(RuntimeEnvironment.getApplication(), api, repository)
        assertEquals(imdb.externalId, restarted.tmdbToImdb(42, "movie"))
        restarted.clearCache()
        assertEquals(42, restarted.imdbToTmdb(imdb.externalId, "movie"))
        assertEquals(0, requests)
    }

    private fun row(identity: ContentIdentity, locale: String, title: String) = CanonicalTitleEntity(
        identity.stableKey, locale, identity.kind.keyPart, title = title, titleSource = "TMDB",
        titleLanguage = locale, lastFetchedAtMillis = System.currentTimeMillis()
    )

    private fun episode(
        seriesKey: String,
        locale: String,
        id: String,
        season: Int,
        number: Int,
        order: Int,
        observedAt: Long
    ) = CanonicalEpisodeEntity(
        seriesIdentityKey = seriesKey,
        locale = locale,
        episodeKey = id,
        id = id,
        title = "Episode $number",
        released = null,
        thumbnail = null,
        overview = null,
        season = season,
        episode = number,
        episodeOrder = order,
        observedAtMillis = observedAt
    )
}
