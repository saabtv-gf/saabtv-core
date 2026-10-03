package com.saab.tv.data.trakt

import android.app.Application
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TraktSyncManagerOfflineGuardTest {
    private lateinit var fixture: FeatureFixture

    @After fun tearDown() = fixture.close()

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 101, name = "Offline", isActive = true))
        fixture.app.configuration.saveRuntimeState(101)
    }

    @Test fun activityPollWithoutCredentialsDoesNotCallTheProvider() = runBlocking {
        assertFalse(fixture.traktSync.checkAndSync())
        assertTrue(fixture.api.calls.isEmpty())
    }

    @Test fun watchlistSyncWithoutCredentialsFailsClosedAndPreservesLocalEntries() = runBlocking {
        val local = WatchlistEntity(101, "tt-local", "movie", "Local Movie", null, 1)
        fixture.dao.addToWatchlist(local)

        val result = fixture.traktSync.syncWatchlist()

        assertTrue(result.isFailure)
        assertEquals(local, fixture.dao.getWatchlistItem(101, "tt-local"))
        assertTrue(fixture.api.calls.isEmpty())
    }
}
