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
import com.hatsyrei.maidnative.data.store.AvatarStore
import com.hatsyrei.maidnative.data.store.attachments
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.ui.common.TextInputDialog
import com.hatsyrei.maidnative.ui.common.rememberAvatar
import com.hatsyrei.maidnative.ui.markdown.clearMarkdownParseCache
import com.hatsyrei.maidnative.ui.markdown.rememberChatStreamingMarkdownState
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
) {
    val listState = rememberLazyListState()
    // One slot rather than four independent flags: the dialogs are mutually
    // exclusive by nature, and the type now says so.
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }
    var exportRoot by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val root = exportRoot
        if (uri != null && root != null) actions.exportConversation(root, uri)
        exportRoot = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) actions.importConversations(uris) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) actions.backupAllChats(uri) }

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
                    // The sheet is hit-testable at every drag offset, so treat it as
                    // inert until it is fully open. The offset is the only exact
                    // signal: `currentValue` is the *settled* value (still `Open`
                    // throughout a closing drag) and `isAnimationRunning` is false
                    // while a finger is dragging. `Open` is anchored at 0f; the
                    // epsilon is sub-pixel slack so a settle landing a hair off zero
                    // can't leave the drawer permanently inert.
                    settled = {
                        drawerState.currentValue == DrawerValue.Open &&
                            abs(drawerState.currentOffset) < 1f
                    },
                    onSelect = actions.selectChat,
                    onNewChat = actions.newChat,
                    onRename = { id, title -> dialog = ChatDialog.Rename(id, title) },
                    onDeleteChat = { id -> dialog = ChatDialog.DeleteChat(id) },
                    onExport = { id ->
                        exportRoot = id
                        exportLauncher.launch(actions.exportFileName(id))
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
                    .restrictDrawerOpenDrag(enabled = { drawerState.isClosed })
                    // The scrim only takes touches once the drawer has *settled* open,
                    // so the chat stays live behind a sheet that is animating over it.
                    // `isAnimationRunning` is true exactly when the drawer moves with
                    // no finger on it, so swallowing the gesture can't fight a drag.
                    .inertWhile { drawerState.isAnimationRunning },
            ) {
                ChatScaffold(
                    state = state,
                    listState = listState,
                    actions = actions,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenAttachment = { viewing = it },
                    onDialog = { dialog = it },
                )
            }
        }
    }

    val dismiss = { dialog = null }
    viewing?.let { attachment ->
        AttachmentViewer(
            attachment = attachment,
            onSave = { uri -> actions.saveAttachment(attachment, uri) },
            onDismiss = { viewing = null },
        )
    }
    when (val current = dialog) {
        null -> Unit

        is ChatDialog.Edit -> EditDialog(
            initial = current.initial,
            revise = current.revise,
            initialAttachments = current.attachments,
            onDismiss = dismiss,
            onConfirm = { text, attachments ->
                if (current.revise) {
                    actions.revise(current.id, text, attachments)
                } else {
                    actions.edit(current.id, text, attachments)
                }
                dismiss()
            },
        )

        is ChatDialog.Rename -> TextInputDialog(
            title = "Rename conversation",
            initial = current.initial,
            confirmLabel = "Rename",
            onDismiss = dismiss,
            onConfirm = { title ->
                actions.renameChat(current.id, title)
                dismiss()
            },
        )

        is ChatDialog.SystemPrompt -> TextInputDialog(
            title = "System prompt",
            initial = current.initial,
            confirmLabel = "Save",
            onDismiss = dismiss,
            onConfirm = { text ->
                actions.setSystemPrompt(text)
                dismiss()
            },
            description = "Applies to this conversation, including messages already sent.",
            multiline = true,
        )

        is ChatDialog.DeleteMessage -> ConfirmDialog(
            title = "Delete message",
            message = "Delete this message and everything below it? This can't be undone.",
            onDismiss = dismiss,
            onConfirm = {
                actions.deleteMessage(current.id)
                dismiss()
            },
        )

        is ChatDialog.DeleteChat -> ConfirmDialog(
            title = "Delete conversation",
            message = "Delete this conversation permanently? This can't be undone.",
            onDismiss = dismiss,
            onConfirm = {
                actions.deleteChat(current.id)
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
    actions: ChatActions,
    onOpenDrawer: () -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onDialog: (ChatDialog) -> Unit,
) {
    // A dismissed keyboard leaves the pill focused (blinking cursor); the next
    // touch on the conversation is what drops it. Kept out of the composer so a
    // list touch can reach it, and out of composition reads so an IME
    // transition doesn't invalidate the scaffold.
    val composerFocus = remember { ComposerFocus() }
    val focusManager = LocalFocusManager.current
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
                            onSelect = actions.selectModel,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions.openSettings) {
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
                    .fillMaxWidth()
                    .clearFocusOnTouch(
                        active = { composerFocus.stale },
                        onClear = { focusManager.clearFocus() },
                    ),
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
                // `streamingText` is already the reply alone — the trace never
                // reaches the markdown parser, where a raw `<think>` would be
                // read as an HTML block and swallow the text after it.
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
                // Decoded here rather than per bubble: every row would otherwise
                // hold its own copy of the same two bitmaps.
                val userAvatar = rememberAvatar(AvatarStore.Role.USER, state.settings.userAvatar)
                val assistantAvatar =
                    rememberAvatar(AvatarStore.Role.ASSISTANT, state.settings.assistantAvatar)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "__system_prompt__") {
                        SystemPromptCard(
                            prompt = state.systemPrompt,
                            enabled = !state.busy,
                            onEdit = { onDialog(ChatDialog.SystemPrompt(state.systemPrompt)) },
                        )
                    }
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
                            userName = state.settings.userName,
                            assistantName = state.settings.assistantName,
                            userAvatar = userAvatar,
                            assistantAvatar = assistantAvatar,
                            onRegenerate = { actions.regenerate(node.id) },
                            onDelete = { onDialog(ChatDialog.DeleteMessage(node.id)) },
                            onRequestEdit = { revise ->
                                onDialog(
                                    ChatDialog.Edit(node.id, node.content, revise, node.attachments()),
                                )
                            },
                            onOpenAttachment = onOpenAttachment,
                            onPrevBranch = { node.parent?.let(actions.prevBranch) },
                            onNextBranch = { node.parent?.let(actions.nextBranch) },
                            streamingState = streamingMarkdown.takeIf { node.id == streamingId },
                            streamingReasoning = state.streamingReasoning.takeIf {
                                node.id == streamingId
                            },
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
                focus = composerFocus,
                attachments = state.pendingAttachments,
                modalities = state.activeModalities,
                onAttach = actions.attach,
                onRemoveAttachment = actions.removeAttachment,
                onOpenAttachment = onOpenAttachment,
                onSubmit = actions.submit,
                onStop = actions.stop,
            )
        }
    }
}
