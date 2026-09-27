package com.saab.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One focus target, generous text padding and a high-contrast D-pad focus state. */
@Composable
fun SetupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && enabled) 1.02f else 1f, tween(120), label = "setupButtonFocus")
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .heightIn(min = if (compact) 40.dp else 48.dp).scale(scale),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color.White else accent.copy(alpha = 0.25f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = when { focused -> accent; primary -> accent.copy(alpha = 0.15f); else -> MaterialTheme.colorScheme.surfaceVariant },
            contentColor = when { focused && destructive -> MaterialTheme.colorScheme.onError; focused -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        ),
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 20.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = androidx.compose.material3.LocalContentColor.current, textAlign = TextAlign.Center)
    }
}

@Composable
fun SetupHeader(title: String, description: String, stage: String) {
    if (stage.isNotBlank()) {
        Text(stage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
    }
    Text(title, color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(10.dp))
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp))
}

@Composable
fun ProfileSetupLayout(
    title: String,
    description: String,
    stage: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column { SetupHeader(title, description, stage) }
            content()
            SetupButton("Back", onBack, modifier = Modifier.width(160.dp))
        }
    }
}
