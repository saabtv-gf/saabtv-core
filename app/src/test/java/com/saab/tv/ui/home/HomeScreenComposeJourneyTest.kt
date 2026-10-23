package com.saab.tv.ui.home

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.compose.material3.Text
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.CatalogResponse
import com.saab.tv.data.model.stremio.MetaItem
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
import kotlinx.coroutines.withTimeout
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

    @Test fun watchedStateRefreshDoesNotStealFocusFromQuickAction() {
        val item = MetaItem("tt-home-focus-refresh", "movie", "Focus Refresh")
        fixture.api.catalogPages["https://fixture.invalid/catalog/movie/top.json"] = CatalogResponse(listOf(item))
        viewModel.loadScreen("home", profile)
        awaitAppState { !viewModel.state.value.isLoading && viewModel.state.value.loadedScreen == "home" }

        val entry = FocusRequester()
        val drawer = FocusRequester()
        var rootView: View? = null
        compose.setContent {
            rootView = LocalView.current
            MaterialTheme {
                HomeScreen(
                    tab = DashboardTab.HOME,
                    entryRequester = entry,
                    drawerRequester = drawer,
                    viewModel = viewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }

        compose.onNodeWithContentDescription(item.name).assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        val view = requireNotNull(rootView)
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, downTime, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0
            ))
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, downTime + 600, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 1
            ))
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, downTime + 650, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0
            ))
        }
        compose.waitUntil(5_000) {
            runCatching { action("Mark As Watched").assertIsEnabled() }.isSuccess
        }
        val markWatched = action("Mark As Watched")
        markWatched.performSemanticsAction(SemanticsActions.RequestFocus)
        markWatched.assertIsFocused()

        val watchedMutation = viewModel.markTitleWatched(profile.id, item)

        compose.waitUntil(3_000) {
            runCatching {
                action("Mark As Unwatched").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        runBlocking {
            withTimeout(5_000) { watchedMutation.join() }
            assertTrue(fixture.dao.getHistoryItemForProfile(profile.id, item.id)?.watched == true)
        }
    }

    @Test fun homeDetailsBackJourneyRestoresTheSameFocusedTitle() {
        val catalogUrl = "https://fixture.invalid/catalog/movie/top.json"
        fixture.api.catalogPages[catalogUrl] = CatalogResponse(listOf(MetaItem(
            id = "tt-home-return", type = "movie", name = "Return Journey"
        )))
        viewModel.loadScreen("home", profile)
        awaitAppState { !viewModel.state.value.isLoading && viewModel.state.value.loadedScreen == "home" }

        val entry = FocusRequester()
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                MaterialTheme {
                    BackNavigationHost(onUnhandledBack = {}) {
                        var activeView by remember { mutableStateOf(ShellView.Menu) }
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
        card
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Details: Return Journey · tt-home-return").assertExists()

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
            assertTrue(viewModel.state.value.lastFocusedKey.orEmpty().contains("tt-home-return"))
        }
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
