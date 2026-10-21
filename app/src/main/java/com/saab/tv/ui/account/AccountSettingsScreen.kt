package com.saab.tv.ui.account

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saab.tv.data.account.AccountAuthManager
import com.saab.tv.data.account.AccountSyncManager
import com.saab.tv.R
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.SetupHeader
import com.saab.tv.ui.addons.VoidDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

internal fun accountSyncStatusResource(status: String): Int? = when (status) {
    "Not synced yet" -> R.string.account_sync_not_yet
    "Checking cloud copy…" -> R.string.account_sync_checking_cloud
    "Cloud copy restored; reopening…" -> R.string.account_sync_restored_reopening
    "Syncing…" -> R.string.account_sync_in_progress
    "Synced" -> R.string.account_sync_synced
    else -> null // Keep actionable server errors intact for troubleshooting.
}

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(val auth: AccountAuthManager, val sync: AccountSyncManager) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = false
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
}

@Composable
fun AccountSettingsScreen(onBack: () -> Unit, viewModel: AccountSettingsViewModel = hiltViewModel()) {
    com.saab.tv.ui.navigation.AppBackHandler(priority = com.saab.tv.ui.navigation.AppBackPriority.Screen, onBack = onBack)
    val context = LocalContext.current
    val status by viewModel.sync.status.collectAsStateWithLifecycle()
    val statusResource = accountSyncStatusResource(status)
    val statusLabel = if (statusResource == null) status else stringResource(statusResource)
    var confirmation by remember { mutableStateOf<String?>(null) }
    val syncFocus = remember { FocusRequester() }
    LaunchedEffect(syncFocus) {
        withFrameNanos { }
        syncFocus.requestFocus()
    }
    Column(Modifier.fillMaxSize().focusGroup().onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) { onBack(); true } else false
    }.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SetupHeader(
            stringResource(R.string.account_title),
            stringResource(R.string.account_signed_in_as, viewModel.auth.username),
            "SAAB TV"
        )
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.account_cloud_backup), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(statusLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.account_sync_description),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SetupButton(text = if (viewModel.busy) stringResource(R.string.common_please_wait) else stringResource(R.string.account_sync_now), enabled = !viewModel.busy,
                onClick = { viewModel.run {
                    if (viewModel.sync.syncAndReconcile()) {
                        viewModel.sync.stop()
                        AccountRestart.restart(context as Activity)
                    }
                } }, primary = true, focusRequester = syncFocus,
                modifier = Modifier.widthIn(min = 200.dp))
            if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (viewModel.error) Text(stringResource(R.string.account_operation_failed), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.account_manage_backup), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        AccountAction(stringResource(R.string.account_restore_backup), stringResource(R.string.account_restore_description), !viewModel.busy) { confirmation = "cloud" }
        AccountAction(stringResource(R.string.account_use_local_data), stringResource(R.string.account_use_local_description), !viewModel.busy) { confirmation = "local" }
        AccountAction(stringResource(R.string.common_sign_out), stringResource(R.string.account_sign_out_description), !viewModel.busy, destructive = true) { confirmation = "logout" }
        Text(stringResource(R.string.account_recovery_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupButton(stringResource(R.string.common_back), onBack, modifier = Modifier.width(160.dp))
    }
    confirmation?.let { operation ->
        VoidDialog(onDismissRequest = { confirmation = null }, title = when (operation) {
            "cloud" -> stringResource(R.string.account_replace_local_title)
            "local" -> stringResource(R.string.account_replace_cloud_title)
            else -> stringResource(R.string.account_sign_out_title)
        }) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(operation) { withFrameNanos { }; cancelFocus.requestFocus() }
            Text(when (operation) {
                "cloud" -> stringResource(R.string.account_replace_local_confirm)
                "local" -> stringResource(R.string.account_replace_cloud_confirm)
                "logout-offline" -> stringResource(R.string.account_sign_out_offline_confirm)
                else -> stringResource(R.string.account_sign_out_confirm)
            })
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupButton(text = stringResource(R.string.common_cancel), onClick = { confirmation = null }, primary = true, focusRequester = cancelFocus, modifier = Modifier.fillMaxWidth())
                SetupButton(text = when(operation) {
                    "cloud" -> stringResource(R.string.account_restore_backup)
                    "local" -> stringResource(R.string.account_replace_cloud_backup)
                    "logout-offline" -> stringResource(R.string.account_sign_out_without_sync)
                    else -> stringResource(R.string.account_sync_and_sign_out)
                },
                    destructive = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    confirmation = null
                    viewModel.run {
                        when (operation) {
                            "cloud" -> { viewModel.sync.resolveConflict(true); AccountRestart.restart(context as Activity) }
                            "local" -> viewModel.sync.resolveConflict(false)
                            "logout-offline" -> { viewModel.sync.stop(); viewModel.auth.signOut(); AccountRestart.restart(context as Activity) }
                            else -> if (viewModel.sync.syncNow()) {
                                viewModel.sync.stop(); viewModel.auth.signOut(); AccountRestart.restart(context as Activity)
                            } else confirmation = "logout-offline"
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun AccountAction(title: String, description: String, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupButton(title, onClick, enabled = enabled, destructive = destructive, modifier = Modifier.widthIn(min = 220.dp))
    }
}
