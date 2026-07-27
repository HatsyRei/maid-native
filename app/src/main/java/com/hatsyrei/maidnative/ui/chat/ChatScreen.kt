package com.hatsyrei.maidnative.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.tree.MessageTree
import kotlinx.coroutines.launch

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
    listState: LazyListState,
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
                    if (state.models.isNotEmpty()) {
                        ModelSelector(
                            models = state.models,
                            selected = state.settings.model,
                            onSelect = onSelectModel,
                        )
                    }
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
