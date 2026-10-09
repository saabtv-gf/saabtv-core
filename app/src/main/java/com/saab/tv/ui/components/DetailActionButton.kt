package com.saab.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DetailActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    compact: Boolean = false,
    destructive: Boolean = false,
    maxExpandedWidth: androidx.compose.ui.unit.Dp = 220.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    val showText = isFocused && !compact

    // Estimate expanded width: icon(18) + padding(12+12) + gap(8) + text
    // ~8dp per character, minimum 110dp to fit short labels like Resume/Watched
    val expandedWidth = (42 + 8 + (label.length * 8)).coerceIn(110, maxExpandedWidth.value.toInt().coerceAtLeast(110)).dp

    // Bubble width: icon-only → icon + label
    val bubbleWidth by animateDpAsState(
        targetValue = if (showText) expandedWidth else 42.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "bubbleWidth"
    )

    // Text fade + slide
    val textAlpha by animateFloatAsState(
        targetValue = if (showText) 1f else 0f,
        animationSpec = tween(200),
        label = "textAlpha"
    )
    val textOffset by animateDpAsState(
        targetValue = if (showText) 0.dp else (-8).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "textOffset"
    )

    // Icon and border colors
    val iconColor by animateColorAsState(
        targetValue = when {
            isFocused -> accentColor
            isActive -> accentColor
            else -> Color.White.copy(alpha = 0.7f)
        },
        animationSpec = tween(200),
        label = "iconColor"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> accentColor
            isActive -> accentColor.copy(alpha = 0.5f)
            else -> Color.White.copy(alpha = 0.15f)
        },
        animationSpec = tween(200),
        label = "borderColor"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> accentColor.copy(alpha = 0.15f)
            isActive -> accentColor.copy(alpha = 0.08f)
            else -> Color.White.copy(alpha = 0.07f)
        },
        animationSpec = tween(200),
        label = "bgColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        label = "btnScale"
    )

    Row(
        modifier = modifier
            .width(bubbleWidth)
            .height(42.dp)
            .scale(scale)
            .clip(RoundedCornerShape(21.dp))
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(21.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )

        if (showText) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = accentColor,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha
                    translationX = textOffset.toPx()
                }
            )
        }
    }
}
