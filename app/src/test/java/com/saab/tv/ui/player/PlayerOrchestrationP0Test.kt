package com.saab.tv.ui.player

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.testing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlayerOrchestrationP0Test {
    private lateinit var f: FeatureFixture
    private lateinit var vm: PlayerViewModel
    @Before fun setup() = runBlocking {
        f = FeatureFixture(RuntimeEnvironment.getApplication())
        f.dao.insertProfile(ProfileEntity(id = 1, name = "One", isActive = true))
        f.dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        vm = f.player()
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); f.close() }
    private fun save(position: Long, duration: Long? = 1_000_000, boundary: Boolean = true,
                     established: Boolean = true, id: String = "tt1", type: String = "movie", profile: Int = 1) = runBlocking {
        vm.saveProgress(id, type, "Title", null, position, duration, boundary, established, profile).join()
    }
    @Test fun boundaryPositionsIncludingZeroPersistExactly() = runBlocking {
        listOf(0L, 1L, 4_999L, 5_000L).forEach { p -> save(p); assertEquals(p, f.dao.getHistoryItemForProfile(1, "tt1")?.position) }
    }
    @Test fun unpreparedBoundaryCannotOverwritePreviousResume() = runBlocking {
        save(50_000); save(0, established = false)
        assertEquals(50_000L, f.dao.getHistoryItemForProfile(1, "tt1")?.position)
    }
    @Test fun periodicSaveIgnoresEarlyPositionsButSavesFiveSeconds() = runBlocking {
        listOf(0L, 1L, 4_999L).forEach { save(it, boundary = false) }
        assertNull(f.dao.getHistoryItemForProfile(1, "tt1"))
        save(5_000, boundary = false); assertEquals(5_000L, f.dao.getHistoryItemForProfile(1, "tt1")?.position)
    }
    @Test fun negativePositionIsNormalizedAtBoundary() = runBlocking {
        save(-500); assertEquals(0L, f.dao.getHistoryItemForProfile(1, "tt1")?.position)
    }
    @Test fun trailerNeverCreatesWatchHistoryEvenOnCompletion() = runBlocking {
        save(999_000, id = "trailer_tt1")
        vm.markCompleted("trailer_tt1", "movie", "Trailer", null, 999_000, 1_000_000, 1).join()
        assertNull(f.dao.getHistoryItemForProfile(1, "trailer_tt1"))
    }
    @Test fun watchedThresholdRequiresBothThreeHundredSecondsAndNinetyFivePercent() = runBlocking {
        save(299_000, duration = 300_000); assertFalse(f.dao.getHistoryItemForProfile(1, "tt1")!!.watched)
        save(949_999); assertFalse(f.dao.getHistoryItemForProfile(1, "tt1")!!.watched)
        save(950_000); assertTrue(f.dao.getHistoryItemForProfile(1, "tt1")!!.watched)
    }
    @Test fun watchedFlagStaysTrueAfterLaterSmallPositionSave() = runBlocking {
        save(950_000); save(0); assertTrue(f.dao.getHistoryItemForProfile(1, "tt1")!!.watched)
    }
    @Test fun unknownDurationPreservesKnownDurationAndArtwork() = runBlocking {
        f.dao.insertHistory(WatchHistoryEntity(profileId = 1, id = "tt1", title = "Title", poster = "poster",
            background = "background", logo = "logo", position = 20_000, duration = 1_000_000, type = "movie", lastWatched = 1))
        save(30_000, duration = null)
        val h = f.dao.getHistoryItemForProfile(1, "tt1")!!
        assertEquals(1_000_000L, h.duration); assertEquals("poster", h.poster)
        assertEquals("background", h.background); assertEquals("logo", h.logo)
    }
    @Test fun futureDatedHistoryIsNotOverwrittenByStaleLocalWrite() = runBlocking {
        f.dao.insertHistory(WatchHistoryEntity(profileId = 1, id = "tt1", title = "Newer", poster = null, position = 70_000,
            duration = 1_000_000, type = "movie", lastWatched = System.currentTimeMillis() + 60_000))
        save(0); assertEquals(70_000L, f.dao.getHistoryItemForProfile(1, "tt1")!!.position)
    }
    @Test fun progressAndWatchlistRemovalAreScopedToProfile() = runBlocking {
        listOf(1, 2).forEach { f.dao.addToWatchlist(WatchlistEntity(profileId = it, id = "tt1", type = "movie", title = "Title", poster = null, addedAt = 1)) }
        save(950_000)
        assertFalse(f.dao.isInWatchlist(1, "tt1")); assertTrue(f.dao.isInWatchlist(2, "tt1"))
        assertNull(f.dao.getHistoryItemForProfile(2, "tt1"))
    }
    @Test fun differentEpisodesKeepSeparateResumeRecords() = runBlocking {
        save(900_000, id = "tt1:1:1", type = "series")
        save(0, id = "tt1:1:2", type = "series")
        assertEquals(900_000L, f.dao.getHistoryItemForProfile(1, "tt1:1:1")!!.position)
        assertEquals(0L, f.dao.getHistoryItemForProfile(1, "tt1:1:2")!!.position)
    }
    @Test fun concurrentDifferentTitlesDoNotDropWrites() = runBlocking {
        val jobs = (1..20).map { vm.saveProgress("tt$it", "movie", "Title", null, 10_000, 1_000_000, true, true, 1) }
        jobs.joinAll()
        (1..20).forEach { assertEquals(10_000L, f.dao.getHistoryItemForProfile(1, "tt$it")?.position) }
    }
    @Test fun localBoundaryWriteSurvivesViewModelCancellation() = runBlocking {
        val job = vm.saveProgress("tt1", "movie", "Title", null, 42_000, 1_000_000, true, true, 1)
        vm.viewModelScope.cancel(); job.join()
        assertEquals(42_000L, f.dao.getHistoryItemForProfile(1, "tt1")?.position)
    }
    @Test fun completingMovieClearsItsThumbnailsButNotOtherProfile() = runBlocking {
        for (profile in listOf(1,2)) {
            assertTrue(vm.storeSeekThumbnail(profile, "tt1", 0, 30, Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)))
        }
        vm.markCompleted("tt1", "movie", "Title", null, 1_000_000, 1_000_000, 1).join()
        assertNull(vm.loadSeekThumbnail(1, "tt1", 0, 30))
        val other = vm.loadSeekThumbnail(2, "tt1", 0, 30)
        assertNotNull(other); other?.recycle()
        Unit
    }
}
