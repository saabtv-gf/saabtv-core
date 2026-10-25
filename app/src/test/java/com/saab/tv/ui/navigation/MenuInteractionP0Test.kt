package com.saab.tv.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.saab.tv.ui.components.SetupButton
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class MenuInteractionP0Test {
    @get:Rule val compose=createComposeRule()
    private val requesters=NavDestination.entries.associateWith { FocusRequester() }
    private val content=FocusRequester()
    private val selected=mutableStateOf(NavDestination.Home)
    private val actions=mutableListOf<NavDestination>()
    private var returns=0
    private fun show(hidden:Boolean=false, consumeUp:Boolean=false) {
        compose.setContent { MaterialTheme { NavDrawer(selected.value,null,requesters,
            onNavigate={actions+=it;selected.value=it;content.requestFocus()},onClose={returns++;content.requestFocus()},hideNavigation=hidden) {
            Box(Modifier.padding(start=250.dp,top=50.dp)) {
                SetupButton("Content",{},focusRequester=content,modifier=Modifier
                    .focusProperties {left=requesters.getValue(NavDestination.Home)}
                    .then(if (consumeUp) Modifier.onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                    } else Modifier))
            }
        } } }
        compose.runOnIdle { content.requestFocus() }
    }
    private fun enter() { compose.onNodeWithText("Content").performKeyInput {pressKey(Key.DirectionLeft)} }
    @Test fun leftEntersDrawerAndDownEnterSelectsMovies() {
        show();enter()
        compose.onNodeWithText("Home").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithText("Movies").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {assertEquals(listOf(NavDestination.Movies),actions)}
        compose.onNodeWithText("Content").assertIsFocused()
    }
    @Test fun repeatedUpDownRemainsNavigableAndEnterSelectsExpectedTab() {
        show();enter()
        compose.onNodeWithText("Home").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithText("Movies").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithText("Series").performKeyInput {pressKey(Key.DirectionUp)}
        compose.onNodeWithText("Movies").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {assertEquals(NavDestination.Movies,actions.single())}
    }
    @Test fun automaticMenuFocusIsReturnedToContentWithoutNavigationAction() {
        // Home stays in the collapsed drawer's focus tree, so this simulates an
        // unsolicited framework focus restore. Settings is now deliberately
        // non-focusable while collapsed and cannot exercise the recovery guard.
        show();compose.runOnIdle {requesters.getValue(NavDestination.Home).requestFocus()}
        compose.waitForIdle()
        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle {assertTrue(actions.isEmpty());assertTrue(returns>0)}
    }
    @Test fun hiddenDrawerCannotStealFocusFromContent() {
        show(true);compose.runOnIdle {requesters.getValue(NavDestination.Home).requestFocus()}
        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle {assertTrue(actions.isEmpty())}
    }

    @Test fun collapsedProfileSettingsAndExitAreNotFocusable() {
        show()

        compose.runOnIdle {
            requesters.getValue(NavDestination.Profile).requestFocus()
            requesters.getValue(NavDestination.Settings).requestFocus()
            requesters.getValue(NavDestination.Exit).requestFocus()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
        compose.onNodeWithTag("nav-profile-avatar").assertIsNotFocused()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun blockingNavigationClearsPreviouslyOwnedDrawerFocus() {
        val hidden = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = selected.value,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { actions += it },
                    onClose = { returns++; content.requestFocus() },
                    hideNavigation = hidden.value
                ) {
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(
                            "Content",
                            {},
                            focusRequester = content,
                            modifier = Modifier.focusProperties {
                                left = requesters.getValue(NavDestination.Home)
                            }
                        )
                    }
                }
            }
        }
        compose.runOnIdle { content.requestFocus() }
        enter()
        compose.onNodeWithText("Home").assertIsFocused()

        compose.runOnIdle { hidden.value = true }
        compose.waitForIdle()
        compose.runOnIdle { hidden.value = false }
        compose.waitForIdle()
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun rightClosesDrawerWithoutNavigationAndLeftCanReenter() {
        show(); enter()
        compose.onNodeWithText("Home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle { assertTrue(actions.isEmpty()); assertTrue(returns > 0) }

        enter()
        compose.onNodeWithText("Home").assertIsFocused()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun consumedContentDirectionCannotAuthorizeLaterDrawerFocus() {
        show(consumeUp = true)
        compose.onNodeWithText("Content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun consumedContentDirectionCannotAuthorizeLaterTopBarFocus() {
        val topRequesters = NavDestination.entries.associateWith { FocusRequester() }
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = topRequesters,
                    onNavigate = { actions += it },
                    onEnterContent = { returns++; content.requestFocus() }
                ) {
                    Box(Modifier.padding(top = 100.dp)) {
                        SetupButton(
                            "Content",
                            {},
                            focusRequester = content,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                            }
                        )
                    }
                }
            }
        }
        compose.runOnIdle { content.requestFocus() }
        compose.onNodeWithText("Content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { topRequesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun collapsedTopBarProfileCannotStealFocusFromContent() {
        val topRequesters = NavDestination.entries.associateWith { FocusRequester() }
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = topRequesters,
                    onNavigate = { actions += it },
                    onEnterContent = { returns++; content.requestFocus() }
                ) {
                    Box(Modifier.padding(top = 100.dp)) {
                        SetupButton("Content", {}, focusRequester = content)
                    }
                }
            }
        }
        compose.runOnIdle {
            content.requestFocus()
            topRequesters.getValue(NavDestination.Profile).requestFocus()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
        compose.onNodeWithTag("top-nav-profile-avatar").assertIsNotFocused()
    }

    @Test fun pendingAutomaticFocusRecoveryDoesNotUndoAuthorizedMenuHandoff() {
        var returns = 0
        lateinit var guard: NavigationFocusGuard
        compose.setContent {
            MaterialTheme {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                guard = androidx.compose.runtime.remember(scope) {
                    NavigationFocusGuard(scope, { false }, { returns++ }, Key.DirectionLeft)
                }
                Box(Modifier.fillMaxSize())
            }
        }

        compose.runOnIdle {
            assertFalse("unrequested restoration is rejected", guard.focus("drawer", focused = true))
            assertTrue("only a fresh matching D-pad press authorizes entry", guard.authorizeDirectEntry(leftDpadPress()))
            assertTrue("the D-pad handoff wins before deferred recovery", guard.focus("drawer", focused = true))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("deferred recovery must not steal accepted focus", 0, returns) }
    }

    @Test fun missingEventAndFailedFocusHandoffCannotAuthorizeRestoredMenuFocus() {
        var returns = 0
        lateinit var guard: NavigationFocusGuard
        compose.setContent {
            MaterialTheme {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                guard = androidx.compose.runtime.remember(scope) {
                    NavigationFocusGuard(scope, { false }, { returns++ }, Key.DirectionLeft)
                }
                Box(Modifier.fillMaxSize())
            }
        }

        compose.runOnIdle {
            assertFalse("a missing event is not user intent", guard.authorizeDirectEntry(null))
            assertFalse("an unsolicited restore remains rejected", guard.focus("drawer", focused = true))
            assertFalse("failed requester does not complete the handoff", guard.handoff(leftDpadPress()) { false })
            assertFalse("failed handoff must not leave a stale entry grant", guard.focus("drawer", focused = true))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("rejected focus is returned to content", returns > 0) }
    }

    @Test fun explicitBackHandoffCanReturnFocusToNavigation() {
        lateinit var guard: NavigationFocusGuard
        compose.setContent {
            MaterialTheme {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                guard = androidx.compose.runtime.remember(scope) {
                    NavigationFocusGuard(scope, { false }, {}, Key.DirectionLeft)
                }
                Box(Modifier.fillMaxSize())
            }
        }

        compose.runOnIdle {
            assertTrue("explicit Back should authorize the requested menu return", guard.handoffBack { true })
            assertTrue("the authorized drawer focus should be accepted", guard.focus("drawer", focused = true))
        }
    }

    @Test fun blockedOrFailedBackHandoffCannotLeaveStaleNavigationGrant() {
        var isBlocked = true
        lateinit var guard: NavigationFocusGuard
        compose.setContent {
            MaterialTheme {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                guard = androidx.compose.runtime.remember(scope) {
                    NavigationFocusGuard(scope, { isBlocked }, {}, Key.DirectionLeft)
                }
                Box(Modifier.fillMaxSize())
            }
        }

        compose.runOnIdle {
            assertFalse(guard.handoffBack { true })
            isBlocked = false
            assertFalse(guard.handoffBack { false })
            assertFalse(guard.focus("drawer", focused = true))
        }
    }

    private fun leftDpadPress() = androidx.compose.ui.input.key.KeyEvent(
        android.view.KeyEvent(
            0L,
            0L,
            android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            0
        )
    )
}
