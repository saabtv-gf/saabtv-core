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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.nativeKeyCode
import com.saab.tv.ui.components.scrollToItemUsingSaabMotion
import com.saab.tv.ui.utils.requestFocusWhenAttached
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.saab.tv.data.player.PlaybackDiagnosticReport
import com.saab.tv.data.player.PlaybackDiagnostics
import com.saab.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlaybackDiagnosticsSettings(
    onGoBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstButtonRequester = remember { FocusRequester() }
    val exportRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val basicRequester = remember { FocusRequester() }
    val detailedRequester = remember { FocusRequester() }
    val firstEventRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var refreshToken by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<PlaybackDiagnosticReport?>(null) }
    var reportLoading by remember { mutableStateOf(true) }
    var reportLoadFailed by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var refreshFocused by remember { mutableStateOf(false) }
    var diagnosticsEnabled by remember {
        mutableStateOf(PlaybackDiagnostics.isEnabled(context))
    }
    var basicEnabled by remember { mutableStateOf(com.saab.tv.AppDiagnostics.isBasicEnabled(context)) }

    LaunchedEffect(refreshToken) {
        reportLoading = true
        reportLoadFailed = false
        try {
            report = withContext(Dispatchers.IO) { com.saab.tv.AppDiagnostics.report(context) }
            if (refreshToken > 0) status = context.getString(R.string.common_updated)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report = null
            reportLoadFailed = true
            status = context.getString(R.string.diagnostics_load_failed)
            com.saab.tv.AppDiagnostics.failure(context, "Diagnostics", "Report Load Failed", failure)
        } finally {
            reportLoading = false
        }
    }

    val currentReport = report
    // The header and log rows live in a lazy list. A directional FocusRequester
    // cannot point across that boundary: the off-screen target may be detached.
    fun focusListItem(index: Int, requester: FocusRequester) {
        scope.launch {
            try {
                listState.scrollToItem(index)
                requester.requestFocusWhenAttached(hasFocus = {
                    listState.layoutInfo.visibleItemsInfo.any { it.index == index } &&
                        (index != 0 || refreshFocused)
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: IllegalStateException) {
                com.saab.tv.AppDiagnostics.failure(context, "Diagnostics", "Focus Target Unavailable", failure)
            }
        }
    }
    val eventKeys = remember(currentReport?.events) {
        val occurrences = mutableMapOf<String, Int>()
        currentReport?.events.orEmpty().map { item ->
            val base = if (item.component == "Cloud Sync" && item.event == "Transfer Summary") "cloud-transfer-summary"
                else "${item.timestampMs}-${item.component}-${item.event}-${item.details.hashCode()}"
            val occurrence = occurrences.getOrDefault(base, 0)
            occurrences[base] = occurrence + 1
            "$base-$occurrence"
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("app-diagnostics-list"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
    ) {
        item(key = "diagnostics-header") {
            Column(modifier = Modifier.fillMaxWidth()) {
        val canFocusEvents = currentReport?.events?.isNotEmpty() == true
        val focusFirstEvent: (() -> Unit)? = if (canFocusEvents) {
            { focusListItem(1, firstEventRequester) }
        } else null
        Text(
            stringResource(R.string.settings_section_diagnostics),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp
            ),
            color = androidx.compose.ui.graphics.Color.White
        )
        Text(
            stringResource(R.string.diagnostics_privacy_summary),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        SettingToggleRow(
            label = stringResource(R.string.diagnostics_basic_logging),
            subtitle = stringResource(R.string.diagnostics_basic_description),
            isChecked = basicEnabled,
            onCheckedChange = { basicEnabled = it; com.saab.tv.AppDiagnostics.setBasicEnabled(context, it) },
            onBack = onGoBack, blockUp = true,
            modifier = Modifier.focusRequester(basicRequester).focusProperties { down = detailedRequester }
        )
        Spacer(Modifier.height(8.dp))
        SettingToggleRow(
            label = stringResource(R.string.diagnostics_detailed_logging),
            subtitle = if (diagnosticsEnabled) {
                stringResource(R.string.diagnostics_recording_enabled)
            } else {
                stringResource(R.string.diagnostics_detailed_description)
            },
            isChecked = diagnosticsEnabled,
            onCheckedChange = { enabled ->
                PlaybackDiagnostics.setEnabled(context, enabled)
                diagnosticsEnabled = enabled
                com.saab.tv.AppDiagnostics.event(context, "Diagnostics", "Logging Changed", "enabled=$enabled", true)
                status = if (enabled) {
                    context.getString(R.string.diagnostics_enabled_status)
                } else {
                    context.getString(R.string.diagnostics_disabled_status)
                }
            },
            onBack = onGoBack,
            modifier = Modifier.focusRequester(detailedRequester).focusProperties { up = basicRequester; down = firstButtonRequester }
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiagnosticsButton(
                label = stringResource(R.string.common_refresh),
                enabled = !actionBusy,
                modifier = Modifier
                    .focusRequester(firstButtonRequester)
                    .onFocusChanged { refreshFocused = it.isFocused }
                    .focusProperties { up = detailedRequester; right = exportRequester },
                onLeft = onGoBack,
                onDown = focusFirstEvent
            ) {
                status = context.getString(R.string.diagnostics_loading)
                refreshToken++
            }
            DiagnosticsButton(
                label = stringResource(R.string.diagnostics_export_report),
                enabled = !actionBusy,
                modifier = Modifier.focusRequester(exportRequester).focusProperties { left = firstButtonRequester; right = clearRequester; up = detailedRequester },
                onDown = focusFirstEvent
            ) {
                scope.launch {
                    if (actionBusy) return@launch
                    actionBusy = true
                    status = context.getString(R.string.diagnostics_export_preparing)
                    try {
                        val file = try {
                            withContext(Dispatchers.IO) { com.saab.tv.AppDiagnostics.export(context) }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            com.saab.tv.AppDiagnostics.failure(context, "Diagnostics", "Export Create Failed", failure)
                            null
                        }
                        if (file == null) {
                            status = context.getString(R.string.diagnostics_export_create_failed)
                        } else {
                            try {
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
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.diagnostics_export_chooser))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                status = context.getString(R.string.diagnostics_export_ready)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                com.saab.tv.AppDiagnostics.failure(context, "Diagnostics", "Export Share Failed", failure)
                                status = context.getString(R.string.diagnostics_export_share_failed)
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        com.saab.tv.AppDiagnostics.failure(context, "Diagnostics", "Export Failed", failure)
                        status = context.getString(R.string.diagnostics_export_create_failed)
                    } finally {
                        actionBusy = false
                    }
                }
            }
            DiagnosticsButton(
                label = stringResource(R.string.diagnostics_clear_logs),
                enabled = !actionBusy,
                modifier = Modifier.focusRequester(clearRequester).focusProperties { left = exportRequester; up = detailedRequester },
                onDown = focusFirstEvent
            ) {
                scope.launch {
                    if (actionBusy) return@launch
                    actionBusy = true
                    status = context.getString(R.string.diagnostics_clearing)
                    try {
                        withContext(Dispatchers.IO) { com.saab.tv.AppDiagnostics.clear(context) }
                        report = null
                        reportLoadFailed = false
                        status = context.getString(R.string.diagnostics_cleared_status)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        com.saab.tv.AppDiagnostics.failure(context, "Diagnostics", "Clear Failed", failure)
                        status = context.getString(R.string.diagnostics_clear_failed)
                    } finally {
                        actionBusy = false
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Spacer(Modifier.height(16.dp))

        if (reportLoading && currentReport == null) {
            Text(
                stringResource(R.string.diagnostics_loading),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (reportLoadFailed) {
            Text(
                stringResource(R.string.diagnostics_load_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (currentReport == null) {
            Text(
                if (diagnosticsEnabled) {
                    stringResource(R.string.diagnostics_no_events)
                } else {
                    stringResource(R.string.diagnostics_off_empty)
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
                stringResource(
                    R.string.diagnostics_report_summary,
                    PlaybackDiagnostics.formatTimestamp(currentReport.startedAtMs),
                    currentReport.events.size
                ),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 10.dp)
            )

        }
            }
        }

        if (currentReport != null) {
            itemsIndexed(
                items = currentReport.events,
                key = { index, _ -> eventKeys[index] }
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
                        val itemIndex = index + 1
                        val layout = listState.layoutInfo
                        val visibleItem = layout.visibleItemsInfo.firstOrNull { it.index == itemIndex }
                        if (shouldScrollDiagnosticItemIntoViewport(
                                itemOffset = visibleItem?.offset,
                                itemSize = visibleItem?.size,
                                viewportStart = layout.viewportStartOffset,
                                viewportEnd = layout.viewportEndOffset
                            )
                        ) {
                            scope.launch { listState.scrollToItemUsingSaabMotion(itemIndex) }
                        }
                    },
                    onLeft = onGoBack,
                    onUp = if (index == 0) ({ focusListItem(0, firstButtonRequester) }) else null
                )
            }
        }
    }
}

internal fun shouldScrollDiagnosticItemIntoViewport(
    itemOffset: Int?,
    itemSize: Int?,
    viewportStart: Int,
    viewportEnd: Int
): Boolean {
    if (itemOffset == null || itemSize == null || itemSize <= 0) return true
    return itemOffset < viewportStart || itemOffset + itemSize > viewportEnd
}

@Composable
private fun DiagnosticsButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLeft: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    com.saab.tv.ui.components.SetupButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        compact = true,
        modifier = modifier
            .onPreviewKeyEvent {
                if (onDown != null && it.key == Key.DirectionDown) {
                    if (it.type == KeyEventType.KeyDown) onDown()
                    true
                } else if (onLeft != null && it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                    onLeft()
                    true
                } else {
                    false
                }
            }
    )
}

@Composable
private fun DiagnosticEventCard(
    item: com.saab.tv.data.player.PlaybackDiagnosticEvent,
    index: Int,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onLeft: () -> Unit,
    onUp: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val context = LocalContext.current
    var expanded by remember(item.timestampMs, item.component, item.event, item.details.hashCode()) {
        mutableStateOf(false)
    }
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent {
                if (isDiagnosticActivationKey(it.key.nativeKeyCode)) {
                    // Keep focus on the row while making long stack traces explicitly expandable.
                    if (it.type == KeyEventType.KeyDown && item.details.isNotBlank()) expanded = !expanded
                    true
                } else if (onUp != null && it.key == Key.DirectionUp) {
                    if (it.type == KeyEventType.KeyDown) onUp()
                    true
                } else if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                    onLeft()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) accent
                else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.045f)
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                if (item.details.isNotBlank()) {
                    role = Role.Button
                    stateDescription = if (expanded) {
                        context.getString(R.string.diagnostics_details_expanded)
                    } else {
                        context.getString(R.string.diagnostics_details_collapsed)
                    }
                    onClick(label = context.getString(
                        if (expanded) R.string.diagnostics_details_expanded
                        else R.string.diagnostics_details_collapsed
                    )) {
                        expanded = !expanded
                        true
                    }
                }
                contentDescription = buildString {
                    append("${index + 1}. ${PlaybackDiagnostics.formatTimestamp(item.timestampMs)}. ")
                    append("${item.component}: ${item.event}.")
                    if (item.details.isNotBlank()) {
                        append(" ${item.details.take(if (expanded) 2_000 else 240)}")
                    }
                }
            }
            .testTag("diagnostic-event-$index")
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#${index + 1}",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                PlaybackDiagnostics.formatTimestamp(item.timestampMs).substringAfter(' '),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.component,
                color = if (focused) androidx.compose.ui.graphics.Color.White else accent,
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
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 10,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
