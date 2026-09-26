package com.saab.tv.ui.account

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saab.tv.data.account.AccountAuthManager
import com.saab.tv.data.account.AccountSyncManager
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.SetupHeader
import com.saab.tv.ui.addons.VoidDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(val auth: AccountAuthManager, val sync: AccountSyncManager) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Unable to complete account operation. Local data is safe. Try again." }
            finally { busy = false }
        }
    }
}

@Composable
fun AccountSettingsScreen(onBack: () -> Unit, viewModel: AccountSettingsViewModel = hiltViewModel()) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val status by viewModel.sync.status.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<String?>(null) }
    val syncFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; syncFocus.requestFocus() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SetupHeader("Your Account", "Signed in as ${viewModel.auth.username}", "SAAB TV")
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Encrypted Cloud Backup", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("Changes sync every 60 seconds while Saab TV is open. Profiles, settings, watchlists and playback progress are encrypted before upload.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SetupButton(text = if (viewModel.busy) "Please Wait…" else "Sync Now", enabled = !viewModel.busy,
                onClick = { viewModel.run { viewModel.sync.syncNow() } }, primary = true, focusRequester = syncFocus,
                modifier = Modifier.widthIn(min = 200.dp))
            if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Manage Your Backup", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        AccountAction("Restore Backup", "Replace this TV’s data with your cloud backup. Unsynced changes will be replaced.", !viewModel.busy) { confirmation = "cloud" }
        AccountAction("Use This TV’s Data", "Replace the cloud backup with this TV’s data. Use this to resolve a sync conflict.", !viewModel.busy) { confirmation = "local" }
        AccountAction("Sign Out", "Your account’s local data stays on this TV, separate from other accounts.", !viewModel.busy, destructive = true) { confirmation = "logout" }
        Text("Keep your password safe. Password recovery is not available yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupButton("Back", onBack, modifier = Modifier.width(160.dp))
    }
    confirmation?.let { operation ->
        VoidDialog(onDismissRequest = { confirmation = null }, title = when (operation) {
            "cloud" -> "Replace Local Data?"
            "local" -> "Replace Cloud Data?"
            else -> "Sign Out?"
        }) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(operation) { withFrameNanos { }; cancelFocus.requestFocus() }
            Text(when (operation) {
                "cloud" -> "Replace this account’s local data with the cloud backup. Unsynced local changes will be lost. Saab TV will restart."
                "local" -> "Use this TV’s data as the cloud copy. Changes made on other devices may be replaced."
                "logout-offline" -> "Sign out without uploading pending changes? They will remain on this TV under this account, but will not yet be available on other devices."
                else -> "Sync first, then sign out and restart Saab TV? If sync fails, you can choose to sign out without syncing."
            })
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupButton(text = "Cancel", onClick = { confirmation = null }, primary = true, focusRequester = cancelFocus, modifier = Modifier.fillMaxWidth())
                SetupButton(text = when(operation) { "cloud" -> "Restore Backup"; "local" -> "Replace Cloud Backup"; "logout-offline" -> "Sign Out Without Sync"; else -> "Sync And Sign Out" },
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
