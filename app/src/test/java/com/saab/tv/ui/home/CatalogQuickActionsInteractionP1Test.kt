package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class CatalogQuickActionsInteractionP1Test {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: HomeViewModel
    private val item = MetaItem("tt-quick-action", "movie", "Quick Action Film")
    private var dismissed = 0

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 21, name = "Quick Action Profile"))
        viewModel = fixture.home()
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.close()
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    CatalogQuickActionsPopup(
                        item = item, bounds = Rect(200f, 160f, 500f, 460f), profileId = 21,
                        onDismiss = { dismissed++ }, onTrailerClick = { _, _ -> }, viewModel = viewModel
                    )
                }
            }
        }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithContentDescription("Watch Trailer").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun watchlistedTitleOffersRemoveOnlyAndRemoteActionRemovesTheCorrectProfileRow() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(21, item.id, item.type, item.name, null, 1L))
        fixture.dao.addToWatchlist(WatchlistEntity(22, item.id, item.type, item.name, null, 1L))
        show()

        compose.onNodeWithContentDescription("Add To Watchlist").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("Remove From Watchlist").assertExists().performClick()
        compose.waitUntil(3_000) { !runBlocking { fixture.dao.isInWatchlist(21, item.id) } }
        assertTrue(fixture.dao.isInWatchlist(22, item.id))
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun unwatchlistedTitleOffersAddAndDoesNotStartTrailerWithoutSelection() = runBlocking {
        var trailerCalls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    CatalogQuickActionsPopup(
                        item = item, bounds = Rect(200f, 160f, 500f, 460f), profileId = 21,
                        onDismiss = { dismissed++ }, onTrailerClick = { _, _ -> trailerCalls++ }, viewModel = viewModel
                    )
                }
            }
        }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithContentDescription("Add To Watchlist").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(600)
        assertFalse(fixture.dao.isInWatchlist(21, item.id))
        compose.onNodeWithContentDescription("Add To Watchlist").assertExists()
        compose.runOnIdle { assertEquals(0, trailerCalls); assertEquals(0, dismissed) }
    }
}
