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
import androidx.compose.ui.window.Dialog
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.details.FilterDropdown
import com.saab.tv.ui.settings.AUDIO_LANGUAGE_OPTIONS
import com.saab.tv.ui.settings.LanguagePickerContent

@Composable
private fun SetupStep(title: String, description: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge, color = Color.LightGray)
        Spacer(Modifier.height(28.dp))
        content()
        Spacer(Modifier.height(24.dp))
        VoidButton(text = "Back", onClick = onBack, modifier = Modifier.width(180.dp))
    }
}

@Composable
internal fun ProfileSetupChoiceStep(profiles: List<ProfileEntity>, onCopy: (Int) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val requester = remember { FocusRequester() }
    var selectedId by remember(profiles) { mutableIntStateOf(profiles.firstOrNull()?.id ?: 0) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    SetupStep("Set Up Your Profile", "Copy All Settings And Addons, Or Set Up Manually. Your Theme Stays Yours.", onBack) {
        FilterDropdown(currentValue = profiles.firstOrNull { it.id == selectedId }?.let { "${it.name} · ${it.id}" }.orEmpty(),
            options = profiles.map { "${it.name} · ${it.id}" },
            modifier = Modifier.width(380.dp).focusRequester(requester),
            onSelect = { label -> profiles.firstOrNull { "${it.name} · ${it.id}" == label }?.let { selectedId = it.id } })
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VoidButton(text = "Copy Profile", onClick = { onCopy(selectedId) }, modifier = Modifier.width(230.dp))
            VoidButton(text = "Set Up Manually", onClick = onManual, modifier = Modifier.width(230.dp))
        }
    }
}

@Composable
internal fun ProfileTvStep(onSelect: (Boolean) -> Unit, onBack: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    SetupStep("Is Your TV 4K?", "This Sets Tunneled Playback And The Initial Format Filters.", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            VoidButton(text = "Yes, 4K TV", onClick = { onSelect(true) }, focusRequester = requester, modifier = Modifier.width(240.dp))
            VoidButton(text = "No, Not 4K", onClick = { onSelect(false) }, modifier = Modifier.width(240.dp))
        }
    }
}

@Composable
internal fun ProfileLanguageStep(priority: Int, languages: List<String>, onSelect: (String) -> Unit, onBack: () -> Unit) {
    val options = remember(priority, languages) { AUDIO_LANGUAGE_OPTIONS.filter { it.second.isNotBlank() && it.second !in languages.take(priority) } }
    var code by remember(priority) { mutableStateOf(languages[priority]) }
    var showPicker by remember(priority) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val pickerRequester = remember { FocusRequester() }
    LaunchedEffect(priority) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    val ordinal = listOf("First", "Second", "Third")[priority]
    SetupStep("$ordinal Language Preference", "Step ${priority + 1} Of 3 · Used To Rank Streams In Order Of Preference.", onBack) {
        VoidButton(text = options.firstOrNull { it.second == code }?.first.orEmpty(),
            onClick = { showPicker = true }, modifier = Modifier.width(380.dp), focusRequester = requester)
        Spacer(Modifier.height(20.dp))
        VoidButton(text = if (priority == 2) "Create Profile" else "Continue", onClick = { onSelect(code) }, modifier = Modifier.width(260.dp))
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
