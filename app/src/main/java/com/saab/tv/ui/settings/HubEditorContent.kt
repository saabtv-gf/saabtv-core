package com.saab.tv.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.saab.tv.domain.HubShape
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.addons.VoidDialog
import com.saab.tv.ui.addons.VoidInput
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.navigation.isInitialBackPress
import com.saab.tv.ui.navigation.isBackOrEscape
import com.saab.tv.ui.utils.requestFocusWhenAttached
import kotlinx.coroutines.launch

@Composable
fun HubEditorContent(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    shape: HubShape,
    onShapeChange: (HubShape) -> Unit,
    categoryCount: Int,
    onAddCategory: () -> Unit,
    onManageCategories: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    shapeFocusRequester: FocusRequester? = null,
    addCategoryFocusRequester: FocusRequester? = null,
    manageCategoriesFocusRequester: FocusRequester? = null
) {
    val saveFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var cancelFocused by remember { mutableStateOf(false) }
    fun focusCancel() {
        scope.launch {
            cancelFocusRequester.requestFocusWhenAttached(hasFocus = { cancelFocused })
        }
    }
    val dispatchBackFromKey = com.saab.tv.ui.navigation.rememberAppBackKeyAction {
        if (cancelFocused) onCancel() else focusCancel()
    }
    com.saab.tv.ui.navigation.AppBackHandler(priority = com.saab.tv.ui.navigation.AppBackPriority.Modal) {
        if (cancelFocused) onCancel() else focusCancel()
    }

    // Helper modifier to redirect Back press to Cancel button
    val interceptBackToCancel = Modifier.onPreviewKeyEvent {
        if (it.isBackOrEscape()) {
            if (it.isInitialBackPress()) dispatchBackFromKey()
            true
        } else false
    }

    VoidDialog(
        onDismissRequest = onCancel,
        title = title,
        footer = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(
                    text = stringResource(com.saab.tv.R.string.common_cancel),
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).onFocusChanged { cancelFocused = it.isFocused }.onPreviewKeyEvent {
                        if (it.isBackOrEscape()) {
                            if (it.isInitialBackPress()) dispatchBackFromKey()
                            true
                        } else false
                    },
                    focusRequester = cancelFocusRequester
                )
                VoidButton(
                    text = stringResource(com.saab.tv.R.string.common_save),
                    onClick = onSave,
                    modifier = Modifier.weight(1f).then(interceptBackToCancel),
                    enabled = saveEnabled,
                    focusRequester = saveFocusRequester
                )
            }
        }
    ) {
        // Name
        Text(stringResource(com.saab.tv.R.string.hub_editor_name), color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
        VoidInput(
            value = name,
            onValueChange = onNameChange,
            placeholder = stringResource(com.saab.tv.R.string.hub_editor_row_name),
            modifier = if (focusRequester != null) Modifier.onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                    shapeFocusRequester?.requestFocus()
                    true
                } else if (it.isBackOrEscape()) {
                    if (it.isInitialBackPress()) dispatchBackFromKey()
                    true
                } else false
            } else Modifier.then(interceptBackToCancel).onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                    shapeFocusRequester?.requestFocus()
                    true
                } else false
            },
            onDone = { shapeFocusRequester?.requestFocus() },
            focusRequester = focusRequester
        )

        Spacer(Modifier.height(20.dp))

        // Shape
        Text(stringResource(com.saab.tv.R.string.hub_editor_shape), color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HubShape.entries.forEach { s ->
                val isSelected = shape == s
                HubShapeItem(
                    label = stringResource(
                        when (s) {
                            HubShape.HORIZONTAL -> com.saab.tv.R.string.dashboard_shape_landscape
                            HubShape.VERTICAL -> com.saab.tv.R.string.dashboard_shape_portrait
                            HubShape.SQUARE -> com.saab.tv.R.string.dashboard_shape_square
                        }
                    ),
                    isSelected = isSelected,
                    onClick = { onShapeChange(s) },
                    modifier = interceptBackToCancel,
                    focusRequester = if (isSelected) shapeFocusRequester else null
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Actions
        Text(stringResource(com.saab.tv.R.string.hub_editor_category_count, categoryCount), color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoidButton(
                text = stringResource(com.saab.tv.R.string.hub_editor_add_category),
                onClick = onAddCategory, 
                modifier = Modifier.fillMaxWidth().then(interceptBackToCancel).onPreviewKeyEvent {
                    if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
                        shapeFocusRequester?.requestFocus()
                        true
                    } else false
                },
                focusRequester = addCategoryFocusRequester
            )
            
            VoidButton(
                text = stringResource(com.saab.tv.R.string.hub_manage_categories_title),
                onClick = onManageCategories, 
                modifier = Modifier.fillMaxWidth().alpha(if (categoryCount > 0) 1f else 0.5f).then(interceptBackToCancel).onPreviewKeyEvent {
                    if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                        saveFocusRequester.requestFocus()
                        true
                    } else false
                },
                enabled = categoryCount > 0,
                focusRequester = manageCategoriesFocusRequester
            )
        }

    }
}

@Composable
private fun RowScope.HubShapeItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    val bgColor = Color.White.copy(0.05f)
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isFocused -> Color.White
        else -> Color.White.copy(0.7f)
    }
    
    val borderColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> MaterialTheme.colorScheme.primary
            else -> Color.Transparent
        },
        animationSpec = SaabMotion.focusFeedbackSpec()
    )
    val borderWidth = if (isFocused || isSelected) 2.dp else 0.dp

    Box(
        modifier = modifier
            .weight(1f)
            .height(50.dp) // Match VoidButton height
            .clip(RoundedCornerShape(8.dp)) // Match VoidButton radius
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 4.dp), // Minimal padding to prevent text touching edges
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label, 
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            ), 
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
