package com.saab.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.addons.VoidDialog
import com.saab.tv.ui.addons.VoidInput
import kotlinx.coroutines.delay

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
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = onDismiss, title = "Manage Item") {
            Text(item.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 24.dp))

            // Rename
            if (showRenameOption) {
                VoidButton("Rename", { showRenameDialog = true }, Modifier.fillMaxWidth(), focusRequester = focusRequester)
                Spacer(Modifier.height(12.dp))
            }

            // Move
            VoidButton("Move Item", onStartReorder, Modifier.fillMaxWidth(), focusRequester = if (!showRenameOption) focusRequester else null)
            Spacer(Modifier.height(12.dp))

            // Remove Image (only shown when item has an image)
            if (showImageOption && item.customImageUrl != null) {
                VoidButton(
                    "Remove Image",
                    { showRemoveImageConfirm = true },
                    Modifier.fillMaxWidth(),
                    isDestructive = true
                )
                Spacer(Modifier.height(12.dp))
            }

            // Remove
            VoidButton(
                "Remove from Hub",
                { showRemoveConfirm = true },
                Modifier.fillMaxWidth(),
                isDestructive = true
            )

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                VoidButton("Close", onDismiss, Modifier.width(120.dp))
            }
        }
    }

    if (showRemoveImageConfirm) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = { showRemoveImageConfirm = false }, title = "Remove Image?") {
            Text("This will remove the custom image for this item.", color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(
                    "Cancel",
                    { showRemoveImageConfirm = false },
                    Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                VoidButton(
                    "Remove",
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
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = { showRemoveConfirm = false }, title = "Remove from Hub?") {
            Text("Are you sure you want to remove this item from the hub?", color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(
                    "Cancel",
                    { showRemoveConfirm = false },
                    Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                VoidButton(
                    "Remove",
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
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = { showRenameDialog = false }, title = "Rename Item") {
            VoidInput(value = editName, onValueChange = { editName = it }, placeholder = "Item Name", modifier = Modifier.focusRequester(focusRequester))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton("Cancel", { showRenameDialog = false }, Modifier.weight(1f))
                VoidButton("Save", { onRename(editName); showRenameDialog = false }, Modifier.weight(1f), isPrimary = true)
            }
        }
    }
}
