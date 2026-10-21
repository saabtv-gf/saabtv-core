package com.saab.tv.ui.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.CatalogResponse
import com.saab.tv.R
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.testing.awaitAppState
import kotlinx.coroutines.cancel
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SearchScreenComposeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fixture: FeatureFixture
    private lateinit var searchViewModel: SearchViewModel
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var previewViewModel: com.saab.tv.ui.home.HomeViewModel

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 73, name = "Search", isActive = true))
        fixture.app.configuration.saveRuntimeState(73)
        fixture.api.catalogs = listOf(
            MetaItem("tt-search-movie", "movie", "Batman Movie"),
            MetaItem("tt-search-series", "series", "Batman Series")
        )
        savedStateHandle = SavedStateHandle()
        searchViewModel = SearchViewModel(fixture.app.repository, fixture.tmdbMeta, fixture.tmdb, savedStateHandle)
        previewViewModel = fixture.home()
    }

    @After fun tearDown() {
        searchViewModel.viewModelScope.cancel()
        previewViewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun typedQueryShowsMovieAndSeriesAndActivatesTheSelectedResult() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        val selected = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 1,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = { selected += it.id }
                )
            }
        }

        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithText("T").performClick()
        awaitAppState {
            searchViewModel.state.value.query == "bat" && !searchViewModel.state.value.isLoading
        }

        assertEquals(setOf("Batman Movie", "Batman Series"), searchViewModel.state.value.results.map { it.title }.toSet())
        compose.onNodeWithContentDescription("Batman Series").assertDoesNotExist()
        compose.onNodeWithContentDescription("Batman Movie").assertExists()
        compose.onNodeWithTag("search-filter-series").performClick()
        compose.onNodeWithContentDescription("Batman Series").assertExists()
        compose.onNodeWithContentDescription("Batman Series")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf("tt-search-series"), selected) }
    }

    @Test fun previousResultsStayVisibleButCannotBeOpenedDuringAQueryChange() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        val selected = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 2,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = { selected += it.id }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithText("T").performClick()
        awaitAppState { !searchViewModel.state.value.isLoading && searchViewModel.state.value.results.isNotEmpty() }
        compose.waitForIdle()

        compose.runOnIdle { searchViewModel.onQueryChange("another title") }
        compose.onNodeWithText("Updating results… Previous matches cannot be opened yet.").assertExists()
        compose.onNodeWithContentDescription("Batman Movie").performClick()

        compose.runOnIdle {
            assertEquals(emptyList<String>(), selected)
            assertEquals("bat", searchViewModel.state.value.resultsQuery)
            assertEquals("another title", searchViewModel.state.value.query)
            assertEquals(false, searchViewModel.state.value.resultsAreCurrent)
        }
    }

    @Test fun searchEmptyStateAndKeyboardUseLocalizedResources() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 3,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }

        val resources = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithText(resources.getString(R.string.search_heading)).assertExists()
        compose.onNodeWithText(resources.getString(R.string.search_prompt)).assertExists()
        compose.onNodeWithText(resources.getString(R.string.search_key_qr)).assertExists()
    }

    @Test fun keyboardActionUsesSharedTvFocusAndMinimumTarget() {
        var cleared = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    TvKeyboard(
                        onKeyPress = {}, onBackspace = {}, onSpace = {}, onClear = { cleared = true },
                        onOpenRemoteInput = {}, entryRequester = FocusRequester(),
                        drawerRequester = FocusRequester(), isTopNav = false, hasResults = false
                    )
                }
            }
        }

        val clearKey = compose.onNode(
            hasText(RuntimeEnvironment.getApplication().getString(R.string.search_key_clear)) and hasClickAction()
        )
        clearKey.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        clearKey.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test fun portraitResultsStayAboveTheKeyboardOnA720pTvLayout() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        searchViewModel.configureSearchProfile(profile.id)
        searchViewModel.beginSearchSession(31)
        searchViewModel.onQueryChange("bat")
        awaitSearchReady {
            searchViewModel.state.value.query == "bat" &&
                !searchViewModel.state.value.isLoading && searchViewModel.state.value.results.isNotEmpty()
        }

        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 31,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }

        val resources = RuntimeEnvironment.getApplication().resources
        val result = compose.onNodeWithContentDescription("Batman Movie")
        val keyboardAction = compose.onNodeWithText(resources.getString(R.string.search_key_clear))
        compose.runOnIdle {
            assertTrue(
                "the poster shelf must not extend into the keyboard action row: " +
                    "result=${result.fetchSemanticsNode().boundsInRoot}, " +
                    "keyboard=${keyboardAction.fetchSemanticsNode().boundsInRoot}",
                result.fetchSemanticsNode().boundsInRoot.bottom <= keyboardAction.fetchSemanticsNode().boundsInRoot.top
            )
        }
    }

    @Test fun recentSearchCanBeReusedAndClearedFromSearchScreen() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        searchViewModel.configureSearchProfile(73)
        searchViewModel.onQueryChange("bat")
        awaitSearchReady {
            searchViewModel.state.value.query == "bat" && !searchViewModel.state.value.isLoading &&
                searchViewModel.state.value.results.isNotEmpty()
        }
        searchViewModel.clearSearch()

        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 7,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }

        val resources = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithText(resources.getString(R.string.search_recent_searches)).assertExists()
        val recentQuery = compose.onNodeWithText("bat")
        val initialBounds = recentQuery.fetchSemanticsNode().boundsInRoot
        recentQuery.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        assertEquals("Recent-query focus must not resize the chip", initialBounds, recentQuery.fetchSemanticsNode().boundsInRoot)
        recentQuery.performClick()
        awaitSearchReady {
            searchViewModel.state.value.query == "bat" && !searchViewModel.state.value.isLoading
        }
        searchViewModel.clearSearch()
        compose.waitForIdle()
        compose.onNodeWithText(resources.getString(R.string.search_clear_recent)).performClick()
        compose.onNodeWithText(resources.getString(R.string.search_recent_searches)).assertDoesNotExist()
    }

    @Test fun partialProviderFailureIsVisibleWhileSuccessfulSearchResultsRemainUsable() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        val movieUrl = "https://v3-cinemeta.strem.io/catalog/movie/top/search=bat.json"
        val seriesUrl = "https://v3-cinemeta.strem.io/catalog/series/top/search=bat.json"
        fixture.api.catalogPages[movieUrl] = CatalogResponse(listOf(MetaItem("tt-bat", "movie", "Batman Movie")))
        fixture.api.catalogFailures += seriesUrl

        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 18,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithText("T").performClick()

        awaitAppState {
            val state = searchViewModel.state.value
            state.query == "bat" && !state.isLoading && state.movies.isNotEmpty() &&
                SearchViewModel.SearchProvider.CINEMETA in state.failedProviders
        }
        compose.onNodeWithContentDescription("Batman Movie").assertExists()
        compose.onNodeWithText(
            RuntimeEnvironment.getApplication().getString(R.string.search_source_unavailable)
        ).assertExists()

        fixture.api.catalogPages[seriesUrl] = CatalogResponse(
            listOf(MetaItem("tt-bat-series", "series", "Batman Series"))
        )
        fixture.api.catalogFailures.remove(seriesUrl)
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.search_retry)).performClick()
        awaitAppState {
            val state = searchViewModel.state.value
            state.query == "bat" && !state.isLoading && state.failedProviders.isEmpty() &&
                state.series.any { it.title == "Batman Series" }
        }
        compose.onNodeWithTag("search-filter-series").performClick()
        compose.onNodeWithContentDescription("Batman Series").assertExists()
    }

    @Test fun movieAndSeriesFiltersKeepTheSameSearchBatch() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        val focusedIds = mutableListOf<String?>()
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 41,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {},
                    onFocusedIdChange = { focusedIds += it }
                )
            }
        }
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithText("T").performClick()
        awaitAppState {
            searchViewModel.state.value.query == "bat" && !searchViewModel.state.value.isLoading &&
                searchViewModel.state.value.results.size == 2
        }
        val searchRequests = fixture.api.calls.count { "/search=bat.json" in it }

        compose.onNodeWithTag("search-filter-series").performClick()
        compose.onNodeWithContentDescription("Batman Series").assertExists()
        compose.onNodeWithContentDescription("Batman Movie").assertDoesNotExist()
        compose.onNodeWithContentDescription("Batman Series")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {
            assertEquals(
                "focused result identity must come from the visible filtered batch",
                searchViewModel.state.value.series.single { it.title == "Batman Series" }.presentationKey,
                focusedIds.lastOrNull()
            )
        }
        compose.onNodeWithTag("search-filter-movies").performClick()
        compose.onNodeWithContentDescription("Batman Movie").assertExists()
        compose.onNodeWithContentDescription("Batman Series").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(SearchMediaFilter.MOVIES, searchViewModel.state.value.mediaFilter)
            assertEquals(
                "filtering is local and should not repeat search requests",
                searchRequests,
                fixture.api.calls.count { "/search=bat.json" in it }
            )
        }
    }

    @Test fun explicitSearchActionSubmitsAVisibleTwoCharacterTitle() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 42,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }
        compose.onNodeWithText("I").performClick()
        compose.onNodeWithText("T").performClick()
        awaitAppState { searchViewModel.state.value.query == "it" && !searchViewModel.state.value.isLoading }
        assertTrue(searchViewModel.state.value.results.isEmpty())

        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.search_key_submit)).performClick()
        awaitAppState {
            searchViewModel.state.value.query == "it" && searchViewModel.state.value.hasSubmittedCurrentQuery &&
                !searchViewModel.state.value.isLoading
        }

        assertEquals(2, searchViewModel.state.value.results.size)
        compose.onNodeWithContentDescription("Batman Movie").assertExists()
    }

    @Test fun backFromSearchResultReturnsFocusToKeyboardBeforeLeavingSearch() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        val keyboardRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        searchViewModel.configureSearchProfile(profile.id)
        searchViewModel.beginSearchSession(12)
        searchViewModel.onQueryChange("bat")
        awaitSearchReady {
            searchViewModel.state.value.query == "bat" && !searchViewModel.state.value.isLoading &&
                searchViewModel.state.value.results.isNotEmpty()
        }

        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = keyboardRequester,
                    drawerRequester = drawerRequester,
                    searchSessionId = 12,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Batman Movie")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("Q").assertIsFocused()
    }

    @Test fun removedSavedResultRestoresFocusToFirstAvailableResult() {
        val profile = runBlocking { fixture.dao.getProfileById(73)!! }
        // Keep this search session intact when SearchScreen enters; the test models
        // returning from Details after the previously focused title was removed.
        searchViewModel.beginSearchSession(2)
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    searchSessionId = 2,
                    viewModel = searchViewModel,
                    previewViewModel = previewViewModel,
                    currentProfile = profile,
                    onMovieClick = {},
                    lastFocusedId = "legacy/movie/removed-result"
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithText("T").performClick()

        try {
            awaitAppState {
                val state = searchViewModel.state.value
                state.query == "bat" && !state.isLoading && state.results.isNotEmpty()
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Search did not settle: ${searchViewModel.state.value}; calls=${fixture.api.calls}", timeout)
        }

        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithContentDescription("Batman Movie").assertIsFocused()
            }.isSuccess
        }
    }

    private fun awaitSearchReady(predicate: () -> Boolean) {
        // Search deliberately debounces keystrokes; advance Robolectric's main looper
        // for direct ViewModel/recent-search actions before waiting on background work.
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        awaitAppState(predicate)
    }
}
