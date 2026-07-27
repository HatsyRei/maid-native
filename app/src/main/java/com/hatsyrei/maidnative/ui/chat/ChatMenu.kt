package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * A single pop-up menu row that dims its content while pressed instead of
 * drawing a full-width highlight (no ripple/state-layer). Shared by the message,
 * drawer, and model-picker menus.
 */
@Composable
internal fun MenuOption(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentAlpha = when {
        !enabled -> 0.38f
        pressed -> 0.5f
        else -> 1f
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .alpha(contentAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                trailingIcon()
            }
        }
    }
}

/**
 * A context menu that pops up centered horizontally on the user's touch point
 * (mirrors the RN app anchoring a zero-size rect at pageX/pageY). Uses a raw
 * [Popup] with a custom position provider because [androidx.compose.material3.DropdownMenu]
 * only supports anchor-relative offsets, which drift to screen edges for wide anchors.
 */
@Composable
internal fun TapContextMenu(
    expanded: Boolean,
    touchOffset: Offset,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val provider = remember(touchOffset) {
        TapMenuPositionProvider(IntOffset(touchOffset.x.roundToInt(), touchOffset.y.roundToInt()))
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 168.dp, max = 172.dp)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                content = content,
            )
        }
    }
}

/**
 * Centers the popup horizontally on [touch] (relative to the popup's anchor)
 * and places its top at the touch point, clamped inside the window.
 */
private class TapMenuPositionProvider(private val touch: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + touch.x - popupContentSize.width / 2
        val y = anchorBounds.top + touch.y
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
