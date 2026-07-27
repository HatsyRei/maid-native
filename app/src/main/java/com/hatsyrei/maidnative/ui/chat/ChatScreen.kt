package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.ui.markdown.MarkdownText
import kotlinx.coroutines.launch

private data class EditTarget(val id: String, val initial: String, val revise: Boolean)
private data class RenameTarget(val id: String, val initial: String)

private fun chatTitle(node: MessageNode): String =
    (node.metadata["title"] as? String)?.takeIf { it.isNotBlank() } ?: "New Chat"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onRegenerate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onRevise: (String, String) -> Unit,
    onPrevBranch: (String) -> Unit,
    onNextBranch: (String) -> Unit,
    onSelectChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Only pin to the bottom while the user is already there; if they've scrolled
    // up to read history mid-stream, don't yank them back down every token.
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(state.conversation.size, state.conversation.lastOrNull()?.content) {
        if (state.conversation.isNotEmpty() && atBottom) {
            listState.scrollToItem(state.conversation.size - 1, Int.MAX_VALUE)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                roots = MessageTree.getRoots(state.mappings),
                activeRoot = state.root,
                onSelect = { id ->
                    onSelectChat(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    onNewChat()
                    scope.launch { drawerState.close() }
                },
                onRename = { id -> renameTarget = RenameTarget(id, "") },
                onDeleteChat = onDeleteChat,
            )
        },
    ) {
        ChatScaffold(
            state = state,
            listState = listState,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNewChat = onNewChat,
            onOpenSettings = onOpenSettings,
            onSubmit = onSubmit,
            onStop = onStop,
            onRegenerate = onRegenerate,
            onDelete = onDelete,
            onPrevBranch = onPrevBranch,
            onNextBranch = onNextBranch,
            onRequestEdit = { id, initial, revise -> editTarget = EditTarget(id, initial, revise) },
        )
    }

    editTarget?.let { target ->
        EditDialog(
            initial = target.initial,
            revise = target.revise,
            onDismiss = { editTarget = null },
            onConfirm = { text ->
                if (target.revise) onRevise(target.id, text) else onEdit(target.id, text)
                editTarget = null
            },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                onRenameChat(target.id, title)
                renameTarget = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScaffold(
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPrevBranch: (String) -> Unit,
    onNextBranch: (String) -> Unit,
    onRequestEdit: (id: String, initial: String, revise: Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                    }
                },
                title = {
                    Column {
                        Text("Maid Native")
                        val subtitle = when {
                            state.settings.model.isNotEmpty() -> state.settings.model
                            state.models.isEmpty() -> "no endpoint"
                            else -> "select a model"
                        }
                        Text(subtitle, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.conversation, key = { it.id }) { node ->
                    val siblings = node.parent?.let { MessageTree.getChildren(state.mappings, it) }
                        ?: emptyList()
                    val index = siblings.indexOfFirst { it.id == node.id }
                    MessageItem(
                        node = node,
                        siblingIndex = index,
                        siblingCount = siblings.size,
                        busy = state.busy,
                        onRegenerate = { onRegenerate(node.id) },
                        onDelete = { onDelete(node.id) },
                        onRequestEdit = { revise ->
                            onRequestEdit(node.id, node.content, revise)
                        },
                        onPrevBranch = { node.parent?.let(onPrevBranch) },
                        onNextBranch = { node.parent?.let(onNextBranch) },
                    )
                }
            }
            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Composer(
                enabled = state.ready,
                busy = state.busy,
                onSubmit = onSubmit,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun EditDialog(
    initial: String,
    revise: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (revise) "Revise message" else "Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.trim().isNotEmpty(),
            ) {
                Text(if (revise) "Send" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.trim().isNotEmpty(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerContent(
    roots: List<MessageNode>,
    activeRoot: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
) {
    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (roots.isEmpty()) {
                item {
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(roots, key = { it.id }) { root ->
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = chatTitle(root),
                                maxLines = 1,
                            )
                        },
                        selected = root.id == activeRoot,
                        onClick = { onSelect(root.id) },
                        badge = {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Conversation options",
                                )
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; onRename(root.id) },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menuOpen = false; onDeleteChat(root.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    node: MessageNode,
    siblingIndex: Int,
    siblingCount: Int,
    busy: Boolean,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isUser) 0.35f else 0.6f),
                RoundedCornerShape(16.dp),
            )
            .combinedClickable(
                onClick = {},
                onLongClick = { menuOpen = true },
            )
            .padding(14.dp),
    ) {
        Text(
            text = if (isUser) "You" else "Assistant",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!reasoning.isNullOrEmpty()) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val body = content ?: if (node.role == "assistant" && node.content.isEmpty()) "…" else ""
        if (body.isNotEmpty()) {
            if (isUser) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                MarkdownText(
                    markdown = body,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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

        Box {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (node.role == "assistant") {
                    DropdownMenuItem(
                        text = { Text("Regenerate") },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRegenerate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Modify") },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRequestEdit(false) },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Revise") },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRequestEdit(true) },
                    )
                    DropdownMenuItem(
                        text = { Text("Modify") },
                        enabled = !busy,
                        onClick = { menuOpen = false; onRequestEdit(false) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(node.content))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    enabled = !busy,
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    enabled: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && text.trim().isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "Message" else "Set an endpoint & model in Settings") },
            shape = RoundedCornerShape(28.dp),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        val fabColor =
            if (busy || canSend) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .background(fabColor, RoundedCornerShape(24.dp)),
        ) {
            IconButton(
                onClick = {
                    if (busy) {
                        onStop()
                    } else if (canSend) {
                        onSubmit(text)
                        text = ""
                    }
                },
                enabled = busy || canSend,
            ) {
                if (busy) {
                    // Filled square = stop (material-icons-core has no Stop glyph).
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color.Black, RoundedCornerShape(3.dp)),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
