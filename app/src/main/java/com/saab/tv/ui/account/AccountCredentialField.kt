package com.saab.tv.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/** Focus highlights the field. OK explicitly enables editing; focus alone cannot open the IME. */
@Composable
internal fun AccountCredentialField(
    value: String, onValueChange: (String) -> Unit, label: String,
    focusRequester: FocusRequester, enabled: Boolean,
    password: Boolean = false, visible: Boolean = false,
    onToggleVisibility: () -> Unit = {}, isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    onNext: (() -> Unit)? = null, onDone: () -> Unit = {}
) {
    var editing by remember { mutableStateOf(false) }
    var eyeFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing, enabled) {
        if (editing && enabled) { withFrameNanos { }; keyboard?.show() }
        else if (!enabled) { editing = false; keyboard?.hide() }
    }
    Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = value, onValueChange = onValueChange,
            placeholder = { Text(label) }, enabled = enabled, singleLine = true,
            readOnly = !editing, isError = isError,
            modifier = Modifier.weight(1f).semantics { contentDescription = label }.border(if (fieldFocused) 3.dp else 0.dp,
                if (fieldFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp)).onFocusChanged {
                fieldFocused = it.isFocused
                if (!it.isFocused) { editing = false }
            }.focusRequester(focusRequester).onPreviewKeyEvent {
                if (enabled && !editing && it.key == Key.DirectionDown && onNext != null) {
                    if (it.type == KeyEventType.KeyDown) onNext()
                    true
                } else if (enabled && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (it.type == KeyEventType.KeyDown) editing = true
                    true
                } else false
            }.pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (enabled) editing = true
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onNext = { editing = false; keyboard?.hide(); onNext?.invoke() },
                onDone = { editing = false; keyboard?.hide(); onDone() }))
        if (password) OutlinedIconButton(onClick = onToggleVisibility, enabled = enabled,
            modifier = Modifier.padding(top = 4.dp).size(48.dp).onFocusChanged { eyeFocused = it.isFocused },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(if (eyeFocused) 3.dp else 1.dp, if (eyeFocused) Color.White else MaterialTheme.colorScheme.outline),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = if (eyeFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (eyeFocused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                if (visible) "Hide $label" else "Show $label", modifier = Modifier.size(22.dp))
        }
      }
      if (supportingText != null) Box(Modifier.padding(start = 8.dp, top = 2.dp)) {
          CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
              supportingText()
          }
      }
    }
}
