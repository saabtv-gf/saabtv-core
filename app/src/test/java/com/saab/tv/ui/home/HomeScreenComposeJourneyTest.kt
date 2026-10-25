package com.saab.tv.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.compose.material3.Text
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.CatalogResponse
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.HomeRow
import com.saab.tv.domain.DashboardTab
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.testing.awaitAppState
import com.saab.tv.ui.navigation.AppBackHandler
import com.saab.tv.ui.navigation.AppBackPriority
import com.saab.tv.ui.navigation.BackNavigationHost
import com.saab.tv.ui.navigation.BrowseReturnPolicy
import com.saab.tv.ui.navigation.ShellView
import com.saab.tv.ui.navigation.CatalogDetailsSelection
import com.saab.tv.ui.navigation.NavDestination
import com.saab.tv.ui.navigation.NavDrawer
import com.saab.tv.ui.navigation.NavigationFocusHandoffHost
import com.saab.tv.ui.navigation.toCatalogDetailsSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class HomeScreenComposeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun action(label: String) = compose.onNode(hasContentDescription(label, ignoreCase = true))
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: HomeViewModel
    private lateinit var profile: ProfileEntity

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        profile = ProfileEntity(id = 91, name = "Home Screen", isActive = true, tmdbEnabled = false, homeTabLayout = "simple")
        val addonUrl = "https://fixture.invalid"
        fixture.dao.insertProfile(profile)
        fixture.dao.insertAddon(AddonEntity(
            transportUrl = addonUrl, id = "fixture", name = "Fixture", version = "1",
            description = null, iconUrl = null, catalogsJson = """[{"id":"top","type":"movie","name":"Top"}]""",
            supportsMeta = true, supportsStream = true
        ))
        fixture.dao.saveCatalogConfig(CatalogConfigEntity(
            uniqueId = "fixture-movie-top", transportUrl = addonUrl, addonName = "Fixture",
            catalogType = "movie", catalogId = "top", showInHome = true
        ))
        viewModel = fixture.home()
    }

    @After fun tearDown() = runBlocking {
        viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        fixture.close()
    }

    @Test fun homeScreenLoadsCatalogAndOpensTheFocusedMovie() {
        val catalogUrl = "https://fixture.invalid/catalog/movie/top.json"
        fixture.api.catalogPages[catalogUrl] = CatalogResponse(listOf(MetaItem("tt-home-screen", "movie", "Home Journey")))
        viewModel.loadScreen("home", profile)
        awaitAppState { !viewModel.state.value.isLoading && viewModel.state.value.loadedScreen == "home" }

        var openedId: String? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    tab = DashboardTab.HOME,
                    entryRequester = entry,
                    drawerRequester = drawer,
                    viewModel = viewModel,
                    currentProfile = profile,
                    onMovieClick = { openedId = it.id }
                )
            }
        }

        compose.onNodeWithContentDescription("Home Journey").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("tt-home-screen", openedId) }
    }

    @Test fun ottRefreshUsesSharedFocusControlWithoutChangingItsBounds() {
        var refreshCount = 0
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                MaterialTheme {
                    OttRefreshButton(onClick = { refreshCount++ })
                }
            }
        }

        val refresh = compose.onNodeWithText("Refresh OTT")
        refresh.assertExists()
        val beforeFocus = refresh.fetchSemanticsNode().boundsInRoot
        refresh.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val afterFocus = refresh.fetchSemanticsNode().boundsInRoot

        assertEquals(beforeFocus.width, afterFocus.width, 0.1f)
        assertEquals(beforeFocus.height, afterFocus.height, 0.1f)
        refresh.performClick()
        compose.runOnIdle { assertEquals(1, refreshCount) }
    }

    @Test fun homeDetailsBackJourneyRestoresTheSameFocusedTitle() =
        assertDetailsBackJourney("tt-home-return", "simple", "poster", gridEnabled = false)

    @Test fun tmdbDetailsBackRestoresTheSamePosterAndDpadNavigation() =
        assertDetailsBackJourney("tmdb:123456", "simple", "poster", gridEnabled = true)

    @Test fun tmdbDetailsBackRestoresTheSameLandscapeCardInCinematicLayout() =
        assertDetailsBackJourney("tmdb:123456", "cinematic", "landscape", gridEnabled = true)

    private fun assertDetailsBackJourney(targetId: String, layout: String, shape: String, gridEnabled: Boolean) {
        profile = profile.copy(homeTabLayout = layout, titleCardShape = shape)
        runBlocking {
            fixture.dao.saveCatalogConfig(CatalogConfigEntity(
                uniqueId = "fixture-movie-top", transportUrl = "https://fixture.invalid", addonName = "Fixture",
                catalogType = "movie", catalogId = "top", showInHome = true,
                isInfiniteLoopEnabled = gridEnabled, isInfiniteScrollingEnabled = false
            ))
        }
        val target = MetaItem(id = targetId, type = "movie", name = "Return Journey")
        val catalogUrl = "https://fixture.invalid/catalog/movie/top.json"
        fixture.api.catalogPages[catalogUrl] = CatalogResponse(listOf(
            MetaItem(id = "tt-home-return-first", type = "movie", name = "First Journey Title"),
            target
        ))
        viewModel.loadScreen("home", profile)
        awaitAppState { !viewModel.state.value.isLoading && viewModel.state.value.loadedScreen == "home" }

        val entry = FocusRequester()
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                MaterialTheme {
                    BackNavigationHost(onUnhandledBack = {}) {
                        var activeView by remember { mutableStateOf(ShellView.Menu) }
                        var browseFocusRestoreToken by remember { mutableIntStateOf(0) }
                        var selectedTitle by remember { mutableStateOf<CatalogDetailsSelection?>(null) }
                        if (activeView == ShellView.Menu) {
                            val drawerRequesters = remember {
                                NavDestination.entries.associateWith { FocusRequester() }
                            }
                            NavigationFocusHandoffHost {
                                NavDrawer(
                                    currentDestination = NavDestination.Home,
                                    currentProfile = profile,
                                    drawerRequesters = drawerRequesters,
                                    onNavigate = {},
                                    onClose = { entry.requestFocus() }
                                ) {
                                    HomeScreen(
                                        tab = DashboardTab.HOME,
                                        browseFocusRestoreToken = browseFocusRestoreToken,
                                        entryRequester = entry,
                                        drawerRequester = drawerRequesters.getValue(NavDestination.Home),
                                        viewModel = viewModel,
                                        currentProfile = profile,
                                        onMovieClick = { title ->
                                            selectedTitle = title.toCatalogDetailsSelection()
                                            activeView = ShellView.Details
                                        }
                                    )
                                }
                            }
                        } else {
                            AppBackHandler(priority = AppBackPriority.Route) {
                                activeView = BrowseReturnPolicy.onBack(ShellView.Menu)
                                browseFocusRestoreToken++
                            }
                            Text("Details: ${selectedTitle?.title} · ${selectedTitle?.contentId}")
                        }
                    }
                }
            }
        }

        val card = compose.onNodeWithContentDescription("Return Journey")
        card.assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
        compose.onNodeWithContentDescription("First Journey Title").assertIsNotFocused()
        card
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Details: Return Journey · $targetId").assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.waitUntil(2_000) {
            runCatching {
                compose.onNodeWithContentDescription("Return Journey").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("Return Journey").assertExists().assertIsFocused()
        compose.runOnIdle {
            val loadedCard = viewModel.state.value.rows.single().items[1]
            assertEquals(homeCardFocusKey(0, "fixture-movie-top", loadedCard.focusIdentityKey, 1), viewModel.state.value.lastFocusedKey)
        }
        compose.onNodeWithTag("nav-profile-avatar").assertIsNotFocused()
        compose.onNodeWithContentDescription("Return Journey").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithContentDescription("First Journey Title").assertIsFocused()
    }

    @Test fun returningToOttPrefersTheFirstTitleCardOverItsRefreshAction() {
        val row = HomeRow(
            configId = "fixture:ott",
            title = "OTT titles",
            items = listOf(MetaItem("tt-ott-return", "movie", "OTT Return Title")),
            order = 0
        )
        val category = CategoryRow.fromHomeRow(row)
        val state = HomeViewModel.HomeState(
            rows = listOf(category),
            mixedRows = listOf(category),
            isLoading = false
        )
        val contentEntry = FocusRequester()
        val refreshEntry = FocusRequester()
        val drawer = FocusRequester()

        compose.setContent {
            MaterialTheme {
                SimpleLayout(
                    startPadding = androidx.compose.ui.unit.Dp(80f),
                    isTopNav = false,
                    state = state,
                    onMovieClick = {},
                    onContinueClick = {},
                    onMovieLongClick = { _, _, _ -> },
                    onViewMore = { _, _, _ -> },
                    onHubClick = {},
                    onLoadMore = {},
                    entryRequester = contentEntry,
                    drawerRequester = drawer,
                    lastFocusedKey = null,
                    rowScrollPositions = emptyMap(),
                    verticalScrollPosition = 0 to 0,
                    historyScrollAdjustment = 0,
                    onFocusChange = {},
                    onScrollPositionChange = { _, _ -> },
                    onVerticalScrollChange = {},
                    onHeroItemVisible = {},
                    topActionRequester = refreshEntry,
                    onRefreshOtt = {},
                    browseFocusRestoreToken = 1
                )
            }
        }

        compose.waitUntil(2_000) {
            runCatching { compose.onNodeWithContentDescription("OTT Return Title").assertIsFocused(); true }
                .getOrDefault(false)
        }
        compose.onNodeWithContentDescription("OTT Return Title").assertIsFocused()
        compose.onNodeWithText("Refresh OTT").assertIsNotFocused()
    }

    @Test fun personalizedOnlyRailRestoresFocusBeyondBothSavedViewports() {
        val rows = (0..7).map { row ->
            CategoryRow.fromHomeRow(HomeRow(
                configId = "tmdb-rail-$row", title = "Suggestions $row", order = row,
                items = (0..14).map { item -> MetaItem("tmdb:${1000 + row * 100 + item}", "movie", "Suggestion $row $item") },
                isInfiniteLoopEnabled = true, isInfiniteScrollingEnabled = false
            ))
        }
        val target = rows.last().items.last().asActionItem()
        val state = HomeViewModel.HomeState(mixedRows = rows, personalizedRows = rows, isLoading = false)
        compose.setContent {
            MaterialTheme {
                SimpleLayout(
                    startPadding = androidx.compose.ui.unit.Dp(80f), isTopNav = false, state = state,
                    onMovieClick = {}, onContinueClick = {}, onMovieLongClick = { _, _, _ -> },
                    onViewMore = { _, _, _ -> }, onHubClick = {}, onLoadMore = {},
                    entryRequester = remember { FocusRequester() }, drawerRequester = remember { FocusRequester() },
                    lastFocusedKey = homeCardFocusKey(7, rows.last().id, target.homeFocusIdentityKey(), 14),
                    rowScrollPositions = emptyMap(), verticalScrollPosition = 0 to 0, historyScrollAdjustment = 0,
                    onFocusChange = {}, onScrollPositionChange = { _, _ -> }, onVerticalScrollChange = {},
                    onHeroItemVisible = {}, browseFocusRestoreToken = 1
                )
            }
        }
        val restored = compose.onNodeWithContentDescription(target.name)
        compose.waitUntil(3_000) { runCatching { restored.assertIsFocused() }.isSuccess }
        restored.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithContentDescription("Suggestion 7 13").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(isFocused() and hasContentDescription("Suggestion 6 ", substring = true)).assertExists()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(isFocused() and hasContentDescription("Suggestion 7 ", substring = true)).assertExists()
    }

    @Test fun catalogDetailsSelectionKeepsSeriesIdentityArtworkAndAddonOrigin() {
        val selection = MetaItem(
            id = "tt-home-series", type = "series", name = "Fixture Series",
            poster = "/poster.jpg", background = "/backdrop.jpg", logo = "/logo.png",
            addonBaseUrl = "https://fixture.invalid"
        ).toCatalogDetailsSelection()

        assertEquals("tt-home-series", selection.contentId)
        assertEquals("series", selection.contentType)
        assertEquals("Fixture Series", selection.title)
        assertEquals("/poster.jpg", selection.poster)
        assertEquals("/backdrop.jpg", selection.background)
        assertEquals("/logo.png", selection.logo)
        assertEquals("https://fixture.invalid", selection.addonBaseUrl)
        assertEquals("tt-home-series", selection.playbackId)
        assertEquals("series", selection.playbackType)
        assertEquals("Fixture Series", selection.playbackTitle)
        assertEquals("/poster.jpg", selection.playbackPoster)
        assertEquals(ShellView.Menu, selection.returnDestination)
    }
}
