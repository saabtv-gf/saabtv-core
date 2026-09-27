package com.saab.tv.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.domain.HubShape
import com.saab.tv.remote_input.HubServerManager
import com.saab.tv.ui.addons.VoidButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog to show QR code for Bulk Image Upload.
 * When an image is received, it triggers onImageReceived callback.
 * Unlike the single upload dialog, this one stays open until the user dismisses it,
 * allowing multiple uploads.
 */
@Composable
fun HubBulkUploadDialog(
    items: List<HubRowItemEntity>,
    shape: HubShape,
    onDismiss: () -> Unit,
    onImageReceived: (String, ByteArray) -> Unit, // callback(configUniqueId, bytes)
    onImageDeleted: ((String) -> Unit)? = null // callback(configUniqueId)
) {
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val focusRequester = remember { FocusRequester() }
    com.saab.tv.remote_input.RemoteDialogLifecycle { HubServerManager.stopServer(); onDismiss() }

    // Start Server
    LaunchedEffect(Unit) {
        // Start bulk server
        val url = HubServerManager.startBulkServer(
            items = items,
            shape = shape,
            onImageReceived = { id, bytes ->
                onImageReceived(id, bytes)
            },
            onImageDeleted = onImageDeleted
        )
        serverUrl = url
        if (url == null) error = "Secure pairing unavailable. Check your internet connection and try again."
        
        // Generate QR
        if (url != null) {
            withContext(Dispatchers.IO) {
                try {
                    val writer = QRCodeWriter()
                    val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 512, 512)
                    val width = bitMatrix.width
                    val height = bitMatrix.height
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                    for (x in 0 until width) {
                        for (y in 0 until height) {
                            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                    }
                    qrBitmap = bmp
                } catch (e: Exception) {
                    if (com.saab.tv.BuildConfig.DEBUG) android.util.Log.w("HubBulkUploadDialog", "QR generation error", e)
                }
            }
        }
    }

    LaunchedEffect(serverUrl) {
        if (serverUrl != null) {
            kotlinx.coroutines.delay(5 * 60_000L)
            HubServerManager.stopServer()
            qrBitmap = null
            error = "Pairing expired. Close and reopen for a new QR code."
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            HubServerManager.stopServer()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(32.dp)
                .focusRequester(focusRequester)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Bulk Manage Images",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                
                Text(
                    text = "Scan to open the web portal on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                val currentQrBitmap = qrBitmap
                val currentServerUrl = serverUrl
                if (currentQrBitmap != null) {
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

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    if (currentServerUrl != null) {
                        Text(
                            "Or visit:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            currentServerUrl,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(error ?: "Connecting securely…", color = Color.Gray)
                }

                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    "Press Back to Finish",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}
