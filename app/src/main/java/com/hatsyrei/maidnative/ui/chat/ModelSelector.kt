package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelector(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (models.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val label = selected.ifEmpty { "select a model" }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 88.dp, max = 200.dp),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        if (open) {
            val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
            val provider = remember(gapPx) { CenteredBelowAnchorPositionProvider(gapPx) }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                MenuSurface(
                    Modifier
                        .width(240.dp)
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    models.forEach { model ->
                        MenuOption(
                            text = model,
                            textColor = if (model == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            onClick = {
                                open = false
                                onSelect(model)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Centers the popup horizontally on the anchor and places it just below,
 * clamped inside the window. Anchors to the selector pill so the menu lines up
 * with the pill.
 */
private class CenteredBelowAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = anchorBounds.bottom + gap
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
