package com.saab.tv.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.saab.tv.data.player.PlaybackDiagnosticReport
import com.saab.tv.data.player.PlaybackDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlaybackDiagnosticsSettings(onGoBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstButtonRequester = remember { FocusRequester() }
    val firstEventRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var refreshToken by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<PlaybackDiagnosticReport?>(null) }
    var status by remember { mutableStateOf("") }
    var diagnosticsEnabled by remember {
        mutableStateOf(PlaybackDiagnostics.isEnabled(context))
    }

    LaunchedEffect(refreshToken) {
        while (true) {
            report = withContext(Dispatchers.IO) { PlaybackDiagnostics.latestReport(context) }
            delay(2_000L)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val currentReport = report
        val canFocusEvents = currentReport?.events?.isNotEmpty() == true

        Text(
            "Playback Diagnostics",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp
            ),
            color = androidx.compose.ui.graphics.Color.White
        )
        Text(
            "Optional local technical information for playback, buffering and thumbnail generation.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        SettingToggleRow(
            label = "Diagnostic Logging",
            subtitle = if (diagnosticsEnabled) {
                "Recording is enabled. Disable it after troubleshooting for maximum performance."
            } else {
                "Off by default. Enable only when collecting a playback report."
            },
            isChecked = diagnosticsEnabled,
            onCheckedChange = { enabled ->
                PlaybackDiagnostics.setEnabled(context, enabled)
                diagnosticsEnabled = enabled
                status = if (enabled) {
                    "Diagnostic logging enabled for the next playback session."
                } else {
                    "Diagnostic logging disabled. Existing reports were kept."
                }
            },
            onBack = onGoBack,
            blockUp = true
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiagnosticsButton(
                label = "Refresh",
                modifier = Modifier.focusRequester(firstButtonRequester),
                onLeft = onGoBack,
                downRequester = firstEventRequester.takeIf { canFocusEvents }
            ) {
                refreshToken++
                status = "Updated"
            }
            DiagnosticsButton(
                label = "Export Report",
                onLeft = onGoBack,
                downRequester = firstEventRequester.takeIf { canFocusEvents }
            ) {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { PlaybackDiagnostics.exportLatest(context) }
                    if (file == null) {
                        status = "No playback report is available yet."
                    } else {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(intent, "Export Playback Diagnostics")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { status = "No compatible export app was found." }
                    }
                }
            }
            DiagnosticsButton(
                label = "Clear Logs",
                onLeft = onGoBack,
                downRequester = firstEventRequester.takeIf { canFocusEvents }
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) { PlaybackDiagnostics.clear(context) }
                    report = null
                    status = "Playback logs cleared."
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        if (currentReport == null) {
            Text(
                if (diagnosticsEnabled) {
                    "No playback session has been recorded yet. Start a video, then return here."
                } else {
                    "Diagnostic logging is off. Existing reports will remain available until cleared."
                },
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                currentReport.title,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Started ${PlaybackDiagnostics.formatTimestamp(currentReport.startedAtMs)}  •  " +
                    "${currentReport.events.size} recent events",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 10.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
            ) {
                itemsIndexed(
                    items = currentReport.events,
                    key = { index, item ->
                        "$index-${item.timestampMs}-${item.component}-${item.event}"
                    }
                ) { index, item ->
                    DiagnosticEventCard(
                        item = item,
                        index = index,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstEventRequester)
                        } else {
                            Modifier
                        },
                        onFocused = {
                            scope.launch { listState.animateScrollToItem(index) }
                        },
                        onLeft = onGoBack
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsButton(
    label: String,
    modifier: Modifier = Modifier,
    onLeft: () -> Unit,
    downRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        color = androidx.compose.ui.graphics.Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = modifier
            .then(
                if (downRequester != null) {
                    Modifier.focusProperties { down = downRequester }
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                    onLeft()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) accent.copy(alpha = 0.28f)
                else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 11.dp)
    )
}

@Composable
private fun DiagnosticEventCard(
    item: com.saab.tv.data.player.PlaybackDiagnosticEvent,
    index: Int,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onLeft: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                    onLeft()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) accent.copy(alpha = 0.18f)
                else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.045f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#${index + 1}",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.38f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                PlaybackDiagnostics.formatTimestamp(item.timestampMs).substringAfter(' '),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.48f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.component,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                item.event,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
        }
        if (item.details.isNotBlank()) {
            Text(
                item.details,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.67f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
