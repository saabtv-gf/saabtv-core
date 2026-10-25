package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SavedListItemRestorationComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingAddressKeepsTheViewport() = assertViewport(null, 0)
    @Test fun negativeAddressKeepsTheViewport() = assertViewport(-1, 0)
    @Test fun removedTargetKeepsTheViewport() = assertViewport(20, 0)
    @Test fun visibleTargetKeepsTheViewport() = assertViewport(1, 0)
    @Test fun offscreenTargetRestoresOnceButLaterFocusDoesNotResetScroll() = assertViewport(10, 10)

    private fun assertViewport(target: Int?, expected: Int) {
        val state = LazyListState()
        val address = mutableStateOf(target)
        compose.setContent {
            RestoreSavedListItem(state, address.value)
            LazyColumn(Modifier.height(120.dp), state = state) {
                items(15) { Box(Modifier.height(40.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(expected, state.firstVisibleItemIndex)
            address.value = 5
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expected, state.firstVisibleItemIndex) }
    }

    @Test fun restorationWaitsForItemsToArrive() {
        val state = LazyListState()
        val count = mutableStateOf(0)
        compose.setContent {
            RestoreSavedListItem(state, 10)
            LazyColumn(Modifier.height(120.dp), state = state) {
                items(count.value) { Box(Modifier.height(40.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            count.value = 15
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(10, state.firstVisibleItemIndex) }
    }
}
