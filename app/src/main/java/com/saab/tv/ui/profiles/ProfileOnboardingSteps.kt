package com.saab.tv.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.ProfileSetupLayout
import com.saab.tv.ui.settings.AUDIO_LANGUAGE_OPTIONS
import com.saab.tv.ui.settings.LanguagePickerContent

@Composable
internal fun ProfileSetupChoiceStep(profiles: List<ProfileEntity>, onCopy: (Int) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val requester = remember { FocusRequester() }
    var selectedId by remember(profiles) { mutableIntStateOf(profiles.firstOrNull()?.id ?: 0) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    ProfileSetupLayout("Make It Yours", "Copy settings, addons and integrations from a profile, or choose your own preferences. Your new theme stays unchanged.", "PROFILE SETUP · PREFERENCES", onBack) {
        Column(Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            profiles.forEachIndexed { index, profile ->
                SetupButton(text = if (selectedId == profile.id) "✓  ${profile.name}" else profile.name,
                    onClick = { selectedId = profile.id }, primary = selectedId == profile.id,
                    modifier = Modifier.fillMaxWidth(), focusRequester = if (index == 0) requester else null)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SetupButton(text = "Copy Settings", onClick = { onCopy(selectedId) }, primary = true, enabled = profiles.any { it.id == selectedId }, modifier = Modifier.weight(1f))
            SetupButton(text = "Set Up Manually", onClick = onManual, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ProfileLanguageStep(priority: Int, languages: List<String>, onSelect: (String) -> Unit, onBack: () -> Unit) {
    val options = remember(priority, languages) { AUDIO_LANGUAGE_OPTIONS.filter { it.second.isNotBlank() && it.second !in languages.take(priority) } }
    var code by remember(priority, languages) { mutableStateOf(languages[priority].takeIf { candidate -> options.any { it.second == candidate } } ?: options.first().second) }
    var showPicker by remember(priority) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val pickerRequester = remember { FocusRequester() }
    LaunchedEffect(priority) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    val ordinal = listOf("First", "Second", "Third")[priority]
    ProfileSetupLayout("$ordinal Language", "Streams in your first language rank ahead of your second and third choices. Each choice must be different.", "PROFILE SETUP · LANGUAGE ${priority + 1} OF 3", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { index ->
                Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(listOf("First", "Second", "Third")[index], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (index < priority) AUDIO_LANGUAGE_OPTIONS.firstOrNull { it.second == languages[index] }?.first.orEmpty() else if (index == priority) "Choose Below" else "Up Next",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        SetupButton(text = "${options.firstOrNull { it.second == code }?.first.orEmpty()}  ·  Change",
            onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth(), focusRequester = requester)
        SetupButton(text = if (priority == 2) "Create Profile" else "Continue", onClick = { onSelect(code) }, primary = true, modifier = Modifier.fillMaxWidth())
    }
    if (showPicker) Dialog(onDismissRequest = { showPicker = false }) {
        Box(Modifier.width(420.dp).height(380.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp)) {
            LanguagePickerContent(title = "$ordinal Language", options = options, selectedValue = code,
                focusRequester = pickerRequester,
                onSelect = { code = it; showPicker = false }, onDismiss = { showPicker = false })
        }
    }
}
