package com.saab.tv.ui.account

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.viewModelScope
import com.saab.tv.R
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AccountSettingsComposeJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: AccountSettingsViewModel

    @Before fun setUp() {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        viewModel = AccountSettingsViewModel(fixture.auth, fixture.sync)
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun stableSyncStatesUseLocalizedLabelsWhileFailuresKeepTheirDetails() {
        assertEquals(R.string.account_sync_not_yet, accountSyncStatusResource("Not synced yet"))
        assertEquals(R.string.account_sync_checking_cloud, accountSyncStatusResource("Checking cloud copy…"))
        assertEquals(R.string.account_sync_restored_reopening, accountSyncStatusResource("Cloud copy restored; reopening…"))
        assertEquals(R.string.account_sync_in_progress, accountSyncStatusResource("Syncing…"))
        assertEquals(R.string.account_sync_synced, accountSyncStatusResource("Synced"))
        assertNull(accountSyncStatusResource("Cloud rejected request (HTTP 429)"))
    }

    @Test fun accountSectionStartsOnSyncAndDpadCanReachBackupActions() {
        compose.setContent {
            MaterialTheme {
                AccountSettingsScreen(onBack = {}, viewModel = viewModel)
            }
        }

        val sync = compose.onNodeWithText("Sync Now")
        sync.assertIsFocused()
        sync.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Restore Backup").assertIsFocused()
    }

    @Test fun remotePairingStatusesMapToStableLocalizedCopy() {
        assertEquals(R.string.account_remote_connecting, accountRemoteStatusResource("Connecting securely…"))
        assertEquals(R.string.account_remote_ready, accountRemoteStatusResource("Ready · QR expires in five minutes"))
        assertEquals(R.string.account_remote_received, accountRemoteStatusResource("Received securely"))
        assertEquals(R.string.account_remote_retrying, accountRemoteStatusResource("Connection interrupted · retrying…"))
        assertEquals(R.string.account_remote_closed, accountRemoteStatusResource("Pairing closed. Reopen for a new QR code."))
        assertEquals(R.string.account_remote_unavailable, accountRemoteStatusResource("Pairing request failed (HTTP 429)"))
    }

    @Test fun remoteCanReachBackupActionsAndEachReplacementRequiresItsOwnConfirmation() {
        compose.setContent {
            MaterialTheme {
                AccountSettingsScreen(onBack = {}, viewModel = viewModel)
            }
        }

        val sync = compose.onNodeWithText("Sync Now")
        sync.performSemanticsAction(SemanticsActions.RequestFocus)
        sync.assertIsFocused()
        sync.performKeyInput { pressKey(Key.DirectionDown) }

        val restore = compose.onNodeWithText("Restore Backup")
        restore.assertIsFocused().performClick()
        compose.onNodeWithText("Replace Local Data?").assertExists()
        compose.onNodeWithText("Replace this account’s local data with the cloud backup. Unsynced local changes will be lost. Saab TV will restart.")
            .assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Replace Local Data?").assertDoesNotExist()

        compose.onNodeWithText("Use This TV’s Data").performScrollTo().performClick()
        compose.onNodeWithText("Replace Cloud Data?").assertExists()
        compose.onNodeWithText("Use this TV’s data as the cloud copy. Changes made on other devices may be replaced.")
            .assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Replace Cloud Data?").assertDoesNotExist()
    }

    @Test fun signOutExplainsPendingSyncBeforeOfferingAnyDestructiveAction() {
        compose.setContent {
            MaterialTheme {
                AccountSettingsScreen(onBack = {}, viewModel = viewModel)
            }
        }

        compose.onNodeWithText("Sign Out").performScrollTo().performClick()
        compose.onNodeWithText("Sign Out?").assertExists()
        compose.onNodeWithText("Sync first, then sign out and restart Saab TV? If sync fails, you can choose to sign out without syncing.")
            .assertExists()
        compose.onNodeWithText("Sync And Sign Out").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Sign Out?").assertDoesNotExist()
    }
}
