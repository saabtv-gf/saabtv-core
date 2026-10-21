package com.saab.tv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.saab.tv.R
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.domain.HubShape
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.addons.VoidDialog
import com.saab.tv.ui.addons.VoidInput
import com.saab.tv.ui.components.scrollToItemUsingSaabMotion
import kotlinx.coroutines.delay

@Composable
fun HubCategoryManagerDialog(
    initialItems: List<HubRowItemEntity>,
    hubShape: HubShape,
    onDismiss: () -> Unit,
    onSave: (List<HubRowItemEntity>) -> Unit
) {
    // Local mutable list for reordering/removals
    var currentItems by remember { mutableStateOf(initialItems) }
    var reorderingItem by remember { mutableStateOf<HubRowItemEntity?>(null) }
    var managingItem by remember { mutableStateOf<HubRowItemEntity?>(null) }
    var showBulkUpload by remember { mutableStateOf(false) }
    var renameItem by remember { mutableStateOf<HubRowItemEntity?>(null) }
    var newRenameName by remember { mutableStateOf("") }
    var confirmRemoveItem by remember { mutableStateOf<HubRowItemEntity?>(null) }
    var confirmRemoveImageItem by remember { mutableStateOf<HubRowItemEntity?>(null) }

    val focusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val categoryListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }

    if (managingItem == null && confirmRemoveItem == null && confirmRemoveImageItem == null && renameItem == null) {
        VoidDialog(
            onDismissRequest = onDismiss,
            title = if (reorderingItem != null) stringResource(R.string.hub_reordering_title)
            else stringResource(R.string.hub_manage_categories_title),
            footer = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoidButton(stringResource(R.string.common_cancel), onDismiss, Modifier.weight(1f))
                    VoidButton(
                        stringResource(R.string.hub_save_changes),
                        { onSave(currentItems) },
                        Modifier.weight(1f),
                        isPrimary = true,
                        focusRequester = if (currentItems.isEmpty()) focusRequester else saveFocusRequester
                    )
                }
            }
        ) {
            if (currentItems.isEmpty()) {
                Text(stringResource(R.string.hub_no_categories), color = Color.White.copy(0.5f))
            } else {
                LazyColumn(
                    state = categoryListState,
                    modifier = Modifier.heightIn(max = 310.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(currentItems, key = { _, it -> it.configUniqueId }) { index, item ->
                        val isReordering = reorderingItem?.configUniqueId == item.configUniqueId

                        HubItemEditorRow(
                            item = item,
                            isReordering = isReordering,
                            initialFocusRequester = if (index == 0) focusRequester else null,
                            onMoveUp = {
                                if (index > 0) {
                                    val mutable = currentItems.toMutableList()
                                    val moved = mutable.removeAt(index)
                                    mutable.add(index - 1, moved)
                                    currentItems = mutable
                                    val target = index - 1
                                    scope.launch { delay(50); categoryListState.scrollToItemUsingSaabMotion((target - 1).coerceAtLeast(0)) }
                                }
                            },
                            onMoveDown = {
                                if (index < currentItems.size - 1) {
                                    val mutable = currentItems.toMutableList()
                                    val moved = mutable.removeAt(index)
                                    mutable.add(index + 1, moved)
                                    currentItems = mutable
                                    val target = index + 1
                                    scope.launch { delay(50); categoryListState.scrollToItemUsingSaabMotion((target - 1).coerceAtLeast(0)) }
                                }
                            },
                            onClick = {
                                if (reorderingItem != null) {
                                    reorderingItem = null
                                } else {
                                    managingItem = item
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Bulk Upload Button
            VoidButton(
                text = stringResource(R.string.hub_manage_images),
                onClick = {
                    showBulkUpload = true
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onPreviewKeyEvent {
                    if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                        saveFocusRequester.requestFocus()
                        true
                    } else false
                }
            )

        }
    } else if (managingItem != null && confirmRemoveItem == null && confirmRemoveImageItem == null && renameItem == null) {
        // Item Actions Dialog (Move, Remove, Remove Image)
        VoidDialog(
            onDismissRequest = { managingItem = null },
            title = managingItem?.title ?: stringResource(R.string.hub_item_options_title),
            footer = { VoidButton(stringResource(R.string.common_close), { managingItem = null }, Modifier.fillMaxWidth()) }
        ) {
             VoidButton(stringResource(R.string.common_rename), {
                 renameItem = managingItem
                 newRenameName = managingItem?.title ?: ""
                 managingItem = null
             }, Modifier.fillMaxWidth())

             Spacer(Modifier.height(12.dp))

             VoidButton(stringResource(R.string.hub_move_item), {
                 reorderingItem = managingItem
                 managingItem = null
             }, Modifier.fillMaxWidth())

             Spacer(Modifier.height(12.dp))

             // Remove Image (only shown when item has a custom image)
             if (managingItem?.customImageUrl != null) {
                 VoidButton(stringResource(R.string.hub_remove_image), {
                     confirmRemoveImageItem = managingItem
                 }, Modifier.fillMaxWidth(), isDestructive = true)

                 Spacer(Modifier.height(12.dp))
             }

             VoidButton(stringResource(R.string.hub_remove_from_hub), {
                 confirmRemoveItem = managingItem
             }, Modifier.fillMaxWidth(), isDestructive = true)
        }
    }

    confirmRemoveItem?.let { itemToRemove ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { focusRequester.requestFocus() } }

        VoidDialog(
            onDismissRequest = { confirmRemoveItem = null },
            title = stringResource(R.string.hub_remove_from_hub_title),
            footer = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoidButton(stringResource(R.string.common_cancel), { confirmRemoveItem = null }, Modifier.weight(1f), focusRequester = focusRequester)
                    VoidButton(
                        stringResource(R.string.common_remove),
                        {
                            val targetId = itemToRemove.configUniqueId
                            currentItems = currentItems.filterNot { it.configUniqueId == targetId }
                            confirmRemoveItem = null
                            managingItem = null
                        },
                        Modifier.weight(1f),
                        isDestructive = true
                    )
                }
            }
        ) {
            Text(
                stringResource(R.string.hub_confirm_remove_item, itemToRemove.title),
                color = Color.Gray
            )
        }
    }

    // Remove Image Confirmation Dialog
    confirmRemoveImageItem?.let { itemWhoseImageWillBeRemoved ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { focusRequester.requestFocus() } }

        VoidDialog(
            onDismissRequest = { confirmRemoveImageItem = null },
            title = stringResource(R.string.hub_remove_image_title),
            footer = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoidButton(stringResource(R.string.common_cancel), { confirmRemoveImageItem = null }, Modifier.weight(1f), focusRequester = focusRequester)
                    VoidButton(
                        stringResource(R.string.common_remove),
                        {
                            val targetId = itemWhoseImageWillBeRemoved.configUniqueId
                            val mutable = currentItems.toMutableList()
                            val idx = mutable.indexOfFirst { it.configUniqueId == targetId }
                            if (idx != -1) {
                                mutable[idx] = mutable[idx].copy(customImageUrl = null)
                                currentItems = mutable
                            }
                            confirmRemoveImageItem = null
                            managingItem = null
                        },
                        Modifier.weight(1f),
                        isDestructive = true
                    )
                }
            }
        ) {
            Text(
                stringResource(R.string.hub_confirm_remove_image, itemWhoseImageWillBeRemoved.title),
                color = Color.Gray
            )
        }
    }

    // Rename Dialog
    renameItem?.let { itemToRename ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { focusRequester.requestFocus() } }

        VoidDialog(
            onDismissRequest = { renameItem = null },
            title = stringResource(R.string.hub_rename_item_title),
            footer = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoidButton(stringResource(R.string.common_cancel), { renameItem = null }, Modifier.weight(1f))
                    VoidButton(
                        stringResource(R.string.common_save),
                        {
                            val mutable = currentItems.toMutableList()
                            val idx = mutable.indexOfFirst { it.configUniqueId == itemToRename.configUniqueId }
                            if (idx != -1) {
                                mutable[idx] = mutable[idx].copy(title = newRenameName)
                                currentItems = mutable
                            }
                            renameItem = null
                        },
                        Modifier.weight(1f),
                        isPrimary = true
                    )
                }
            }
        ) {
            com.saab.tv.ui.addons.VoidInput(
                value = newRenameName,
                onValueChange = { newRenameName = it },
                placeholder = stringResource(R.string.hub_item_name),
                focusRequester = focusRequester
            )
        }
    }

    // Bulk Upload Dialog Logic
    if (showBulkUpload) {
        val context = androidx.compose.ui.platform.LocalContext.current
        com.saab.tv.ui.home.HubBulkUploadDialog(
            items = currentItems,
            shape = hubShape,
            onDismiss = { showBulkUpload = false },
            onImageReceived = { configId, bytes ->
                // Save to internal storage
                val filename = "hub_item_${java.util.UUID.randomUUID()}.jpg"
                try {
                    val imageFile = java.io.File(com.saab.tv.data.account.AccountStorage.files(context), "hub_images/$filename")
                    imageFile.parentFile?.mkdirs()
                    imageFile.outputStream().use {
                        it.write(bytes)
                    }
                    val path = imageFile.absolutePath

                    // Update item with new path in real-time
                    val mutable = currentItems.toMutableList()
                    val idx = mutable.indexOfFirst { it.configUniqueId == configId }
                    if (idx != -1) {
                        mutable[idx] = mutable[idx].copy(customImageUrl = path)
                        currentItems = mutable
                    }
                } catch (e: Exception) {
                    if (com.saab.tv.BuildConfig.DEBUG) android.util.Log.w("HubCategoryManagerDialog", "Image upload error", e)
                }
            },
            onImageDeleted = { configId ->
                val mutable = currentItems.toMutableList()
                val idx = mutable.indexOfFirst { it.configUniqueId == configId }
                if (idx != -1) {
                    mutable[idx] = mutable[idx].copy(customImageUrl = null)
                    currentItems = mutable
                }
            }
        )
    }
}
