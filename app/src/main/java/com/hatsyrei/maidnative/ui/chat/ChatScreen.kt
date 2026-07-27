package com.hatsyrei.maidnative.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.ui.icons.ArrowUpwardIcon
import com.hatsyrei.maidnative.ui.icons.ContentCopyIcon
import com.hatsyrei.maidnative.ui.icons.FileDownloadIcon
import com.hatsyrei.maidnative.ui.icons.FolderOpenIcon
import com.hatsyrei.maidnative.ui.icons.SaveAltIcon
import com.hatsyrei.maidnative.ui.markdown.MarkdownText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onSelectModel: (String) -> Unit,
    exportFileName: (String) -> String,
    onExportConversation: (String, Uri) -> Unit,
    onImportConversations: (List<Uri>) -> Unit,
    onBackupAllChats: (Uri) -> Unit,
) {
    val listState = rememberLazyListState()
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var deleteMsgTarget by remember { mutableStateOf<String?>(null) }
    var deleteChatTarget by remember { mutableStateOf<String?>(null) }
    var exportRoot by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val root = exportRoot
        if (uri != null && root != null) onExportConversation(root, uri)
        exportRoot = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onImportConversations(uris) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onBackupAllChats(uri) }

    // Back button closes the drawer instead of leaving the app.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Dismiss the soft keyboard when the drawer opens so it doesn't overlay the
    // conversation list (RN parity).
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                roots = MessageTree.getRoots(state.mappings),
                activeRoot = state.root,
                onSelect = { id -> onSelectChat(id) },
                onNewChat = { onNewChat() },
                onRename = { id -> renameTarget = RenameTarget(id, "") },
                onDeleteChat = { id -> deleteChatTarget = id },
                onExport = { id ->
                    exportRoot = id
                    exportLauncher.launch(exportFileName(id))
                },
                onImport = { importLauncher.launch(arrayOf("application/json")) },
                onBackupAll = { backupLauncher.launch(null) },
            )
        },
    ) {
        ChatScaffold(
            state = state,
            listState = listState,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onOpenSettings = onOpenSettings,
            onSubmit = onSubmit,
            onStop = onStop,
            onRegenerate = onRegenerate,
            onDelete = { id -> deleteMsgTarget = id },
            onPrevBranch = onPrevBranch,
            onNextBranch = onNextBranch,
            onSelectModel = onSelectModel,
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

    deleteMsgTarget?.let { id ->
        ConfirmDialog(
            title = "Delete message",
            message = "Delete this message and everything below it? This can't be undone.",
            onDismiss = { deleteMsgTarget = null },
            onConfirm = {
                onDelete(id)
                deleteMsgTarget = null
            },
        )
    }

    deleteChatTarget?.let { id ->
        ConfirmDialog(
            title = "Delete conversation",
            message = "Delete this conversation permanently? This can't be undone.",
            onDismiss = { deleteChatTarget = null },
            onConfirm = {
                onDeleteChat(id)
                deleteChatTarget = null
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
    onOpenSettings: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPrevBranch: (String) -> Unit,
    onNextBranch: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onRequestEdit: (id: String, initial: String, revise: Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                    }
                },
                title = {
                    ModelSelector(
                        models = state.models,
                        selected = state.settings.model,
                        onSelect = onSelectModel,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(18.dp),
                        )
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
            // Reset to the top of the conversation whenever the active chat changes.
            LaunchedEffect(state.root) {
                listState.scrollToItem(0)
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Bottom spacer (viewport height - 96dp) so the last message can be
                // scrolled up near the top and streaming text scrolled into view
                // (mirrors the RN app's ListFooterComponent).
                val spacerHeight = (maxHeight - 96.dp).coerceAtLeast(0.dp)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val latestId = state.conversation.lastOrNull()?.id
                    items(state.conversation, key = { it.id }) { node ->
                        val siblings = node.parent?.let { MessageTree.getChildren(state.mappings, it) }
                            ?: emptyList()
                        val index = siblings.indexOfFirst { it.id == node.id }
                        MessageItem(
                            node = node,
                            siblingIndex = index,
                            siblingCount = siblings.size,
                            busy = state.busy,
                            isLatest = node.id == latestId,
                            onRegenerate = { onRegenerate(node.id) },
                            onDelete = { onDelete(node.id) },
                            onRequestEdit = { revise ->
                                onRequestEdit(node.id, node.content, revise)
                            },
                            onPrevBranch = { node.parent?.let(onPrevBranch) },
                            onNextBranch = { node.parent?.let(onNextBranch) },
                        )
                    }
                    if (state.conversation.isNotEmpty()) {
                        item(key = "__bottom_spacer__") {
                            Spacer(Modifier.height(spacerHeight))
                        }
                    }
                }
                DraggableScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.TopEnd),
                    trailingSpacerHeight = spacerHeight,
                )
            }
            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
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

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = when {
        selected.isNotEmpty() -> selected
        models.isEmpty() -> "no endpoint"
        else -> "select a model"
    }
    Box {
        Surface(
            onClick = { if (models.isNotEmpty()) open = true },
            enabled = models.isNotEmpty(),
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
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            models.forEach { model ->
                MenuOption(
                    text = model,
                    modifier = Modifier.width(240.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerContent(
    roots: List<MessageNode>,
    activeRoot: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onExport: (String) -> Unit,
    onImport: () -> Unit,
    onBackupAll: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.85f)) {
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
            IconButton(onClick = onImport, modifier = Modifier.size(40.dp)) {
                Icon(FolderOpenIcon, contentDescription = "Import conversations")
            }
            IconButton(onClick = onBackupAll, modifier = Modifier.size(40.dp)) {
                Icon(SaveAltIcon, contentDescription = "Back up all chats")
            }
            IconButton(onClick = onNewChat, modifier = Modifier.size(40.dp)) {
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
                DrawerChatItem(
                    title = chatTitle(root),
                    selected = root.id == activeRoot,
                    onClick = { onSelect(root.id) },
                    onRename = { onRename(root.id) },
                    onDelete = { onDeleteChat(root.id) },
                    onExport = { onExport(root.id) },
                )
            }
        }
    }
}

@Composable
private fun DrawerChatItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    // Fade the pill between focused and unfocused states (RN parity).
    val pillColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        animationSpec = tween(450),
        label = "chatPillBg",
    )
    val pillTextColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(450),
        label = "chatPillText",
    )
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = pillColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onClick() },
                            onLongPress = { pressOffset = it; menuOpen = true },
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    // Dim just the label while pressed as the long-press cue.
                    color = if (pressed) pillTextColor.copy(alpha = 0.5f) else pillTextColor,
                )
            }
        }
        TapContextMenu(
            expanded = menuOpen,
            touchOffset = pressOffset,
            onDismiss = { menuOpen = false },
        ) {
            MenuOption(
                text = "Rename",
                trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menuOpen = false; onRename() },
            )
            MenuOption(
                text = "Export",
                trailingIcon = { Icon(FileDownloadIcon, contentDescription = null) },
                onClick = { menuOpen = false; onExport() },
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
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/**
 * A single pop-up menu row that dims its content while pressed instead of
 * drawing a full-width highlight (no ripple/state-layer). Shared by the message,
 * drawer, and model-picker menus.
 */
@Composable
private fun MenuOption(
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
 * [Popup] with a custom position provider because [DropdownMenu] only supports
 * anchor-relative offsets, which drift to screen edges for wide anchors.
 */
@Composable
private fun TapContextMenu(
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

@Composable
private fun MessageItem(
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
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
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
            if (isUser) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
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
    val active = busy || canSend

    // Single full-width pill (RN prompt-input-group): the input spans the width
    // and the send/stop button lives inside the pill at the right end.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(30.dp),
            )
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 120.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 5,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Message",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        val fabColor by animateColorAsState(
            targetValue = if (active) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = tween(400),
            label = "fabColor",
        )
        val arrowTint by animateColorAsState(
            targetValue = if (canSend) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(400),
            label = "arrowTint",
        )
        Box(modifier = Modifier.background(fabColor, CircleShape)) {
            IconButton(
                onClick = {
                    if (busy) {
                        onStop()
                    } else if (canSend) {
                        onSubmit(text)
                        text = ""
                    }
                },
                enabled = active,
            ) {
                Crossfade(targetState = busy, animationSpec = tween(400), label = "sendStop") { streaming ->
                    // Fixed-size, center-aligned box so the small stop square stays
                    // centered during the crossfade instead of snapping from the
                    // top-start corner of the larger send arrow's bounds.
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (streaming) {
                            // Perfectly square = stop (material-icons-core has no Stop glyph).
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .background(Color.Black),
                            )
                        } else {
                            Icon(
                                ArrowUpwardIcon,
                                contentDescription = "Send",
                                tint = arrowTint,
                            )
                        }
                    }
                }
            }
        }
    }
}
