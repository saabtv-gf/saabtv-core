package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class DashboardEditorComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var vm: DashboardViewModel
    private var backCalls = 0

    @Before fun setUp() {
        app = OfflineAppFixture(RuntimeEnvironment.getApplication())
        vm = DashboardViewModel(app.dao, app.configuration)
        compose.setContent {
            MaterialTheme {
                DashboardEditorScreen(
                    onBack = { backCalls++ },
                    currentProfile = ProfileEntity(id = 77, name = "Editor"),
                    viewModel = vm
                )
            }
        }
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        app.close()
    }

    @Test fun emptyDashboardOffersAddFlowAndHomeTabBackAction() {
        compose.onNodeWithText("Home Screen Editor").assertExists()
        compose.onNodeWithText("No rows on Home").assertExists()
        compose.onNodeWithText("Home", substring = false)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(1, backCalls) }
    }
}
