package com.saab.tv.ui.watchlist

import android.app.Application
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun action(label: String) = compose.onNode(hasContentDescription(label, ignoreCase = true))
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: WatchlistViewModel
    private lateinit var actionsViewModel: com.saab.tv.ui.home.HomeViewModel

    private fun SemanticsNodeInteraction.isDisplayedForWait(): Boolean =
        runCatching { assertIsDisplayed(); true }.getOrDefault(false)

    private fun awaitEnabledAction(label: String) {
        compose.waitUntil(3_000) {
            runCatching { action(label).assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false)
        }
    }

    private fun longPressFocusedCard(view: View) {
        val downTime = SystemClock.uptimeMillis()
        compose.runOnUiThread {
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, downTime, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0
            ))
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, downTime + 500, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 1
            ))
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, downTime + 520, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0
            ))
        }
    }

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true))
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-watchlist-screen", "movie", "Watchlist Movie", null, 1))
        viewModel = WatchlistViewModel(fixture.dao, fixture.app.repository, fixture.sync, fixture.userStateCommands)
        actionsViewModel = fixture.home()
    }

    @After fun tearDown() {
        // Stop lifecycle-aware Room collectors while the fixture database is still open.
        // Compose's rule disposes its host after @After methods, so closing the fixture
        // first can race an active watched-badge query on slower test variants.
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContentView(View(activity))
        }
        compose.waitForIdle()
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
            compose.onNodeWithContentDescription("Watchlist Movie").isDisplayedForWait()
        }
        compose.onNodeWithContentDescription("Watchlist Movie")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("tt-watchlist-screen", openedId) }
    }

    @Test fun libraryRestoresTheFocusedCardAfterReturningFromDetails() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-library-second", "movie", "Second Library Movie", null, 2))
        val entry = FocusRequester()
        val drawer = FocusRequester()
        val showLibrary = mutableStateOf(true)
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    if (showLibrary.value) {
                        WatchlistScreen(
                            currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                            entryRequester = entry,
                            drawerRequester = drawer,
                            onMovieClick = {},
                            viewModel = viewModel,
                            actionsViewModel = actionsViewModel
                        )
                    } else {
                        Text("Details")
                    }
                }
            }
        }

        val secondCard = compose.onNodeWithContentDescription("Second Library Movie")
        compose.waitUntil(5_000) { secondCard.isDisplayedForWait() }
        secondCard.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {
            assertEquals(
                MetaItem("tt-library-second", "movie", "Second Library Movie").canonicalPresentationKey(),
                viewModel.lastFocusedKey
            )
        }

        compose.runOnIdle { showLibrary.value = false }
        compose.onNodeWithText("Details").assertExists()
        compose.runOnIdle { showLibrary.value = true }
        compose.waitUntil(5_000) { secondCard.isDisplayedForWait() }
        compose.runOnUiThread { entry.requestFocus() }
        compose.waitUntil(3_000) {
            runCatching { secondCard.assertIsFocused(); true }.getOrDefault(false)
        }
    }

    @Test fun libraryDpadReachesTheFinalCardInPosterAndLandscapeLayouts() = runBlocking {
        (2..15).forEach { index ->
            fixture.dao.addToWatchlist(
                WatchlistEntity(81, "tt-library-edge-$index", "movie", "Library Edge $index", null, index.toLong())
            )
        }
        val profile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true)
        viewModel.setProfileId(81)
        compose.waitUntil(5_000) { viewModel.libraryItems.value.watchlist.size == 15 }
        val titles = viewModel.libraryItems.value.watchlist.map { it.asActionItem().name }
        val firstItem = viewModel.libraryItems.value.watchlist.first().asActionItem()
        viewModel.rememberFocusedCard(LIBRARY_WATCHLIST, firstItem.canonicalPresentationKey(), 0)
        val cardShape = mutableStateOf("poster")
        val entry = FocusRequester()
        val drawer = FocusRequester()

        compose.setContent {
            val shape = cardShape.value
            key(shape) {
                CompositionLocalProvider(LocalTitleCardShape provides shape) {
                    Material3Theme {
                        MaterialTheme {
                            WatchlistScreen(
                                currentProfile = profile,
                                entryRequester = entry,
                                drawerRequester = drawer,
                                onMovieClick = {},
                                viewModel = viewModel,
                                actionsViewModel = actionsViewModel
                            )
                        }
                    }
                }
            }
        }

        for (shape in listOf("poster", "landscape")) {
            if (cardShape.value != shape) {
                viewModel.rememberFocusedCard(
                    LIBRARY_WATCHLIST,
                    firstItem.canonicalPresentationKey(),
                    0
                )
                compose.runOnUiThread { cardShape.value = shape }
                compose.waitForIdle()
            }
            val first = compose.onNodeWithContentDescription(titles.first())
            compose.waitUntil(5_000) { first.isDisplayedForWait() }
            first.performSemanticsAction(SemanticsActions.RequestFocus)
            first.assertIsFocused()
            titles.drop(1).forEachIndexed { index, title ->
                compose.onNodeWithContentDescription(titles[index])
                    .performKeyInput { pressKey(Key.DirectionRight) }
                compose.waitUntil(2_000) {
                    runCatching { compose.onNodeWithContentDescription(title).assertIsFocused(); true }
                        .getOrDefault(false)
                }
            }
            compose.onNodeWithContentDescription(titles.last()).assertIsFocused()
        }
    }

    @Test fun libraryFiltersMoveByDpadAndKeepSelectionSeparateFromFocus() {
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        compose.onNodeWithText("My Library").assertExists()
        compose.onNodeWithText("All Titles").assertExists()

        val all = compose.onNodeWithTag("library-status-all")
        val watchlist = compose.onNodeWithTag("library-status-watchlist")
        all.performSemanticsAction(SemanticsActions.RequestFocus)
        all.assertIsFocused().assertIsSelected()

        all.performKeyInput { pressKey(Key.DirectionRight) }
        watchlist.assertIsFocused().assertIsNotSelected()

        watchlist.performKeyInput { pressKey(Key.DirectionCenter) }
        watchlist.assertIsSelected()
        all.assertIsNotSelected()
    }

    @Test fun myLibraryMediaFilterShowsOnlySeriesAndStatusFilterNarrowsTheRows() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-library-series", "series", "Library Series", null, 2))
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        compose.waitUntil(5_000) {
            compose.onNodeWithContentDescription("Library Series").isDisplayedForWait()
        }
        compose.onNodeWithTag("library-media-series").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Library Series").assertExists()
        compose.onNodeWithContentDescription("Watchlist Movie").assertDoesNotExist()

        compose.onNodeWithTag("library-status-watched").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No Series In This Section").assertExists()
        compose.onNodeWithContentDescription("Library Series").assertDoesNotExist()
    }

    @Test fun myLibrarySortMenuReordersVisibleWatchlistWithoutChangingMembership() {
        runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-library-zulu", "movie", "Zulu Library Movie", null, 3))
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-library-alpha", "movie", "Alpha Library Movie", null, 2))
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        compose.onNodeWithTag("library-status-watchlist").performClick()
        compose.waitUntil(5_000) {
            compose.onNodeWithContentDescription("Zulu Library Movie").isDisplayedForWait() &&
                compose.onNodeWithContentDescription("Alpha Library Movie").isDisplayedForWait()
        }
        val zulu = compose.onNodeWithContentDescription("Zulu Library Movie")
        val alpha = compose.onNodeWithContentDescription("Alpha Library Movie")
        compose.runOnIdle {
            assertTrue(zulu.fetchSemanticsNode().boundsInRoot.left < alpha.fetchSemanticsNode().boundsInRoot.left)
        }

        compose.onNodeWithTag("library-sort-button").performClick()
        compose.onNodeWithTag("library-sort-title").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(alpha.fetchSemanticsNode().boundsInRoot.left < zulu.fetchSemanticsNode().boundsInRoot.left)
        }
        compose.onNodeWithContentDescription("Zulu Library Movie").assertExists()
        compose.onNodeWithContentDescription("Alpha Library Movie").assertExists()
        }
    }

    @Test fun markingSavedTitleWatchedMovesItFromWatchlistToWatchedInMyLibrary() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(81, "tt-library-transition", "movie", "Library Transition", null, 2))
        var rootView: View? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        val card = compose.onNodeWithContentDescription("Library Transition")
        compose.waitUntil(10_000) {
            compose.onNodeWithContentDescription("Library Transition").isDisplayedForWait()
        }
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        longPressFocusedCard(requireNotNull(rootView))
        compose.waitUntil(3_000) {
            action("Mark As Watched").isDisplayedForWait()
        }
        action("Mark As Watched").performClick()
        compose.waitUntil(3_000) {
            runBlocking {
                fixture.dao.getHistoryItemForProfile(81, "tt-library-transition")?.watched == true &&
                    !fixture.dao.isInWatchlist(81, "tt-library-transition")
            }
        }
        try {
            compose.waitUntil(3_000) {
                viewModel.libraryItems.value.watched.any { it.asActionItem().id == "tt-library-transition" } &&
                    viewModel.libraryItems.value.watchlist.none { it.asActionItem().id == "tt-library-transition" }
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Library projection stayed stale: ${viewModel.libraryItems.value}", timeout)
        }
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("library-status-watched").isDisplayedForWait() &&
                compose.onNodeWithTag("library-status-watchlist").isDisplayedForWait()
        }
        compose.onAllNodesWithContentDescription("Library Transition").assertCountEquals(1)
        compose.onNodeWithContentDescription("Watchlist Movie").assertExists()
        compose.waitUntil(3_000) {
            runCatching { card.assertIsFocused(); true }.getOrDefault(false)
        }
        Unit
    }

    @Test fun removingLastWatchlistTitleRestoresFocusToEmptyLibraryState() = runBlocking {
        var rootView: View? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        val card = compose.onNodeWithContentDescription("Watchlist Movie")
        compose.waitUntil(5_000) { card.isDisplayedForWait() }
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        longPressFocusedCard(requireNotNull(rootView))
        compose.waitUntil(3_000) {
            action("Remove From Watchlist").isDisplayedForWait()
        }
        action("Remove From Watchlist").performClick()

        compose.waitUntil(3_000) {
            runBlocking { !fixture.dao.isInWatchlist(81, "tt-watchlist-screen") } &&
                viewModel.libraryItems.value.watchlist.isEmpty()
        }
        compose.waitUntil(3_000) {
            compose.onNodeWithText("Your Library Is Empty").isDisplayedForWait()
        }
        compose.waitUntil(3_000) {
            runCatching { compose.onNodeWithTag("library-empty-state").assertIsFocused(); true }
                .getOrDefault(false)
        }
    }

    @Test fun continueWatchingQuickActionResumesTheExactEpisode() = runBlocking {
        fixture.dao.insertSeriesNextUp(
            SeriesNextUpEntity(81, "tt-library-progress", "Progress Series", null, 2, 6, "Next", updatedAt = 20)
        )
        fixture.dao.insertHistory(
            WatchHistoryEntity(
                profileId = 81,
                id = "tt-library-progress:2:5",
                title = "Episode Five",
                poster = null,
                position = 1_230_000,
                duration = 2_700_000,
                lastWatched = 20,
                type = "series"
            )
        )
        var resumedPlaybackId: String? = null
        var rootView: View? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        onContinueClick = { resumedPlaybackId = it.resumePlaybackId },
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        val card = compose.onNodeWithContentDescription("Progress Series")
        compose.waitUntil(5_000) { card.isDisplayedForWait() }
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        longPressFocusedCard(requireNotNull(rootView))
        awaitEnabledAction("Resume")
        action("Resume").performClick()

        compose.runOnIdle { assertEquals("tt-library-progress:2:5", resumedPlaybackId) }
    }

    @Test fun continueWatchingClearProgressAsksBeforeRemovingTheResumeCard() = runBlocking {
        fixture.dao.insertHistory(
            WatchHistoryEntity(
                profileId = 81,
                id = "tt-library-progress-movie",
                title = "Progress Movie",
                poster = null,
                position = 1_000,
                duration = 10_000,
                lastWatched = 20,
                type = "movie"
            )
        )
        // This journey is about the destructive quick-action confirmation, not the
        // asynchronous first Room subscription. Prime the same projection that a
        // previously opened Library would already have before asserting card focus.
        viewModel.setProfileId(81)
        viewModel.refreshLibrary()
        assertTrue(
            "Continue Watching projection was not primed: ${viewModel.libraryItems.value}",
            viewModel.libraryItems.value.inProgress.any {
                it.asActionItem().id == "tt-library-progress-movie"
            }
        )
        var rootView: View? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        val card = compose.onNodeWithContentDescription("Progress Movie")
        compose.waitUntil(10_000) { card.isDisplayedForWait() }
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        longPressFocusedCard(requireNotNull(rootView))
        awaitEnabledAction("Clear Progress")
        action("Clear Progress").performClick()
        compose.onNodeWithText("Clear progress?").assertExists()
        awaitEnabledAction("Clear Progress")
        action("Clear Progress").performClick()

        compose.waitUntil(3_000) {
            runBlocking { fixture.dao.getHistoryItemForProfile(81, "tt-library-progress-movie") == null } &&
                viewModel.libraryItems.value.inProgress.isEmpty()
        }
        compose.onNodeWithContentDescription("Progress Movie").assertDoesNotExist()
    }

    @Test fun continueWatchingMarkWatchedRemovesWatchlistAndUpdatesLibraryImmediately() {
        runBlocking {
        fixture.dao.addToWatchlist(
            WatchlistEntity(81, "tt-library-progress-movie", "movie", "Progress Movie", null, 10)
        )
        fixture.dao.insertHistory(
            WatchHistoryEntity(
                profileId = 81,
                id = "tt-library-progress-movie",
                title = "Progress Movie",
                poster = null,
                position = 1_000,
                duration = 10_000,
                lastWatched = 20,
                type = "movie"
            )
        )
        // Load the seeded Room projection before composing the journey. The
        // screen also does this from LaunchedEffect, but waiting on that
        // lifecycle/Room startup made the first card intermittently miss the
        // test's visibility window under the full suite.
        viewModel.setProfileId(81)
        viewModel.refreshLibrary()
        var rootView: View? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            Material3Theme {
                MaterialTheme {
                    WatchlistScreen(
                        currentProfile = ProfileEntity(id = 81, name = "Watchlist Screen", isActive = true),
                        entryRequester = entry,
                        drawerRequester = drawer,
                        onMovieClick = {},
                        viewModel = viewModel,
                        actionsViewModel = actionsViewModel
                    )
                }
            }
        }

        val card = compose.onAllNodesWithContentDescription("Progress Movie").onFirst()
        compose.waitUntil(5_000) { card.isDisplayedForWait() }
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        longPressFocusedCard(requireNotNull(rootView))
        awaitEnabledAction("Mark As Watched")
        action("Mark As Watched").performClick()

        compose.waitUntil(3_000) {
            runBlocking {
                fixture.dao.getHistoryItemForProfile(81, "tt-library-progress-movie")?.watched == true &&
                    !fixture.dao.isInWatchlist(81, "tt-library-progress-movie")
            }
        }
        compose.waitUntil(3_000) {
            viewModel.libraryItems.value.inProgress.none { it.asActionItem().id == "tt-library-progress-movie" } &&
                viewModel.libraryItems.value.watched.any { it.asActionItem().id == "tt-library-progress-movie" } &&
                viewModel.libraryItems.value.watchlist.none { it.asActionItem().id == "tt-library-progress-movie" }
        }
        assertTrue(compose.onAllNodesWithContentDescription("Progress Movie")
            .fetchSemanticsNodes().isNotEmpty())
        }
    }
}
