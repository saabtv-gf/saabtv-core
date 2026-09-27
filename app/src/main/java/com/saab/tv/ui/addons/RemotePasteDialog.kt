package com.saab.tv.ui.addons

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.saab.tv.remote_input.ServerInfo
import com.saab.tv.remote_input.ServerManager
import com.saab.tv.remote_input.RemoteInputMode
import kotlinx.coroutines.delay

/**
 * Dialog that displays a QR code for remote URL pasting.
 * Uses GitHub Pages and a short-lived encrypted relay; no local listener.
 */
@Composable
fun RemotePasteDialog(
    onDismissRequest: () -> Unit,
    onUrlReceived: (String) -> Unit,
    mode: RemoteInputMode = RemoteInputMode.URL,
    title: String = "Remote Paste",
    description: String = "Scan with your phone to paste a URL"
) {
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    val serverManager = remember { ServerManager() }
    com.saab.tv.remote_input.RemoteDialogLifecycle { serverManager.stopServer(); onDismissRequest() }
    val focusRequester = remember { FocusRequester() }

    // Start server when dialog opens
    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        
        val info = serverManager.startServer(mode) { receivedText ->
            onUrlReceived(receivedText)
            onDismissRequest()
        }
        
        if (info != null) {
            serverInfo = info
            qrBitmap = generateQrCode(info.url)
        } else {
            error = "Secure pairing unavailable. Check your internet connection and try again."
        }
    }

    LaunchedEffect(serverInfo) {
        if (serverInfo != null) {
            delay(5 * 60_000L)
            serverManager.stopServer()
            error = "Pairing expired. Close and reopen for a new QR code."
        }
    }

    // Stop server when dialog closes
    DisposableEffect(Unit) {
        onDispose {
            serverManager.stopServer()
        }
    }

    Dialog(onDismissRequest = {
        serverManager.stopServer()
        onDismissRequest()
    }) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(32.dp)
                .focusRequester(focusRequester)
                .focusable()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(32.dp))

                val currentError = error
                val currentQrBitmap = qrBitmap
                val currentServerInfo = serverInfo
                when {
                    currentError != null -> {
                        Text(
                            currentError,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    currentQrBitmap != null && currentServerInfo != null -> {
                        // QR Code
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .padding(3.dp)
                        ) {
                            Image(
                                bitmap = currentQrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // Manual URL
                        Text(
                            "Private QR · expires in 5 minutes",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            com.saab.tv.remote_input.CloudPairingSession.PAGE_URL,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    else -> {
                        // Loading
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Connecting securely…",
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * Generates a QR code bitmap for the given URL.
 */
private fun generateQrCode(url: String, size: Int = 512): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
        
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        
        bitmap
    } catch (e: Exception) {
        if (com.saab.tv.BuildConfig.DEBUG) android.util.Log.w("RemotePasteDialog", "QR generation error", e)
        null
    }
}
