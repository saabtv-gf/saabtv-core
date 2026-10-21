package com.saab.tv.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class BackNavigationDispatcherTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dispatchReturnsFalseWhenEmptyOrOnlyDisabled() {
        val dispatcher = BackNavigationDispatcher()
        var calls = 0
        assertFalse(dispatcher.dispatch())

        val id = dispatcher.register(AppBackPriority.Screen.rank, enabled = false) { calls++ }
        assertFalse(dispatcher.dispatch())
        assertEquals(0, calls)

        dispatcher.update(id, enabled = true) { calls += 10 }
        assertTrue(dispatcher.dispatch())
        assertEquals(10, calls)
    }

    @Test fun higherPriorityWinsAndMostRecentWinsAtSamePriority() {
        val dispatcher = BackNavigationDispatcher()
        val calls = mutableListOf<String>()
        dispatcher.register(AppBackPriority.Modal.rank, enabled = true) { calls += "modal-old" }
        dispatcher.register(AppBackPriority.Screen.rank, enabled = true) { calls += "screen" }
        dispatcher.register(AppBackPriority.Modal.rank, enabled = true) { calls += "modal-new" }

        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("modal-new"), calls)
    }

    @Test fun focusedNavigationOwnsBackBeforeRouteAndRouteResumesWhenNavigationCloses() {
        val navigationActive = mutableStateOf(true)
        var navigationCalls = 0
        var routeCalls = 0
        compose.setContent {
            BackNavigationHost(onUnhandledBack = {}) {
                AppBackHandler(priority = AppBackPriority.Route) { routeCalls++ }
                AppBackHandler(
                    enabled = navigationActive.value,
                    priority = AppBackPriority.Navigation
                ) { navigationCalls++ }
            }
        }

        pressBack()
        compose.runOnIdle {
            assertEquals(1, navigationCalls)
            assertEquals(0, routeCalls)
            navigationActive.value = false
        }
        compose.waitForIdle()

        pressBack()
        compose.runOnIdle {
            assertEquals(1, navigationCalls)
            assertEquals(1, routeCalls)
        }
    }

    @Test fun updatesReplaceCallbackAndDisableBeforeUnregister() {
        val dispatcher = BackNavigationDispatcher()
        val calls = mutableListOf<String>()
        val id = dispatcher.register(AppBackPriority.Route.rank, enabled = true) { calls += "old" }
        dispatcher.update(id, enabled = true) { calls += "new" }
        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("new"), calls)

        dispatcher.update(id, enabled = false) { calls += "disabled" }
        assertFalse(dispatcher.dispatch())
        dispatcher.update(Long.MIN_VALUE, enabled = true) { calls += "missing" }
        dispatcher.unregister(Long.MIN_VALUE)
        dispatcher.unregister(id)
        assertFalse(dispatcher.dispatch())
        assertEquals(listOf("new"), calls)
    }

    @Test fun keyBackDispatchesOnceAndConsumesTheFollowingSystemCallback() {
        val dispatcher = BackNavigationDispatcher()
        var handled = 0
        var fallback = 0
        dispatcher.register(AppBackPriority.Preview.rank, enabled = true) { handled++ }

        dispatcher.dispatchFromKey { fallback++ }
        dispatcher.dispatchFromSystem { fallback++ }
        assertEquals(1, handled)
        assertEquals(0, fallback)

        dispatcher.dispatchFromSystem { fallback++ }
        assertEquals(2, handled)

        dispatcher.unregister(1L)
        dispatcher.dispatchFromKey { fallback++ }
        assertEquals(1, fallback)
        dispatcher.dispatchFromSystem { fallback++ }
        assertEquals(1, fallback)
        dispatcher.dispatchFromSystem { fallback++ }
        assertEquals(2, fallback)
    }

    @Test fun duplicateSystemBackSuppressionExpiresBeforeAnUnrelatedBack() {
        var nowNanos = 1L
        val dispatcher = BackNavigationDispatcher(
            nowNanos = { nowNanos },
            duplicateBackWindowNanos = 10L
        )
        var handled = 0
        dispatcher.register(AppBackPriority.Screen.rank, enabled = true) { handled++ }

        dispatcher.dispatchFromKey {}
        assertEquals(1, handled)

        nowNanos = 12L
        dispatcher.dispatchFromSystem {}
        assertEquals(2, handled)
    }

    @Test fun hostRoutesModalThenScreenThenUnhandledBack() {
        var modalCalls = 0
        var screenCalls = 0
        var fallbackCalls = 0
        val modalOpen = mutableStateOf(true)
        val screenEnabled = mutableStateOf(true)

        compose.setContent {
            BackNavigationHost(onUnhandledBack = { fallbackCalls++ }) {
                AppBackHandler(enabled = screenEnabled.value, priority = AppBackPriority.Screen) {
                    screenCalls++
                }
                if (modalOpen.value) {
                    AppBackHandler(priority = AppBackPriority.Modal) {
                        modalCalls++
                        modalOpen.value = false
                    }
                }
            }
        }

        pressBack()
        compose.runOnIdle {
            assertEquals(1, modalCalls)
            assertEquals(0, screenCalls)
            assertEquals(0, fallbackCalls)
        }

        pressBack()
        compose.runOnIdle {
            assertEquals(1, screenCalls)
            assertEquals(0, fallbackCalls)
        }

        compose.runOnIdle { screenEnabled.value = false }
        compose.waitForIdle()
        pressBack()
        compose.runOnIdle {
            assertEquals(1, fallbackCalls)
        }
    }

    @Test fun standaloneBackHandlerRetainsAndroidXFallback() {
        var calls = 0
        compose.setContent { AppBackHandler { calls++ } }

        pressBack()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun standaloneKeyActionUsesItsLocalCallback() {
        var calls = 0
        compose.setContent {
            val dispatchBack = rememberAppBackKeyAction { calls++ }
            Box(Modifier.testTag("standalone-key").clickable { dispatchBack() })
        }

        compose.onNodeWithTag("standalone-key").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun disposingRegisteredRouteUnregistersItFromTheShell() {
        val routePresent = mutableStateOf(true)
        var routeCalls = 0
        var fallbackCalls = 0
        compose.setContent {
            BackNavigationHost(onUnhandledBack = { fallbackCalls++ }) {
                if (routePresent.value) {
                    AppBackHandler(priority = AppBackPriority.Route) { routeCalls++ }
                }
            }
        }

        pressBack()
        compose.runOnIdle { assertEquals(1, routeCalls) }
        compose.runOnIdle { routePresent.value = false }
        compose.waitForIdle()
        pressBack()
        compose.runOnIdle {
            assertEquals(1, routeCalls)
            assertEquals(1, fallbackCalls)
        }
    }

    @Test fun keyActionUsesTheActiveRouteAndConsumesItsSystemDuplicate() {
        var routeCalls = 0
        var standaloneCalls = 0
        var fallbackCalls = 0
        compose.setContent {
            BackNavigationHost(onUnhandledBack = { fallbackCalls++ }) {
                val dispatchBack = rememberAppBackKeyAction { standaloneCalls++ }
                AppBackHandler(priority = AppBackPriority.Preview) { routeCalls++ }
                Box(Modifier.testTag("key-route").clickable { dispatchBack() })
            }
        }

        compose.onNodeWithTag("key-route").performClick()
        pressBack()
        compose.runOnIdle {
            assertEquals(1, routeCalls)
            assertEquals(0, standaloneCalls)
            assertEquals(0, fallbackCalls)
        }
        pressBack()
        compose.runOnIdle { assertEquals(2, routeCalls) }
    }

    @Test fun keyActionUsesShellFallbackWhenNoChildRouteIsRegistered() {
        var standaloneCalls = 0
        var fallbackCalls = 0
        compose.setContent {
            BackNavigationHost(onUnhandledBack = { fallbackCalls++ }) {
                val dispatchBack = rememberAppBackKeyAction { standaloneCalls++ }
                Box(Modifier.testTag("key-fallback").clickable { dispatchBack() })
            }
        }

        compose.onNodeWithTag("key-fallback").performClick()
        pressBack()
        compose.runOnIdle {
            assertEquals(0, standaloneCalls)
            assertEquals(1, fallbackCalls)
        }
    }

    private fun pressBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }
}
