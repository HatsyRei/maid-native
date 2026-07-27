package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.ui.icons.ContentCopyIcon
import com.hatsyrei.maidnative.ui.markdown.MarkdownText

@Composable
internal fun MessageItem(
    node: MessageNode,
    siblingIndex: Int,
    siblingCount: Int,
    busy: Boolean,
    isLatest: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onRequestEdit: (revise: Boolean) -> Unit,
    onPrevBranch: () -> Unit,
    onNextBranch: () -> Unit,
) {
    val isUser = node.role == "user"
    val (content, reasoning) = if (isUser) node.content to null else Reasoning.split(node)
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    var reasoningExpanded by remember(node.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(16.dp),
            )
            .background(
                // Darken (dim) the card while pressed as the long-press cue.
                if (pressed) Color.Black.copy(alpha = 0.18f)
                else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onLongPress = { pressOffset = it; menuOpen = true },
                )
            }
            .padding(14.dp),
    ) {
        Box {
            TapContextMenu(
                expanded = menuOpen,
                touchOffset = pressOffset,
                onDismiss = { menuOpen = false },
            ) {
                if (node.role == "assistant") {
                    MenuOption(
                        text = "Regenerate",
                        trailingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRegenerate() },
                    )
                    MenuOption(
                        text = "Modify",
                        trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRequestEdit(false) },
                    )
                } else {
                    MenuOption(
                        text = "Revise",
                        trailingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRequestEdit(true) },
                    )
                    MenuOption(
                        text = "Modify",
                        trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRequestEdit(false) },
                    )
                }
                MenuOption(
                    text = "Copy",
                    trailingIcon = { Icon(ContentCopyIcon, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(node.content))
                    },
                )
                MenuOption(
                    text = "Delete",
                    textColor = MaterialTheme.colorScheme.error,
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    enabled = !busy,
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
        Text(
            text = if (isUser) "You" else "Assistant",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!reasoning.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { reasoningExpanded = !reasoningExpanded },
            ) {
                Icon(
                    imageVector = if (reasoningExpanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (reasoningExpanded) "Hide reasoning" else "Show reasoning",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (reasoningExpanded) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
        }
        val body = content ?: ""
        if (node.role == "assistant" && body.isBlank() && busy && isLatest) {
            // Placeholder shown only on the newest assistant bubble while waiting
            // on the endpoint / first tokens. Gated on `busy` so it clears if the
            // stream ends or errors before any tokens arrive.
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (body.isNotEmpty()) {
            MarkdownText(
                markdown = body,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (siblingCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrevBranch,
                    enabled = siblingIndex > 0 && !busy,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous branch",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${siblingIndex + 1} / $siblingCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onNextBranch,
                    enabled = siblingIndex < siblingCount - 1 && !busy,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "Next branch",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
