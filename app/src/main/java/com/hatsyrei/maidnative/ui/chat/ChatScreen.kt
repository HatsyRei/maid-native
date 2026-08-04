package com.hatsyrei.maidnative.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.ui.markdown.clearMarkdownParseCache
import com.hatsyrei.maidnative.ui.markdown.rememberChatStreamingMarkdownState
import kotlin.math.abs
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
    // One slot rather than four independent flags: the dialogs are mutually
    // exclusive by nature, and the type now says so.
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }
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

    // `getRoots` scans every node of every conversation, and the drawer is
    // composed even while closed, so an inline call would re-scan (and hand the
    // drawer a fresh list instance, defeating skipping) on every streamed token.
    // `mappings` is reference-stable while streaming, so this recomputes only on
    // real tree changes.
    val roots = remember(state.mappings) { MessageTree.getRoots(state.mappings) }

    // The sheet is hit-testable at every drag offset, so treat it as inert until
    // it is fully open. The offset is the only exact signal: `currentValue` is the
    // *settled* value (still `Open` throughout a closing drag) and
    // `isAnimationRunning` is false while a finger is dragging. `Open` is anchored
    // at 0f (material3 `ModalNavigationDrawer`); the epsilon is sub-pixel slack so
    // an animation that lands a hair off zero can't leave the drawer inert.
    val drawerSettled by remember(drawerState) {
        derivedStateOf {
            drawerState.currentValue == DrawerValue.Open && abs(drawerState.currentOffset) < 1f
        }
    }

    // One menu at a time across both the message list and the drawer: Compose
    // gives sibling nodes no gesture arbitration, so two simultaneous
    // long-presses would otherwise each open their own popup.
    val menus = remember { MenuController() }

    CompositionLocalProvider(LocalMenuController provides menus) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    roots = roots,
                    activeRoot = state.root,
                    interactive = drawerSettled,
                    onSelect = { id -> onSelectChat(id) },
                    onNewChat = { onNewChat() },
                    onRename = { id, title -> dialog = ChatDialog.Rename(id, title) },
                    onDeleteChat = { id -> dialog = ChatDialog.DeleteChat(id) },
                    onExport = { id ->
                        exportRoot = id
                        exportLauncher.launch(exportFileName(id))
                    },
                    onImport = { importLauncher.launch(arrayOf("application/json")) },
                    onBackupAll = { backupLauncher.launch(null) },
                )
            },
        ) {
            // Swiping the drawer open is a left-edge gesture, not a whole-screen one.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .restrictDrawerOpenDrag(enabled = drawerState.isClosed),
            ) {
                ChatScaffold(
                    state = state,
                    listState = listState,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = onOpenSettings,
                    onSubmit = onSubmit,
                    onStop = onStop,
                    onRegenerate = onRegenerate,
                    onDelete = { id -> dialog = ChatDialog.DeleteMessage(id) },
                    onPrevBranch = onPrevBranch,
                    onNextBranch = onNextBranch,
                    onSelectModel = onSelectModel,
                    onRequestEdit = { id, initial, revise ->
                        dialog = ChatDialog.Edit(id, initial, revise)
                    },
                )
            }
        }
    }

    val dismiss = { dialog = null }
    when (val current = dialog) {
        null -> Unit

        is ChatDialog.Edit -> EditDialog(
            initial = current.initial,
            revise = current.revise,
            onDismiss = dismiss,
            onConfirm = { text ->
                if (current.revise) onRevise(current.id, text) else onEdit(current.id, text)
                dismiss()
            },
        )

        is ChatDialog.Rename -> RenameDialog(
            initial = current.initial,
            onDismiss = dismiss,
            onConfirm = { title ->
                onRenameChat(current.id, title)
                dismiss()
            },
        )

        is ChatDialog.DeleteMessage -> ConfirmDialog(
            title = "Delete message",
            message = "Delete this message and everything below it? This can't be undone.",
            onDismiss = dismiss,
            onConfirm = {
                onDelete(current.id)
                dismiss()
            },
        )

        is ChatDialog.DeleteChat -> ConfirmDialog(
            title = "Delete conversation",
            message = "Delete this conversation permanently? This can't be undone.",
            onDismiss = dismiss,
            onConfirm = {
                onDeleteChat(current.id)
                dismiss()
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
            // Reset to the top of the conversation whenever the active chat
            // changes. Guarded against re-firing on a fresh composition: this
            // screen is disposed and rebuilt every time the user visits
            // Settings, and an unguarded `LaunchedEffect(state.root)` would
            // then scroll back to the bottom and throw away the scroll
            // position that `SaveableStateHolder` just restored.
            var settledRoot by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(state.root) {
                if (state.root == settledRoot) return@LaunchedEffect
                settledRoot = state.root
                // Drop the previous chat's cached markdown parses so their ASTs
                // don't linger on the heap; the new conversation reparses on demand.
                clearMarkdownParseCache()
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
                // Precompute the parent -> children grouping once per structural
                // change instead of re-scanning every node's siblings on each
                // recomposition (getChildren is O(nodes), so the per-item lookup
                // was O(nodes^2) per frame in long chats). `state.mappings` is a
                // new instance only when the tree actually changes (edit, delete,
                // regenerate), so this stays valid across those and is skipped
                // during streaming (same instance, reference-equal key).
                val childrenByParent = remember(state.mappings) {
                    state.mappings.values.groupBy { it.parent }
                }
                // Hoisted above the list on purpose: `LazyColumn` disposes items
                // that scroll out of view, and the incremental parser is
                // append-only, so it cannot be rebuilt from scratch mid-stream.
                val streamingId = state.streamingId
                val streamingMarkdown = if (streamingId != null) {
                    rememberChatStreamingMarkdownState(streamingId, state.streamingText)
                } else {
                    null
                }
                // `conversation` is a computed getter that walks the thread and
                // allocates a fresh list; read it exactly once per recomposition
                // instead of once per use site.
                val conversation = state.conversation
                val latestId = conversation.lastOrNull()?.id
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(conversation, key = { it.id }) { node ->
                        val siblings = node.parent?.let { childrenByParent[it] }
                            ?: emptyList()
                        val index = siblings.indexOfFirst { it.id == node.id }
                        MessageItem(
                            node = node,
                            siblingIndex = index,
                            siblingCount = siblings.size,
                            busy = state.busy,
                            ready = state.ready,
                            isLatest = node.id == latestId,
                            onRegenerate = { onRegenerate(node.id) },
                            onDelete = { onDelete(node.id) },
                            onRequestEdit = { revise ->
                                onRequestEdit(node.id, node.content, revise)
                            },
                            onPrevBranch = { node.parent?.let(onPrevBranch) },
                            onNextBranch = { node.parent?.let(onNextBranch) },
                            streamingState = streamingMarkdown.takeIf { node.id == streamingId },
                        )
                    }
                    if (conversation.isNotEmpty()) {
                        item(key = "__bottom_spacer__") {
                            Spacer(Modifier.height(spacerHeight))
                        }
                    }
                }
                DraggableScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.TopEnd),
                    trailingSpacerHeight = spacerHeight,
                    resetKey = state.root,
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
