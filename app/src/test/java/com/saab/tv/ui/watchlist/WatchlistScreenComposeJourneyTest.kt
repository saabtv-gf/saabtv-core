package com.saab.tv.ui.watchlist

import android.app.Application
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class WatchlistScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: WatchlistViewModel
    private lateinit var actionsViewModel: com.saab.tv.ui.home.HomeViewModel

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true))
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-watchlist-screen", "movie", "Watchlist Movie", null, 1))
        viewModel = WatchlistViewModel(fixture.dao, fixture.app.repository, fixture.sync)
        actionsViewModel = fixture.home()
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        actionsViewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun watchlistCardOpensItsExactTitleOnTheActiveProfile() {
        var openedId: String? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = { openedId = it.id },
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Watchlist Movie").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Watchlist Movie")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("tt-watchlist-screen", openedId) }
    }
}
