package com.saab.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saab.tv.R
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.addons.VoidDialog
import com.saab.tv.ui.addons.VoidInput

@Composable
fun HubItemManageDialog(
    item: HubRowItemEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onStartReorder: () -> Unit,
    onRemove: () -> Unit,
    onRemoveImage: () -> Unit,
    showRenameOption: Boolean = true,
    showImageOption: Boolean = true
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(item.title) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showRemoveImageConfirm by remember { mutableStateOf(false) }

    // Main Manage Dialog
    if (!showRenameDialog && !showRemoveConfirm && !showRemoveImageConfirm) {
        val focusRequester = remember { FocusRequester() }

        VoidDialog(onDismissRequest = onDismiss, title = stringResource(R.string.hub_manage_item_title)) {
            LaunchedEffect(focusRequester) {
                withFrameNanos { }
                focusRequester.requestFocus()
            }
            Text(item.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 24.dp))

            // Rename
            if (showRenameOption) {
                VoidButton(stringResource(R.string.common_rename), { showRenameDialog = true }, Modifier.fillMaxWidth(), focusRequester = focusRequester)
                Spacer(Modifier.height(12.dp))
            }

            // Move
            VoidButton(stringResource(R.string.hub_move_item), onStartReorder, Modifier.fillMaxWidth(), focusRequester = if (!showRenameOption) focusRequester else null)
            Spacer(Modifier.height(12.dp))

            // Remove Image (only shown when item has an image)
            if (showImageOption && item.customImageUrl != null) {
                VoidButton(
                    stringResource(R.string.hub_remove_image),
                    { showRemoveImageConfirm = true },
                    Modifier.fillMaxWidth(),
                    isDestructive = true
                )
                Spacer(Modifier.height(12.dp))
            }

            // Remove
            VoidButton(
                stringResource(R.string.hub_remove_from_hub),
                { showRemoveConfirm = true },
                Modifier.fillMaxWidth(),
                isDestructive = true
            )

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                VoidButton(stringResource(R.string.common_close), onDismiss, Modifier.width(120.dp))
            }
        }
    }

    if (showRemoveImageConfirm) {
        val focusRequester = remember { FocusRequester() }

        VoidDialog(onDismissRequest = { showRemoveImageConfirm = false }, title = stringResource(R.string.hub_remove_image_title)) {
            LaunchedEffect(focusRequester) {
                withFrameNanos { }
                focusRequester.requestFocus()
            }
            Text(stringResource(R.string.hub_confirm_remove_image_generic), color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(
                    stringResource(R.string.common_cancel),
                    { showRemoveImageConfirm = false },
                    Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                VoidButton(
                    stringResource(R.string.common_remove),
                    {
                        onRemoveImage()
                        showRemoveImageConfirm = false
                        onDismiss()
                    },
                    Modifier.weight(1f),
                    isDestructive = true
                )
            }
        }
    }

    if (showRemoveConfirm) {
        val focusRequester = remember { FocusRequester() }

        VoidDialog(onDismissRequest = { showRemoveConfirm = false }, title = stringResource(R.string.hub_remove_from_hub_title)) {
            LaunchedEffect(focusRequester) {
                withFrameNanos { }
                focusRequester.requestFocus()
            }
            Text(stringResource(R.string.hub_confirm_remove_item_generic), color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(
                    stringResource(R.string.common_cancel),
                    { showRemoveConfirm = false },
                    Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                VoidButton(
                    stringResource(R.string.common_remove),
                    {
                        showRemoveConfirm = false
                        onRemove()
                    },
                    Modifier.weight(1f),
                    isDestructive = true
                )
            }
        }
    }

    // Rename Sub-Dialog
    if (showRenameDialog) {
        val focusRequester = remember { FocusRequester() }

        VoidDialog(onDismissRequest = { showRenameDialog = false }, title = stringResource(R.string.hub_rename_item_title)) {
            LaunchedEffect(focusRequester) {
                withFrameNanos { }
                focusRequester.requestFocus()
            }
            VoidInput(value = editName, onValueChange = { editName = it }, placeholder = stringResource(R.string.hub_item_name), modifier = Modifier.focusRequester(focusRequester))
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(stringResource(R.string.common_cancel), { showRenameDialog = false }, Modifier.weight(1f))
                VoidButton(stringResource(R.string.common_save), { onRename(editName); showRenameDialog = false }, Modifier.weight(1f), isPrimary = true)
            }
        }
    }
}
