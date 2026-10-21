package com.saab.tv.ui.account

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.saab.tv.remote_input.CloudPairingSession
import com.saab.tv.data.account.AccountCredentials
import com.saab.tv.data.account.AccountAuthManager
import com.saab.tv.R
import com.saab.tv.ui.components.SetupButton
import kotlinx.coroutines.*

internal fun accountRemoteStatusResource(status: String): Int = when (status) {
    "Connecting securely…" -> R.string.account_remote_connecting
    "Ready · QR expires in five minutes" -> R.string.account_remote_ready
    "Secure pairing unavailable. Check your internet connection and try again." -> R.string.account_remote_unavailable
    "Received securely" -> R.string.account_remote_received
    "Connection interrupted · retrying…" -> R.string.account_remote_retrying
    "Pairing closed. Reopen for a new QR code." -> R.string.account_remote_closed
    else -> R.string.account_remote_unavailable
}

@Composable
internal fun AccountRemoteDialog(auth: AccountAuthManager, signup: Boolean, onDismiss: () -> Unit,
    onSubmit: (String, String, Boolean) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var usernameAvailable by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dismiss() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val server = remember {
        CloudPairingSession(if (signup) "signup" else "signin") { message ->
            val username = message.get("username").asString
            val password = message.get("password").asString
            require(AccountCredentials.usernameError(username) == null)
            require(password.length in 1..128)
            if (signup) require(AccountCredentials.passwordError(password) == null)
            pending = username to password
        }
    }
    val connectionStatus by server.status.collectAsState()
    DisposableEffect(server) { onDispose { pending = null; server.close(); qr?.recycle() } }
    LaunchedEffect(pending) {
        usernameAvailable = !signup
        if (pending != null) {
            withContext(Dispatchers.IO) { server.close() }
            if (signup) {
                checking = true
                try {
                    usernameAvailable = auth.usernameAvailable(pending!!.first)
                    if (!usernameAvailable) error = R.string.account_remote_username_taken
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = R.string.account_remote_username_check_failed }
                finally { checking = false }
            }
        }
    }
    LaunchedEffect(server) {
        try {
            withContext(Dispatchers.IO) {
                try { server.open() } finally { if (!currentCoroutineContext().isActive) server.close() }
            }
            qr = withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(server.url, BarcodeFormat.QR_CODE, 384, 384)
                val pixels = IntArray(384 * 384) { i -> if (matrix[i % 384, i / 384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                Bitmap.createBitmap(pixels, 384, 384, Bitmap.Config.ARGB_8888)
            }
            ready = true
            delay(5 * 60_000L)
            if (pending == null) { ready = false; error = R.string.account_remote_qr_expired }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = R.string.account_remote_unavailable }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    stringResource(if (signup) R.string.account_create_with_phone else R.string.account_signin_with_phone),
                    style = MaterialTheme.typography.headlineSmall
                )
                val received = pending
                if (received != null) {
                    Text(stringResource(R.string.account_remote_continue_as, received.first), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.account_remote_confirmation_warning))
                    if (checking) Text(stringResource(R.string.account_remote_checking_username))
                    SetupButton(stringResource(if (signup) R.string.account_create_account else R.string.account_sign_in), {
                        pending = null
                        onDismiss()
                        onSubmit(received.first, received.second, signup)
                    }, primary = true, enabled = usernameAvailable && !checking, modifier = Modifier.fillMaxWidth())
                } else if (ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        qr?.let { Image(it.asImageBitmap(), stringResource(R.string.account_remote_qr_description), Modifier.size(210.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.account_remote_pairing_steps))
                            Text(stringResource(R.string.account_remote_privacy_note), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(CloudPairingSession.PAGE_URL, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.account_remote_expiry), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(accountRemoteStatusResource(connectionStatus)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else if (error == null) CircularProgressIndicator()
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                SetupButton(stringResource(R.string.common_cancel), { pending = null; onDismiss() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
