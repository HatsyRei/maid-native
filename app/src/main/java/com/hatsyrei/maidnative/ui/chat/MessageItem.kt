package com.hatsyrei.maidnative.ui.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.ui.icons.ContentCopyIcon
import com.hatsyrei.maidnative.ui.markdown.MarkdownText
import com.hatsyrei.maidnative.ui.markdown.StreamingMarkdownText
import com.mikepenz.markdown.model.StreamingMarkdownState
import kotlinx.coroutines.launch

/**
 * The conversation's system node, shown as the first card in the thread — the
 * root of every tree *is* the system message, so this stops the UI from hiding
 * the one setting that shapes every reply. Collapsed to a single line by default
 * so a long persona stays a header instead of burying the conversation.
 */
@Composable
internal fun SystemPromptCard(
    prompt: String,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(16.dp),
            )
            .background(
                if (pressed) Color.Black.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { expanded = !expanded },
                )
            }
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "System",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onEdit,
                enabled = enabled,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit system prompt",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun MessageItem(
    node: MessageNode,
    siblingIndex: Int,
    siblingCount: Int,
    busy: Boolean,
    ready: Boolean,
    isLatest: Boolean,
    userName: String,
    assistantName: String,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onRequestEdit: (revise: Boolean) -> Unit,
    onPrevBranch: () -> Unit,
    onNextBranch: () -> Unit,
    streamingState: StreamingMarkdownState? = null,
    streamingReasoning: String? = null,
) {
    val isUser = node.role == "user"
    // `Reasoning.split` scans (and for `<think>` bodies, copies) the entire
    // message. It ran unmemoized on every recomposition, so scrolling a bubble
    // into view or toggling `busy` re-scanned it for nothing. Keying on the
    // content means it now runs only when the text actually changes — and while
    // this bubble is streaming it does not run at all, because the reply and the
    // trace arrive already separated.
    val (content, reasoning) = remember(node.content, isUser, streamingReasoning) {
        when {
            isUser -> node.content to null
            streamingReasoning != null -> node.content to streamingReasoning.ifEmpty { null }
            else -> Reasoning.split(node.content)
        }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val menus = LocalMenuController.current
    val menuId = remember(node.id) { "message:${node.id}" }
    // `derivedStateOf` so this bubble recomposes when *its* menu toggles, not
    // every time any other item's does.
    val menuOpen by remember(menuId) { derivedStateOf { menus.openId == menuId } }
    val closeMenu = { menus.close(menuId) }
    // The list disposes off-screen items; drop the slot so it can't reopen on scroll back.
    DisposableEffect(menuId) { onDispose { menus.close(menuId) } }
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
                    onLongPress = { pressOffset = it; menus.open(menuId) },
                )
            }
            .padding(14.dp),
    ) {
        Box {
            TapContextMenu(
                expanded = menuOpen,
                touchOffset = pressOffset,
                onDismiss = closeMenu,
            ) {
                if (node.role == "assistant") {
                    // Regenerate/Revise both kick off a new completion, so they
                    // need a usable model on top of an idle stream.
                    MenuOption(
                        text = "Regenerate",
                        trailingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = !busy && ready,
                        onClick = { closeMenu(); onRegenerate() },
                    )
                    MenuOption(
                        text = "Modify",
                        trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = !busy,
                        onClick = { closeMenu(); onRequestEdit(false) },
                    )
                } else {
                    MenuOption(
                        text = "Revise",
                        trailingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        enabled = !busy && ready,
                        onClick = { closeMenu(); onRequestEdit(true) },
                    )
                    MenuOption(
                        text = "Modify",
                        trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = !busy,
                        onClick = { closeMenu(); onRequestEdit(false) },
                    )
                }
                MenuOption(
                    text = "Copy",
                    trailingIcon = { Icon(ContentCopyIcon, contentDescription = null) },
                    onClick = {
                        closeMenu()
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("message", node.content)),
                            )
                        }
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
                    onClick = { closeMenu(); onDelete() },
                )
            }
        }
        MessageHeader(
            name = if (isUser) userName else assistantName,
            controls = {
                if (siblingCount > 1) {
                    IconButton(
                        onClick = onPrevBranch,
                        enabled = siblingIndex > 0 && !busy,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous branch",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${siblingIndex + 1} / $siblingCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = onNextBranch,
                        enabled = siblingIndex < siblingCount - 1 && !busy,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next branch",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
        if (!reasoning.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 10.dp)
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
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (streamingState != null) {
            // Actively streaming: render from the incrementally parsed state so
            // each token re-parses only the trailing block, not the whole reply.
            StreamingMarkdownText(
                state = streamingState,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (body.isNotEmpty()) {
            MarkdownText(
                markdown = body,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Role label + branch controls. A `weight(1f)` row would squeeze a long custom
 * name against the chevrons, so the name is measured against the full width
 * first and the controls drop to a line of their own when the two don't fit.
 */
@Composable
private fun MessageHeader(
    name: String,
    controls: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = controls)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val label = measurables[0].measure(loose)
        // Empty when there is a single sibling, which collapses this to the label alone.
        val branch = measurables[1].measure(loose)
        val sameLine = label.width + branch.width <= width
        val height = if (sameLine) {
            maxOf(label.height, branch.height)
        } else {
            label.height + branch.height
        }
        layout(width, height) {
            if (sameLine) {
                label.place(0, (height - label.height) / 2)
                branch.place(width - branch.width, (height - branch.height) / 2)
            } else {
                label.place(0, 0)
                branch.place(width - branch.width, label.height)
            }
        }
    }
}
