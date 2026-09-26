package com.saab.tv.ui.account

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saab.tv.data.account.AccountAuthManager
import com.saab.tv.data.account.AccountSyncManager
import com.saab.tv.ui.addons.VoidButton
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        Text("Username: ${viewModel.auth.username}")
        Text("Cloud Sync: $status")
        Text("Profiles, themes, PINs, settings, addons, integrations, watchlists, watched titles and playback choices are encrypted before cloud backup.",
            style = MaterialTheme.typography.bodySmall)
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        VoidButton(text = "Sync Now", enabled = !viewModel.busy, onClick = { viewModel.run { viewModel.sync.syncNow() } }, isPrimary = true)
        VoidButton(text = "Restore Cloud Copy", enabled = !viewModel.busy, onClick = { confirmation = "cloud" })
        VoidButton(text = "Keep This TV’s Copy", enabled = !viewModel.busy, onClick = { confirmation = "local" })
        VoidButton(text = "Sign Out", enabled = !viewModel.busy, onClick = { confirmation = "logout" })
        Text("Sign-out keeps this account’s local data separate. A different account cannot read it. Password recovery is not available yet.",
            style = MaterialTheme.typography.bodySmall)
    }
    confirmation?.let { operation ->
        VoidDialog(onDismissRequest = { confirmation = null }, title = when (operation) {
            "cloud" -> "Replace Local Data?"
            "local" -> "Replace Cloud Data?"
            else -> "Sign Out?"
        }) {
            Text(when (operation) {
                "cloud" -> "Replace this account’s local data with the cloud backup. Unsynced local changes will be lost. Saab TV will restart."
                "local" -> "Use this TV’s data as the cloud copy. Changes made on other devices may be replaced."
                "logout-offline" -> "Sign out without uploading pending changes? They will remain on this TV under this account, but will not yet be available on other devices."
                else -> "Sync first, then sign out and restart Saab TV? If sync fails, you can choose to sign out without syncing."
            })
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VoidButton(text = "Cancel", onClick = { confirmation = null }, isPrimary = true)
                VoidButton(text = "Confirm", onClick = {
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
