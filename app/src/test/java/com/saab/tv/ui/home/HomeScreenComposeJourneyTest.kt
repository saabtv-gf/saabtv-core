package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.CatalogResponse
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.DashboardTab
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.testing.awaitAppState
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
class HomeScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
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

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
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
}
