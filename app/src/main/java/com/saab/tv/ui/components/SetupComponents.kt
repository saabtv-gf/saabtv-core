package com.saab.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.saab.tv.R
import com.saab.tv.ui.theme.SaabVisualTokens

internal fun setupButtonTextColor(
    enabled: Boolean,
    focused: Boolean,
    destructive: Boolean,
    onPrimary: Color,
    onError: Color,
    onSurfaceVariant: Color
): Color = when {
    !enabled -> Color.White.copy(alpha = 0.9f)
    focused && destructive -> onError
    focused -> onPrimary
    // Idle buttons use surfaceVariant (or a low-opacity accent over the dark
    // surface), so white can disappear on themes with a light surfaceVariant.
    else -> onSurfaceVariant
}

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
    compact: Boolean = false,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val targetTextColor = setupButtonTextColor(
        enabled = enabled,
        focused = focused,
        destructive = destructive,
        onPrimary = MaterialTheme.colorScheme.onPrimary,
        onError = MaterialTheme.colorScheme.onError,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val textColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "setupButtonTextColor"
    )
    val targetContainerColor = when {
        focused -> accent
        primary -> accent.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "setupButtonContainerColor"
    )
    val targetBorderColor = if (focused) SaabVisualTokens.focusColor else accent.copy(alpha = 0.25f)
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "setupButtonBorderColor"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) SaabVisualTokens.focusOutlineWidth else 1.dp,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "setupButtonBorderWidth"
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .heightIn(min = if (compact) SaabVisualTokens.compactControlMinHeight else SaabVisualTokens.controlMinHeight),
        shape = RoundedCornerShape(SaabVisualTokens.controlCornerRadius),
        border = BorderStroke(borderWidth, borderColor),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = textColor,
            disabledContainerColor = Color(0xFF344B65),
            disabledContentColor = Color.White.copy(alpha = 0.9f)
        ),
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 20.dp, vertical = 10.dp)
    ) {
        if (content == null) {
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = textColor, textAlign = TextAlign.Center)
        } else {
            content()
        }
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
fun ReorderIndicator(instructions: String, modifier: Modifier = Modifier) {
    Text(
        text = "▲▼",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.semantics { stateDescription = instructions }
    )
}

@Composable
fun ProfileSetupLayout(
    title: String,
    description: String,
    stage: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    com.saab.tv.ui.navigation.AppBackHandler(priority = com.saab.tv.ui.navigation.AppBackPriority.Screen, onBack = onBack)
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column { SetupHeader(title, description, stage) }
            content()
            SetupButton(stringResource(R.string.common_back), onBack, modifier = Modifier.width(160.dp))
        }
    }
}
