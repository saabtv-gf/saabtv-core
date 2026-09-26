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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.saab.tv.remote_input.AccountPairingServer
import com.saab.tv.data.account.AccountAuthManager
import com.saab.tv.ui.components.SetupButton
import kotlinx.coroutines.*

@Composable
internal fun AccountRemoteDialog(auth: AccountAuthManager, signup: Boolean, onDismiss: () -> Unit,
    onSubmit: (String, String, Boolean) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
        AccountPairingServer(signup) { username, password, _ ->
            pending = username to password
        }
    }
    DisposableEffect(server) { onDispose { pending = null; server.close(); qr?.recycle() } }
    LaunchedEffect(pending) {
        usernameAvailable = !signup
        if (pending != null) {
            withContext(Dispatchers.IO) { server.close() }
            if (signup) {
                checking = true
                try {
                    usernameAvailable = auth.usernameAvailable(pending!!.first)
                    if (!usernameAvailable) error = "This username is already taken. Cancel and try a different username."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Could not check username availability. Please try again using the TV form." }
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
            if (pending == null) { ready = false; error = "This QR code has expired. Close and reopen to try again." }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Secure phone sign-in could not start. Check your local network or use the TV form." }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (signup) "Create Account With Your Phone" else "Sign In With Your Phone", style = MaterialTheme.typography.headlineSmall)
                val received = pending
                if (received != null) {
                    Text("Continue as ${received.first}?", style = MaterialTheme.typography.titleLarge)
                    Text("Only confirm if you sent these details from your phone. The phone connection is now closed.")
                    if (checking) Text("Checking username availability…")
                    SetupButton(if (signup) "Create Account" else "Sign In", {
                        pending = null
                        onDismiss()
                        onSubmit(received.first, received.second, signup)
                    }, primary = true, enabled = usernameAvailable && !checking, modifier = Modifier.fillMaxWidth())
                } else if (ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        qr?.let { Image(it.asImageBitmap(), "Secure account sign-in QR code", Modifier.size(210.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("1. Connect your phone to the same Wi-Fi.\n2. Scan the QR code.\n3. Before trusting the temporary certificate, verify its SHA-256 fingerprint against the value below.\n4. Enter your details and confirm on the TV.")
                            Text("If your browser cannot show the certificate fingerprint, cancel and use the TV form.", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(server.url, style = MaterialTheme.typography.bodySmall)
                    Text("Certificate SHA-256\n${server.fingerprint}", style = MaterialTheme.typography.bodySmall)
                    Text("Private HTTPS link · expires in 5 minutes · no password logging", style = MaterialTheme.typography.bodySmall)
                } else if (error == null) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SetupButton("Cancel", { pending = null; onDismiss() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
