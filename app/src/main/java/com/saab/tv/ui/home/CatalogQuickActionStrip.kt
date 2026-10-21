package com.saab.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One horizontal action layout for catalog cards in every destination. */
internal data class CatalogQuickAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean,
    val destructive: Boolean = false,
    val focusRequester: FocusRequester? = null
)

@Composable
internal fun CatalogQuickActionStrip(
    actions: List<CatalogQuickAction>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(CATALOG_QUICK_ACTION_STRIP_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            CardActionIcon(
                icon = action.icon,
                description = action.label,
                onClick = action.onClick,
                enabled = action.enabled,
                focusRequester = action.focusRequester,
                destructive = action.destructive
            )
        }
    }
}

internal const val CATALOG_QUICK_ACTION_STRIP_TAG = "catalog-quick-action-strip"
