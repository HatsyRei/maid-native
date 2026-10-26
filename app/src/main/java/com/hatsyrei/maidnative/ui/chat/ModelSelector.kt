package com.hatsyrei.maidnative.ui.chat

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

// Roughly what the 320dp menu shows without scrolling.
private const val SEARCH_THRESHOLD = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelector(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (models.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val query = rememberTextFieldState()
    // Re-keys the position provider so the popup re-places itself when the keyboard moves.
    var visibleBottom by remember { mutableIntStateOf(0) }
    val hostView = LocalView.current
    val label = selected.ifEmpty { "select a model" }
    Box {
        Surface(
            onClick = {
                query.clearText()
                open = true
            },
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
        val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
        val provider = remember(gapPx, visibleBottom) { CenteredBelowAnchorPositionProvider(gapPx) }
        MenuPopup(
            expanded = open,
            popupPositionProvider = provider,
            transformOrigin = TransformOrigin(0.5f, 0f),
            onDismissRequest = { open = false },
            modifier = Modifier
                .width(240.dp)
                .heightIn(max = 320.dp),
            header = if (models.size > SEARCH_THRESHOLD) {
                { ModelSearchField(query, hostView, onFrameChange = { visibleBottom = it }) }
            } else {
                null
            },
        ) {
            val text = query.text.toString()
            val shown = remember(models, text) { filterModels(models, text) }
            // A fresh state per query so a narrowed list starts at its first match.
            val listState = remember(text) { LazyListState() }
            LazyColumn(state = listState) {
                items(shown) { model ->
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
            if (shown.isEmpty()) {
                Text(
                    text = "No matching models",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** Every whitespace-separated term must appear, so "qwen 30b q4" finds quantised variants. */
internal fun filterModels(models: List<String>, query: String): List<String> {
    val terms = query.split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return models
    return models.filter { model -> terms.all { model.contains(it, ignoreCase = true) } }
}

// The popup only re-fits to the visible frame when its own size changes, and nothing signals the
// keyboard moving that frame reliably, so poll it while focused.
@Composable
private fun ModelSearchField(state: TextFieldState, hostView: View, onFrameChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val popupView = LocalView.current
    val frameChanged by rememberUpdatedState(onFrameChange)
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val frame = Rect()
        var bottom = -1
        while (true) {
            withFrameNanos { }
            hostView.getWindowVisibleDisplayFrame(frame)
            if (frame.bottom != bottom) {
                bottom = frame.bottom
                frameChanged(bottom)
                popupView.requestLayout()
            }
        }
    }
    BasicTextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        textStyle = MaterialTheme.typography.titleSmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.SingleLine,
        // Model IDs are not prose.
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        decorator = TextFieldDecorator { innerTextField ->
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = "Search models",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
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
