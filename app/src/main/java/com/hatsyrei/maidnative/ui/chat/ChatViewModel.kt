package com.hatsyrei.maidnative.ui.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatsyrei.maidnative.data.db.MaidDatabase
import com.hatsyrei.maidnative.data.db.MessageRepository
import com.hatsyrei.maidnative.data.prefs.SecretUnavailableException
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.remote.EndpointScanner
import com.hatsyrei.maidnative.data.remote.Endpoints
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
import com.hatsyrei.maidnative.domain.tree.Mappings
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.domain.tree.validateMappings
import com.hatsyrei.maidnative.ui.theme.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    /**
     * Something the app did to the stored credentials that the user has to know
     * about — a key dropped because the endpoint moved, or a key that could not
     * be stored. Kept apart from [error] so a subsequent model fetch, which
     * clears [error] on success, cannot wipe it.
     */
    val credentialNotice: String? = null,
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

    private val stream = StreamController(viewModelScope, client)

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
                if (cleartextBlocked(s.baseURL)) {
                    _state.update {
                        it.copy(
                            models = emptyList(),
                            modalities = emptyMap(),
                            error = if (silent) it.error else CLEARTEXT_BLOCKED,
                        )
                    }
                    return@launch
                }
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

    /**
     * True when [url] is plain HTTP and the user has not allowed that.
     *
     * Only [refreshModels] consults this. Configuring an endpoint — typing one,
     * applying a preset, adopting a scan result — is always allowed to succeed,
     * so the app never silently discards a URL the user asked for; the refusal
     * lands once, at the point the endpoint is actually contacted, where the
     * warning can name the fix.
     */
    private fun cleartextBlocked(url: String): Boolean =
        Endpoints.isCleartext(url) && !_state.value.settings.allowCleartext

    fun setBaseURL(value: String) = viewModelScope.launch {
        // `error` is deliberately left alone: the endpoint change triggers a
        // model fetch whose result owns it, and clearing it here would race
        // that fetch and could swallow its warning.
        val dropped = settingsRepo.setBaseURL(value.trim()) ?: return@launch
        _state.update {
            it.copy(
                credentialNotice = "API key cleared: it was saved for $dropped, and this " +
                    "endpoint is somewhere else. Re-enter the key if it belongs here.",
            )
        }
    }

    fun setApiKey(value: String) = viewModelScope.launch {
        // A key that cannot be encrypted is not stored at all. Keeping the
        // plaintext instead would leave the user believing it is protected.
        runCatching { settingsRepo.setApiKey(value) }
            .onSuccess { _state.update { it.copy(credentialNotice = null) } }
            .onFailure { failure -> _state.update { it.copy(credentialNotice = failure.notice()) } }
    }

    /**
     * Allowing HTTP re-opens the endpoint; withdrawing it has to drop the loaded
     * model list too, or the composer would stay enabled for an endpoint that is
     * now off limits.
     */
    fun setAllowCleartext(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setAllowCleartext(enabled)
        if (!Endpoints.isCleartext(_state.value.settings.baseURL)) return@launch
        if (enabled) {
            refreshModels()
        } else {
            _state.update {
                it.copy(models = emptyList(), modalities = emptyMap(), error = CLEARTEXT_BLOCKED)
            }
        }
    }

    fun setModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }
    fun setReasoning(enabled: Boolean) = viewModelScope.launch { settingsRepo.setReasoning(enabled) }

    fun setExportMedia(enabled: Boolean) = viewModelScope.launch { settingsRepo.setExportMedia(enabled) }
    fun setAccentColor(argb: Int) = viewModelScope.launch { settingsRepo.setAccentColor(argb) }
    fun setUserName(value: String) = viewModelScope.launch { settingsRepo.setUserName(value) }
    fun setAssistantName(value: String) = viewModelScope.launch { settingsRepo.setAssistantName(value) }
    fun setNameplate(value: String) = viewModelScope.launch { settingsRepo.setNameplate(value) }

    fun savePreset(name: String, baseURL: String, apiKey: String) =
        viewModelScope.launch { guardCredentials { settingsRepo.savePreset(name, baseURL, apiKey) } }

    fun applyPreset(preset: SettingsRepository.EndpointPreset) = viewModelScope.launch {
        if (!preset.keyReadable) {
            _state.update {
                it.copy(
                    credentialNotice = "\"${preset.name}\" holds a key this device can no longer " +
                        "decrypt. Re-enter it and save the preset again.",
                )
            }
            return@launch
        }
        guardCredentials { settingsRepo.applyPreset(preset) }
    }

    fun renamePreset(id: String, name: String) =
        viewModelScope.launch { guardCredentials { settingsRepo.renamePreset(id, name) } }

    fun deletePreset(id: String) =
        viewModelScope.launch { guardCredentials { settingsRepo.deletePreset(id) } }

    /**
     * Runs a preset write, which re-encrypts the whole list and so fails as a
     * unit if the Keystore is unavailable. Nothing is persisted in that case.
     */
    private suspend fun guardCredentials(block: suspend () -> Unit) {
        runCatching { block() }
            .onSuccess { _state.update { it.copy(credentialNotice = null) } }
            .onFailure { failure -> _state.update { it.copy(credentialNotice = failure.notice()) } }
    }

    private fun Throwable.notice(): String = when (this) {
        is SecretUnavailableException -> message ?: "The API key could not be stored securely."
        else -> "The API key could not be saved: ${message ?: this::class.java.simpleName}"
    }

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
     *
     * The probes carry no credentials ([EndpointScanner] builds a bare GET), so
     * the sweep itself discloses nothing to the hosts it touches. A result is
     * adopted even when plain HTTP is not allowed: the endpoint is still the
     * right one, and the model fetch that follows explains what to turn on.
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
                    // Triggers refreshModels via the settings collector. Any key
                    // bound to the previous endpoint is dropped by the write, so
                    // that fetch cannot carry it to whichever host won the race.
                    val dropped = settingsRepo.setBaseURL(url)
                    if (dropped != null) {
                        _state.update {
                            it.copy(
                                credentialNotice = "API key cleared: it was saved for $dropped, " +
                                    "and the scan found a different endpoint.",
                            )
                        }
                    }
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
        stream.start(
            config = OpenAiClient.Config(s.baseURL, s.apiKey, s.model, s.reasoning),
            conversation = MessageTree.getConversation(_state.value.mappings, rootId),
            onUpdate = { update ->
                // Guards against a tick from a superseded job.
                if (_state.value.streamingId == responseId) {
                    _state.update {
                        it.copy(
                            streamingText = update.content ?: it.streamingText,
                            streamingReasoning = update.reasoning ?: it.streamingReasoning,
                        )
                    }
                }
            },
            onError = { e -> _state.update { it.copy(error = e.message) } },
            onFinish = ::finishStreaming,
        )
    }

    fun stop() {
        // `onFinish` dies with the job, so the commit happens here instead.
        stream.cancel()
        finishStreaming()
    }

    private fun finishStreaming() {
        if (!_state.value.busy) return
        // Commit the accumulated reply into the tree in a single map write, then
        // persist once (per-token writes were intentionally suppressed above).
        val id = _state.value.streamingId
        val text = stream.committedText()
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
        stream.reset()
        persist()
    }

    private fun persist() {
        // Non-blocking: hand the latest snapshot to the single-consumer save
        // loop. The repository writes only the diff, and skips the DB entirely
        // when nothing changed.
        saveRequests.trySend(_state.value.mappings)
    }

    private companion object {
        const val CLEARTEXT_BLOCKED =
            "Blocked: this endpoint uses plain HTTP. " +
                "Turn on \"Allow HTTP endpoints\" in Settings to use it."
    }
}
