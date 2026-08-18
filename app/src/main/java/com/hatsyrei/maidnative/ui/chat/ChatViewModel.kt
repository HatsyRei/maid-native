package com.hatsyrei.maidnative.ui.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatsyrei.maidnative.data.db.MaidDatabase
import com.hatsyrei.maidnative.data.db.MessageRepository
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.remote.EndpointScanner
import com.hatsyrei.maidnative.data.remote.OpenAiClient
import com.hatsyrei.maidnative.data.store.AttachmentStore
import com.hatsyrei.maidnative.data.store.ConversationFileStore
import com.hatsyrei.maidnative.data.store.MessageStore
import com.hatsyrei.maidnative.data.store.NameplateStore
import com.hatsyrei.maidnative.data.store.attachments
import com.hatsyrei.maidnative.data.store.withAttachments
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.domain.ConversationDefaults
import com.hatsyrei.maidnative.domain.Modalities
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.Mappings
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.domain.tree.validateMappings
import com.hatsyrei.maidnative.ui.theme.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Immutable
data class ChatUiState(
    val mappings: Mappings = LinkedHashMap(),
    val root: String? = null,
    val models: List<String> = emptyList(),
    val modalities: Map<String, Modalities> = emptyMap(),
    val pendingAttachments: List<Attachment> = emptyList(),
    val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val refreshingModels: Boolean = false,
    val foundURL: String? = null,
    val error: String? = null,
    val streamingId: String? = null,
    val streamingText: String = "",
    val streamingReasoning: String = "",
) {
    /**
     * Visible conversation = active thread from root, skipping the system node.
     * While a stream is in flight the growing reply lives in [streamingText]
     * (not the tree), so overlay it onto the streaming node here. The tree map
     * itself is left untouched per token and rewritten exactly once when the
     * stream ends or is stopped.
     *
     * [streamingText] is the reply only — the trace is carried separately in
     * [streamingReasoning] — so nothing downstream has to re-split it per token.
     */
    val conversation: List<MessageNode>
        get() {
            val thread = root?.let { MessageTree.getConversation(mappings, it).drop(1) }
                ?: return emptyList()
            val id = streamingId ?: return thread
            return thread.map { if (it.id == id) it.copy(content = streamingText) else it }
        }

    /**
     * The system prompt in force for the active chat: the root node *is* the
     * system message. Until a conversation exists there is no node to read, so
     * fall back to the default every new chat would be seeded with.
     */
    val systemPrompt: String
        get() = root?.let { mappings[it]?.content }
            ?: settings.systemPrompt.ifEmpty { SettingsRepository.DEFAULT_SYSTEM_PROMPT }

    val ready: Boolean
        get() = settings.model.isNotEmpty() && models.contains(settings.model)

    /**
     * Capabilities of the model that would receive the next message. Absent
     * from the map means the endpoint never described it, which stays UNKNOWN
     * rather than becoming a denial.
     */
    val activeModalities: Modalities
        get() = modalities[settings.model] ?: Modalities.UNKNOWN
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val client = OpenAiClient()
    private val repo = MessageRepository(MaidDatabase.get(app).messageDao())
    private val fileStore = ConversationFileStore(app.contentResolver)
    private val nameplateStore = NameplateStore(app)
    private val attachmentStore = AttachmentStore(app)
    private val legacyFile = File(app.filesDir, "messages.json")

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // Kept apart from [state] so the theme (which sits above the whole app) is
    // not recomposed by every streamed token.
    val theme: StateFlow<ThemeSettings> = settingsRepo.settings
        .map { ThemeSettings(it.accentColor, it.nameplate, it.nameplateStamp) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())

    /** Saved endpoints, kept out of [state] since only Settings reads them. */
    val presets: StateFlow<List<SettingsRepository.EndpointPreset>> = settingsRepo.presets
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var streamJob: Job? = null

    // Accumulates the in-flight reply. Appending to a StringBuilder is amortised
    // O(1); the previous `streamingText + chunk` reallocated and copied the whole
    // reply per token, which is quadratic over a response and was the largest
    // single source of CPU work and GC churn during generation.
    //
    // The trace is kept in its own buffer for the same reason: classifying it
    // once, as it arrives, avoids re-scanning the accumulated text every tick.
    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()

    // Lengths at the last UI publish, so an idle tick can skip emitting an
    // identical state (and the O(n) `toString` behind it), and so a tick that
    // only grew one of the two does not copy the other.
    private var publishedContentLength = 0
    private var publishedReasoningLength = 0

    // Guards the automatic model fetch so it runs at most once per app launch
    // (plus once per endpoint change). A failed fetch leaves the models list
    // empty; without this flag the settings collector would re-fetch on every
    // subsequent settings emission, hammering an unreachable endpoint and
    // draining the battery. After a failure the user re-triggers a fetch
    // manually via the Settings refresh/scan buttons.
    private var autoFetchedModels = false

    // Coalesces persist requests: a single consumer serializes writes (so the
    // repository's diff state is never touched concurrently) and CONFLATED
    // keeps only the latest pending snapshot, collapsing bursts (e.g. rapid
    // branch navigation) into one incremental write.
    private val saveRequests = Channel<Mappings>(Channel.CONFLATED)

    /**
     * Apply a pure [MessageTree] operation to the current tree, commit it, and
     * persist. Every tree edit shares this shape: refuse while a stream is in
     * flight, then honour the referential-equality no-op that [MessageTree]
     * returns when the operation changed nothing.
     */
    private inline fun mutateTree(op: (Mappings) -> Mappings) {
        if (_state.value.busy) return
        val current = _state.value.mappings
        val next = op(current)
        if (next === current) return
        _state.update { it.copy(mappings = next) }
        persist()
    }

    init {
        viewModelScope.launch {
            val loaded = repo.load(legacyFile)
            val roots = MessageTree.getRoots(loaded)
            // Reopen whatever chat the last session ended on; fall back to the
            // first conversation if it has since been deleted.
            val remembered = settingsRepo.activeChat.first()
            val root = roots.firstOrNull { it.id == remembered }?.id ?: roots.firstOrNull()?.id
            _state.update { it.copy(mappings = loaded, root = root) }
            withContext(Dispatchers.IO) {
                // Pending files are read back from state, not `loaded`: a pick
                // that beat the load home would otherwise be swept out of it.
                val referenced = loaded.values
                    .flatMapTo(HashSet()) { node -> node.attachments().map { it.path } }
                _state.value.pendingAttachments.mapTo(referenced) { it.path }
                attachmentStore.sweep(referenced)
            }
            // Recorded here rather than at each call site that moves the active
            // root (new/select/delete/first submit).
            _state.map { it.root }
                .distinctUntilChanged()
                .collect { settingsRepo.setActiveChat(it) }
        }
        viewModelScope.launch {
            saveRequests.consumeAsFlow().collect { snapshot ->
                runCatching { repo.save(snapshot) }
            }
        }
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                val changedEndpoint = s.baseURL != _state.value.settings.baseURL ||
                    s.apiKey != _state.value.settings.apiKey
                _state.update { it.copy(settings = s) }
                // Fetch models once on launch, and again only when the endpoint
                // actually changes. Never auto-retry after a failure: doing so
                // would re-connect on every settings emission and drain the
                // battery. The user re-triggers a fetch via the Settings
                // refresh/scan buttons (which call refreshModels directly).
                if (!autoFetchedModels || changedEndpoint) {
                    val firstLaunch = !autoFetchedModels
                    autoFetchedModels = true
                    // The startup fetch fails silently so opening the app
                    // offline doesn't cover the (locally-stored) conversation
                    // with a network-error banner. An endpoint change is an
                    // explicit user action, so surface its failures.
                    refreshModels(silent = firstLaunch)
                }
            }
        }
    }

    /** Manual model refresh (Settings button / scan): surfaces failures to the user. */
    fun refreshModels() = refreshModels(silent = false)

    private fun refreshModels(silent: Boolean) {
        // Ignore overlapping requests (e.g. spam-tapping Refresh) so we never
        // fire N concurrent /models calls. The button is also disabled in the
        // UI while this flag is set; this guard covers the non-UI callers.
        if (_state.value.refreshingModels) return
        _state.update { it.copy(refreshingModels = true) }
        viewModelScope.launch {
            try {
                val s = _state.value.settings
                val result = withContext(Dispatchers.IO) {
                    runCatching { client.listModels(OpenAiClient.Config(s.baseURL, s.apiKey, s.model)) }
                }
                result.onSuccess { fetched ->
                    val ids = fetched.map { it.id }
                    val current = _state.value.settings.model
                    _state.update { state ->
                        state.copy(
                            models = ids,
                            modalities = fetched.associate { it.id to it.modalities },
                            error = null,
                        )
                    }
                    // Auto-select first model if none valid (mirrors open-ai.tsx).
                    if (ids.isNotEmpty() && (current.isEmpty() || !ids.contains(current))) {
                        setModel(ids.first())
                    }
                }.onFailure { failure ->
                    // Silent (startup) failures leave the reading surface clean;
                    // user-initiated failures show the error banner.
                    _state.update {
                        it.copy(
                            models = emptyList(),
                            modalities = emptyMap(),
                            error = if (silent) it.error else failure.message,
                        )
                    }
                }
            } finally {
                _state.update { it.copy(refreshingModels = false) }
            }
        }
    }

    fun setBaseURL(value: String) = viewModelScope.launch { settingsRepo.setBaseURL(value) }
    fun setApiKey(value: String) = viewModelScope.launch { settingsRepo.setApiKey(value) }
    fun setModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }
    fun setReasoning(enabled: Boolean) = viewModelScope.launch { settingsRepo.setReasoning(enabled) }

    fun setExportMedia(enabled: Boolean) = viewModelScope.launch { settingsRepo.setExportMedia(enabled) }
    fun setAccentColor(argb: Int) = viewModelScope.launch { settingsRepo.setAccentColor(argb) }
    fun setUserName(value: String) = viewModelScope.launch { settingsRepo.setUserName(value) }
    fun setAssistantName(value: String) = viewModelScope.launch { settingsRepo.setAssistantName(value) }
    fun setNameplate(value: String) = viewModelScope.launch { settingsRepo.setNameplate(value) }

    fun savePreset(name: String, baseURL: String, apiKey: String) =
        viewModelScope.launch { settingsRepo.savePreset(name, baseURL, apiKey) }

    fun applyPreset(preset: SettingsRepository.EndpointPreset) =
        viewModelScope.launch { settingsRepo.applyPreset(preset) }

    fun renamePreset(id: String, name: String) =
        viewModelScope.launch { settingsRepo.renamePreset(id, name) }

    fun deletePreset(id: String) = viewModelScope.launch { settingsRepo.deletePreset(id) }

    /** Copies the picked image into app storage, then selects it. */
    fun importNameplate(uri: Uri) = viewModelScope.launch {
        val imported = withContext(Dispatchers.IO) {
            runCatching { nameplateStore.import(uri) }.getOrDefault(false)
        }
        if (imported) {
            settingsRepo.setCustomNameplate(System.currentTimeMillis())
        } else {
            _state.update { it.copy(error = "That image could not be loaded.") }
        }
    }

    /** Clear transient scan state so re-entering Settings shows a fresh scan button. */
    fun resetScan() {
        _state.update { it.copy(scanning = false, foundURL = null) }
    }

    /**
     * Sweep the local subnet for an OpenAI-compatible endpoint on [port] and
     * adopt the first match. The options are persisted first so the scan dialog
     * reopens with the same choice. On success the endpoint is saved
     * automatically, which triggers model loading via the settings collector.
     */
    fun scanEndpoint(port: Int, prefixLength: Int) {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, foundURL = null, error = null) }
        viewModelScope.launch {
            settingsRepo.setScanOptions(port, prefixLength)
            val found = withContext(Dispatchers.IO) {
                runCatching { EndpointScanner.scanForEndpoint(port, prefixLength) }
            }
            found.onSuccess { url ->
                if (url != null) {
                    _state.update { it.copy(foundURL = url) }
                    settingsRepo.setBaseURL(url) // triggers refreshModels via the settings collector
                } else {
                    _state.update {
                        it.copy(
                            error = "No OpenAI-compatible endpoint answered on port $port " +
                                "in the local /$prefixLength.",
                        )
                    }
                }
            }.onFailure { failure ->
                _state.update { it.copy(error = failure.message) }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    /**
     * Seed a conversation: its root node *is* the system message. [prompt]
     * defaults to the configured persona, which is what every path that creates
     * a chat implicitly wants.
     */
    private fun addSystemRoot(
        mappings: Mappings,
        id: String,
        prompt: String = _state.value.settings.systemPrompt
            .ifEmpty { SettingsRepository.DEFAULT_SYSTEM_PROMPT },
    ): Mappings = MessageTree.addNode(
        mappings, id, "system", prompt,
        null, null, null, mapOf("title" to ConversationDefaults.CHAT_TITLE),
    )

    fun newChat() {
        // Mirror drawer-content.tsx `createChat`: create the system root node up
        // front so the conversation shows in the drawer immediately, then make it
        // the active chat. The first `submit` then attaches to this existing root.
        val rootId = UUID.randomUUID().toString()
        _state.update { it.copy(mappings = addSystemRoot(it.mappings, rootId), root = rootId) }
        persist()
    }

    /** Switch the active conversation to an existing root. */
    fun selectChat(rootId: String) {
        if (rootId == _state.value.root) return
        _state.update { it.copy(root = rootId) }
    }

    /**
     * Edit the active conversation's system prompt, i.e. its root node's content.
     * With no conversation yet the root is materialised here, so a persona can be
     * set before the first message (`submit` then attaches to this root).
     */
    fun setSystemPrompt(text: String) {
        val prompt = text.trim().ifEmpty { ConversationDefaults.SYSTEM_PROMPT }
        val rootId = _state.value.root
        if (rootId != null) {
            mutateTree { MessageTree.setContent(it, rootId, prompt) }
            return
        }
        val id = UUID.randomUUID().toString()
        _state.update { it.copy(mappings = addSystemRoot(it.mappings, id, prompt), root = id) }
        persist()
    }

    /** Rename a conversation by updating its root node's title metadata. */
    fun renameChat(rootId: String, title: String) {
        val t = title.trim().ifEmpty { ConversationDefaults.CHAT_TITLE }
        mutateTree { MessageTree.updateContent(it, rootId, { c -> c }, { m -> m + ("title" to t) }) }
    }

    /** Delete an entire conversation (root + descendants). */
    fun deleteChat(rootId: String) = deleteSubtree(rootId)

    /**
     * Suggested export filename for a conversation: its title + `.json`, matching
     * the RN app (`${title || "New Chat"}.json`). Only path separators are
     * stripped so titles like "Steampunk D&D" survive intact.
     */
    fun exportFileName(rootId: String): String {
        val title = _state.value.mappings[rootId]?.metadata?.get("title") as? String
        val safe = (title?.trim().orEmpty().ifEmpty { ConversationDefaults.CHAT_TITLE }).replace('/', '_')
        return "$safe.json"
    }

    /** Write a single conversation (root + descendants) to [uri] as JSON. */
    fun exportConversation(rootId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val nodes = _state.value.mappings.values.filter { it.root == rootId }
                if (nodes.isEmpty()) error("Conversation is empty.")
                val media = _state.value.settings.exportMedia
                fileStore.write(uri, MessageStore.encodeExport(nodes.map { attachmentStore.embed(it, media) }))
            }.onFailure { failure ->
                _state.update { it.copy(error = "Export failed: ${failure.message}") }
            }
        }
    }

    /**
     * Back up every conversation into the user-picked directory [treeUri], one
     * `<title>.json` file per root (mirrors the RN "backup all chats" action).
     */
    fun backupAllChats(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = _state.value.mappings
                val media = _state.value.settings.exportMedia
                val files = MessageTree.getRoots(snapshot).map { root ->
                    val nodes = snapshot.values.filter { it.root == root.id }
                    exportFileName(root.id) to
                        MessageStore.encodeExport(nodes.map { attachmentStore.embed(it, media) })
                }
                fileStore.backup(treeUri, files)
            }.onFailure { failure ->
                _state.update { it.copy(error = "Backup failed: ${failure.message}") }
            }
        }
    }

    /**
     * Import one or more conversation files, mirroring the RN `loadMappings`:
     * each file is parsed, validated, then merged into the store **by original
     * id** (so re-importing the same export is idempotent rather than creating
     * duplicates). The active conversation is left unchanged.
     */
    fun importConversations(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val before = _state.value.mappings
            val merged = withContext(Dispatchers.IO) {
                val next = LinkedHashMap(before)
                for (uri in uris) {
                    runCatching {
                        val text = fileStore.read(uri)
                        val parsed = LinkedHashMap<String, MessageNode>()
                        for (node in MessageStore.decodeExport(text)) {
                            parsed[node.id] = attachmentStore.materialize(node)
                        }
                        next.putAll(validateMappings(parsed))
                    }
                }
                next
            }
            if (merged.size != before.size || merged != before) {
                _state.update { it.copy(mappings = merged) }
                persist()
                // Re-importing an export replaces its nodes, stranding the
                // files the previous import had unpacked for them.
                pruneOrphans(before, merged)
            }
        }
    }

    /** Mirrors prompt-button.tsx: build system/user/assistant nodes, then stream. */
    fun submit(text: String) {
        val prompt = text.trim()
        val pending = _state.value.pendingAttachments
        if ((prompt.isEmpty() && pending.isEmpty()) || _state.value.busy) return

        var next = _state.value.mappings
        val existingRoot = _state.value.root

        val parent: String
        if (existingRoot != null) {
            val thread = MessageTree.getConversation(next, existingRoot)
            parent = thread.last().id
        } else {
            parent = UUID.randomUUID().toString()
            next = addSystemRoot(next, parent)
        }
        val rootId = existingRoot ?: parent

        val userId = UUID.randomUUID().toString()
        next = MessageTree.addNode(
            next, userId, "user", prompt, rootId, parent, null,
            withAttachments(emptyMap(), pending),
        )

        val responseId = UUID.randomUUID().toString()
        next = MessageTree.addNode(next, responseId, "assistant", "", rootId, userId)

        // The attachments now belong to the message, so the composer lets go of
        // them without deleting the files.
        _state.update { it.copy(mappings = next, root = rootId, pendingAttachments = emptyList()) }
        startStream(rootId, responseId)
    }

    /** Copy a picked file into app storage and stage it on the next message. */
    fun attach(uri: Uri, kind: Attachment.Kind) {
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { attachmentStore.import(uri, kind) }) {
                is AttachmentStore.ImportResult.Failure ->
                    _state.update { it.copy(error = result.reason) }
                is AttachmentStore.ImportResult.Success ->
                    _state.update {
                        it.copy(pendingAttachments = it.pendingAttachments + result.attachment, error = null)
                    }
            }
        }
    }

    fun removeAttachment(attachment: Attachment) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments - attachment) }
        viewModelScope.launch(Dispatchers.IO) { attachmentStore.delete(listOf(attachment)) }
    }

    /** Copy a stored attachment out to a user-picked [uri]. */
    fun saveAttachment(attachment: Attachment, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val source = File(attachment.path)
                if (!source.isFile) error("File is no longer available.")
                fileStore.write(uri, source)
            }.onFailure { failure ->
                // The picker already created the document, so a failed write
                // would otherwise leave an empty file behind.
                fileStore.discard(uri)
                _state.update { it.copy(error = "Save failed: ${failure.message}") }
            }
        }
    }

    /** Regenerate an assistant reply as a new sibling branch (mirrors message-menu.tsx). */
    fun regenerate(messageId: String) {
        if (_state.value.busy) return
        val node = _state.value.mappings[messageId] ?: return
        val responseId = UUID.randomUUID().toString()
        val next = MessageTree.branchNode(_state.value.mappings, messageId, responseId, "")
        if (next === _state.value.mappings) return
        _state.update { it.copy(mappings = next) }
        startStream(node.root, responseId)
    }

    /** Revise a user message: branch a new version and regenerate the reply. */
    fun revise(messageId: String, content: String, attachments: List<Attachment>) {
        val text = content.trim()
        if (_state.value.busy || (text.isEmpty() && attachments.isEmpty())) return
        val node = _state.value.mappings[messageId] ?: return
        val userId = UUID.randomUUID().toString()
        // The branch carries whatever survived the dialog; files it still shares
        // with the original are left alone, since the original still names them.
        var next = MessageTree.branchNode(
            _state.value.mappings, messageId, userId, text,
            withAttachments(emptyMap(), attachments),
        )
        if (next === _state.value.mappings) return
        val responseId = UUID.randomUUID().toString()
        next = MessageTree.addNode(next, responseId, "assistant", "", node.root, userId)
        _state.update { it.copy(mappings = next) }
        startStream(node.root, responseId)
    }

    /** Edit a message's content and attachments in place without regenerating. */
    fun editMessage(messageId: String, content: String, attachments: List<Attachment>) {
        val before = _state.value.mappings
        mutateTree {
            MessageTree.updateContent(it, messageId, { content.trim() }) { metadata ->
                withAttachments(metadata, attachments)
            }
        }
        pruneOrphans(before, _state.value.mappings)
    }

    /** Delete a message and everything below it. */
    fun deleteMessage(messageId: String) = deleteSubtree(messageId)

    /**
     * Remove [id] and its descendants. When that takes the active conversation
     * with it — either because [id] *is* the active root, or because the whole
     * thread went with it — fall back to the first remaining root. Both cases
     * reduce to "the active root is no longer in the tree", so conversation and
     * message deletion are the same operation.
     */
    private fun deleteSubtree(id: String) {
        if (_state.value.busy) return
        val current = _state.value.mappings
        val next = MessageTree.deleteNode(current, id)
        if (next === current) return
        pruneOrphans(current, next)
        val active = _state.value.root
        val root = if (active != null && active !in next) {
            MessageTree.getRoots(next).firstOrNull()?.id
        } else {
            active
        }
        _state.update { it.copy(mappings = next, root = root) }
        persist()
    }

    /**
     * Delete attachment files that [before] named and [after] no longer does.
     * Nothing else owns them, so they would otherwise sit in filesDir forever.
     * Branches share their original's files, hence the comparison by path
     * against everything that survives rather than per node.
     */
    private fun pruneOrphans(before: Mappings, after: Mappings) {
        val kept = after.values.flatMapTo(HashSet()) { node -> node.attachments().map { it.path } }
        val removable = before.values
            .flatMap { it.attachments() }
            .filterNot { it.path in kept }
            .distinctBy { it.path }
        if (removable.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { attachmentStore.delete(removable) }
    }

    /** Switch to the previous sibling branch under [parentId]. */
    fun prevBranch(parentId: String) = mutateTree { MessageTree.lastChild(it, parentId) }

    /** Switch to the next sibling branch under [parentId]. */
    fun nextBranch(parentId: String) = mutateTree { MessageTree.nextChild(it, parentId) }

    private fun startStream(rootId: String, responseId: String) {
        val s = _state.value.settings
        _state.update {
            it.copy(
                busy = true,
                error = null,
                streamingId = responseId,
                streamingText = "",
                streamingReasoning = "",
            )
        }
        persist()
        val conversation = MessageTree.getConversation(_state.value.mappings, rootId)
        contentBuffer.setLength(0)
        reasoningBuffer.setLength(0)
        publishedContentLength = 0
        publishedReasoningLength = 0
        streamJob = viewModelScope.launch {
            // Publishing is demand-driven, never timer-driven: the pump sleeps on
            // `pending` and is woken only by an arriving token. A free-running
            // ticker would keep waking the main thread ~30x/second for as long as
            // the request was open, which on a stalled stream is pure drain. An
            // idle TCP socket, by contrast, costs essentially nothing.
            val pending = Channel<Unit>(Channel.CONFLATED)

            val pump = launch {
                for (signal in pending) {
                    publishStreamingText(responseId)
                    // Trailing throttle. Tokens arriving inside this window
                    // collapse into the single conflated signal that follows it,
                    // so the UI updates at most once per interval while the first
                    // token of a burst still lands immediately.
                    delay(PUBLISH_INTERVAL_MS)
                }
            }

            client.streamChat(OpenAiClient.Config(s.baseURL, s.apiKey, s.model, s.reasoning), conversation)
                // UNLIMITED rather than the default 64-slot buffer: the SSE
                // listener publishes with `trySend`, which silently drops a
                // chunk when the channel is full. Losing a token corrupts the
                // reply, and the buffer operator fuses with the callbackFlow's
                // own channel, so this just widens that one channel.
                .buffer(Channel.UNLIMITED)
                // Building the request reads every attachment off disk and
                // base64-encodes it, which must not happen on the main thread.
                // Fuses with the buffer above, so it stays one channel.
                .flowOn(Dispatchers.IO)
                .catch { e -> _state.update { it.copy(error = e.message) } }
                .collect { chunk ->
                    when (chunk) {
                        is Reasoning.Chunk.Reply -> contentBuffer.append(chunk.text)
                        is Reasoning.Chunk.Thought -> reasoningBuffer.append(chunk.text)
                        // A chat template supplied the opening tag, so the reply
                        // so far was really the trace. Both buffers live here, so
                        // the correction is a move rather than a re-parse.
                        Reasoning.Chunk.Reclassify -> {
                            reasoningBuffer.append(contentBuffer)
                            contentBuffer.setLength(0)
                        }
                    }
                    pending.trySend(Unit)
                }

            pump.cancel()
            finishStreaming()
        }
    }

    fun stop() {
        // Cancelling the job unwinds the collector, which trips `awaitClose` in
        // OpenAiClient.streamChat and cancels the underlying EventSource — so
        // this releases the socket, not just the UI state.
        streamJob?.cancel()
        finishStreaming()
    }

    /**
     * Push the accumulated reply into UI state, skipping the copy entirely when
     * nothing new has arrived since the last tick. Guards against a stale tick
     * from a superseded job.
     */
    private fun publishStreamingText(responseId: String) {
        if (_state.value.streamingId != responseId) return
        // Not `>`: a reclassification shrinks the reply and grows the trace.
        val contentChanged = contentBuffer.length != publishedContentLength
        val reasoningChanged = reasoningBuffer.length != publishedReasoningLength
        if (!contentChanged && !reasoningChanged) return
        publishedContentLength = contentBuffer.length
        publishedReasoningLength = reasoningBuffer.length
        _state.update {
            it.copy(
                streamingText = if (contentChanged) contentBuffer.toString() else it.streamingText,
                streamingReasoning =
                    if (reasoningChanged) reasoningBuffer.toString() else it.streamingReasoning,
            )
        }
    }

    private fun finishStreaming() {
        if (!_state.value.busy) return
        // Commit the accumulated reply into the tree in a single map write, then
        // persist once (per-token writes were intentionally suppressed above).
        // Read from the buffers, not from state: `stop()` cancels the job
        // mid-cadence, so state can lag them by up to one tick.
        val id = _state.value.streamingId
        val text = committedText()
        val committed = if (id != null) {
            MessageTree.setContent(_state.value.mappings, id, text)
        } else {
            _state.value.mappings
        }
        _state.update {
            it.copy(
                mappings = committed,
                busy = false,
                streamingId = null,
                streamingText = "",
                streamingReasoning = "",
            )
        }
        contentBuffer.setLength(0)
        reasoningBuffer.setLength(0)
        publishedContentLength = 0
        publishedReasoningLength = 0
        persist()
    }

    /**
     * Rejoins the two buffers into the single string the tree and the database
     * store. Keeping one content column means no schema migration and no second
     * write path; `Reasoning.split` takes it apart again once, on render.
     */
    private fun committedText(): String {
        val reply = contentBuffer.toString().trim()
        val thought = reasoningBuffer.toString().trim()
        if (thought.isEmpty()) return reply
        return buildString(thought.length + reply.length + THINK_WRAPPER_LENGTH) {
            append("<think>\n").append(thought).append("\n</think>\n\n").append(reply)
        }
    }

    private fun persist() {
        // Non-blocking: hand the latest snapshot to the single-consumer save
        // loop. The repository writes only the diff, and skips the DB entirely
        // when nothing changed.
        saveRequests.trySend(_state.value.mappings)
    }

    private companion object {
        /**
         * Minimum spacing between UI publishes of a growing reply (~30 Hz).
         * Text streaming reads as perfectly smooth at this rate while doing far
         * less recomposition work than the token rate, which on a fast local
         * endpoint can exceed the display refresh rate.
         */
        const val PUBLISH_INTERVAL_MS = 33L

        /** `<think>\n` + `\n</think>\n\n`, pre-sized so the join never regrows. */
        const val THINK_WRAPPER_LENGTH = 21
    }
}
