package com.hatsyrei.maidnative.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatsyrei.maidnative.data.db.MaidDatabase
import com.hatsyrei.maidnative.data.db.MessageRepository
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.remote.EndpointScanner
import com.hatsyrei.maidnative.data.remote.OpenAiClient
import com.hatsyrei.maidnative.data.store.ConversationFileStore
import com.hatsyrei.maidnative.data.store.MessageStore
import com.hatsyrei.maidnative.domain.ConversationDefaults
import com.hatsyrei.maidnative.domain.tree.Mappings
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import com.hatsyrei.maidnative.domain.tree.validateMappings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ChatUiState(
    val mappings: Mappings = LinkedHashMap(),
    val root: String? = null,
    val models: List<String> = emptyList(),
    val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val foundURL: String? = null,
    val error: String? = null,
    val streamingId: String? = null,
    val streamingText: String = "",
) {
    /**
     * Visible conversation = active thread from root, skipping the system node.
     * While a stream is in flight the growing reply lives in [streamingText]
     * (not the tree), so overlay it onto the streaming node here. The tree map
     * itself is left untouched per token and rewritten exactly once when the
     * stream ends or is stopped.
     */
    val conversation: List<MessageNode>
        get() {
            val thread = root?.let { MessageTree.getConversation(mappings, it).drop(1) }
                ?: return emptyList()
            val id = streamingId ?: return thread
            return thread.map { if (it.id == id) it.copy(content = streamingText) else it }
        }

    val ready: Boolean
        get() = settings.model.isNotEmpty() && models.contains(settings.model)
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val client = OpenAiClient()
    private val repo = MessageRepository(MaidDatabase.get(app).messageDao())
    private val fileStore = ConversationFileStore(app.contentResolver)
    private val legacyFile = File(app.filesDir, "messages.json")

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    // Coalesces persist requests: a single consumer serializes writes (so the
    // repository's diff state is never touched concurrently) and CONFLATED
    // keeps only the latest pending snapshot, collapsing bursts (e.g. rapid
    // branch navigation) into one incremental write.
    private val saveRequests = Channel<Mappings>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            val loaded = repo.load(legacyFile)
            val root = MessageTree.getRoots(loaded).firstOrNull()?.id
            _state.value = _state.value.copy(mappings = loaded, root = root)
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
                _state.value = _state.value.copy(settings = s)
                if (_state.value.models.isEmpty() || changedEndpoint) refreshModels()
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val s = _state.value.settings
            val result = withContext(Dispatchers.IO) {
                runCatching { client.listModels(OpenAiClient.Config(s.baseURL, s.apiKey, s.model)) }
            }
            result.onSuccess { models ->
                val current = _state.value.settings.model
                _state.value = _state.value.copy(models = models, error = null)
                // Auto-select first model if none valid (mirrors open-ai.tsx).
                if (models.isNotEmpty() && (current.isEmpty() || !models.contains(current))) {
                    setModel(models.first())
                }
            }.onFailure {
                _state.value = _state.value.copy(models = emptyList(), error = it.message)
            }
        }
    }

    fun setBaseURL(value: String) = viewModelScope.launch { settingsRepo.setBaseURL(value) }
    fun setApiKey(value: String) = viewModelScope.launch { settingsRepo.setApiKey(value) }
    fun setModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }

    /** Clear transient scan state so re-entering Settings shows a fresh scan button. */
    fun resetScan() {
        _state.value = _state.value.copy(scanning = false, foundURL = null)
    }

    /**
     * Discover a local OpenAI-compatible endpoint (mirrors base-url-field.tsx).
     * If [candidate] (the Base URL currently in the field) normalizes and
     * validates, adopt it instead of scanning the whole subnet; otherwise scan
     * and adopt the first match. On success the endpoint is saved automatically,
     * which triggers model loading via the settings collector.
     */
    fun scanEndpoint(candidate: String) {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, foundURL = null, error = null)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = EndpointScanner.normalizeBaseUrl(candidate)
                    if (normalized != null && EndpointScanner.validateEndpoint(normalized)) normalized
                    else EndpointScanner.scanForEndpoint()
                }
            }
            found.onSuccess { url ->
                if (url != null) {
                    _state.value = _state.value.copy(foundURL = url)
                    settingsRepo.setBaseURL(url) // triggers refreshModels via the settings collector
                } else {
                    _state.value = _state.value.copy(
                        error = "Could not find an OpenAI-compatible endpoint on the local network.",
                    )
                }
            }.onFailure {
                _state.value = _state.value.copy(error = it.message)
            }
            _state.value = _state.value.copy(scanning = false)
        }
    }

    fun newChat() {
        // Mirror drawer-content.tsx `createChat`: create the system root node up
        // front so the conversation shows in the drawer immediately, then make it
        // the active chat. The first `submit` then attaches to this existing root.
        val s = _state.value.settings
        val rootId = UUID.randomUUID().toString()
        val next = MessageTree.addNode(
            _state.value.mappings, rootId, "system",
            s.systemPrompt.ifEmpty { SettingsRepository.DEFAULT_SYSTEM_PROMPT },
            null, null, null, mapOf("title" to ConversationDefaults.CHAT_TITLE),
        )
        _state.value = _state.value.copy(mappings = next, root = rootId)
        persist()
    }

    /** Switch the active conversation to an existing root. */
    fun selectChat(rootId: String) {
        if (rootId == _state.value.root) return
        _state.value = _state.value.copy(root = rootId)
    }

    /** Rename a conversation by updating its root node's title metadata. */
    fun renameChat(rootId: String, title: String) {
        if (_state.value.busy) return
        val t = title.trim().ifEmpty { ConversationDefaults.CHAT_TITLE }
        val next = MessageTree.updateContent(
            _state.value.mappings, rootId, { it }, { it + ("title" to t) },
        )
        if (next === _state.value.mappings) return
        _state.value = _state.value.copy(mappings = next)
        persist()
    }

    /** Delete an entire conversation (root + descendants). */
    fun deleteChat(rootId: String) {
        if (_state.value.busy) return
        val next = MessageTree.deleteNode(_state.value.mappings, rootId)
        if (next === _state.value.mappings) return
        val newRoot = if (_state.value.root == rootId) {
            MessageTree.getRoots(next).firstOrNull()?.id
        } else {
            _state.value.root
        }
        _state.value = _state.value.copy(mappings = next, root = newRoot)
        persist()
    }

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
                fileStore.write(uri, MessageStore.encodeExport(nodes))
            }.onFailure {
                _state.value = _state.value.copy(error = "Export failed: ${it.message}")
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
                val files = MessageTree.getRoots(snapshot).map { root ->
                    val nodes = snapshot.values.filter { it.root == root.id }
                    exportFileName(root.id) to MessageStore.encodeExport(nodes)
                }
                fileStore.backup(treeUri, files)
            }.onFailure {
                _state.value = _state.value.copy(error = "Backup failed: ${it.message}")
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
            val merged = withContext(Dispatchers.IO) {
                val next = LinkedHashMap(_state.value.mappings)
                for (uri in uris) {
                    runCatching {
                        val text = fileStore.read(uri)
                        val parsed = LinkedHashMap<String, MessageNode>()
                        for (node in MessageStore.decodeExport(text)) parsed[node.id] = node
                        next.putAll(validateMappings(parsed))
                    }
                }
                next
            }
            if (merged.size != _state.value.mappings.size || merged != _state.value.mappings) {
                _state.value = _state.value.copy(mappings = merged)
                persist()
            }
        }
    }

    /** Mirrors prompt-button.tsx: build system/user/assistant nodes, then stream. */
    fun submit(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy) return

        val s = _state.value.settings
        var next = _state.value.mappings
        val existingRoot = _state.value.root

        val parent: String
        if (existingRoot != null) {
            val thread = MessageTree.getConversation(next, existingRoot)
            parent = thread.last().id
        } else {
            parent = UUID.randomUUID().toString()
            next = MessageTree.addNode(
                next, parent, "system",
                s.systemPrompt.ifEmpty { SettingsRepository.DEFAULT_SYSTEM_PROMPT },
                null, null, null, mapOf("title" to ConversationDefaults.CHAT_TITLE),
            )
        }
        val rootId = existingRoot ?: parent

        val userId = UUID.randomUUID().toString()
        next = MessageTree.addNode(next, userId, "user", prompt, rootId, parent)

        val responseId = UUID.randomUUID().toString()
        next = MessageTree.addNode(next, responseId, "assistant", "", rootId, userId)

        _state.value = _state.value.copy(mappings = next, root = rootId)
        startStream(rootId, responseId)
    }

    /** Regenerate an assistant reply as a new sibling branch (mirrors message-menu.tsx). */
    fun regenerate(messageId: String) {
        if (_state.value.busy) return
        val node = _state.value.mappings[messageId] ?: return
        val responseId = UUID.randomUUID().toString()
        val next = MessageTree.branchNode(_state.value.mappings, messageId, responseId, "")
        if (next === _state.value.mappings) return
        _state.value = _state.value.copy(mappings = next)
        startStream(node.root, responseId)
    }

    /** Revise a user message: branch a new version and regenerate the reply. */
    fun revise(messageId: String, content: String) {
        val text = content.trim()
        if (_state.value.busy || text.isEmpty()) return
        val node = _state.value.mappings[messageId] ?: return
        val userId = UUID.randomUUID().toString()
        var next = MessageTree.branchNode(_state.value.mappings, messageId, userId, text)
        if (next === _state.value.mappings) return
        val responseId = UUID.randomUUID().toString()
        next = MessageTree.addNode(next, responseId, "assistant", "", node.root, userId)
        _state.value = _state.value.copy(mappings = next)
        startStream(node.root, responseId)
    }

    /** Edit a message's content in place without regenerating. */
    fun editMessage(messageId: String, content: String) {
        val next = MessageTree.setContent(_state.value.mappings, messageId, content.trim())
        if (next === _state.value.mappings) return
        _state.value = _state.value.copy(mappings = next)
        persist()
    }

    fun deleteMessage(messageId: String) {
        if (_state.value.busy) return
        val next = MessageTree.deleteNode(_state.value.mappings, messageId)
        if (next === _state.value.mappings) return
        val currentRoot = _state.value.root
        val newRoot = if (currentRoot != null && !next.containsKey(currentRoot)) {
            MessageTree.getRoots(next).firstOrNull()?.id
        } else {
            currentRoot
        }
        _state.value = _state.value.copy(mappings = next, root = newRoot)
        persist()
    }

    /** Switch to the previous sibling branch under [parentId]. */
    fun prevBranch(parentId: String) {
        if (_state.value.busy) return
        val next = MessageTree.lastChild(_state.value.mappings, parentId)
        if (next === _state.value.mappings) return
        _state.value = _state.value.copy(mappings = next)
        persist()
    }

    /** Switch to the next sibling branch under [parentId]. */
    fun nextBranch(parentId: String) {
        if (_state.value.busy) return
        val next = MessageTree.nextChild(_state.value.mappings, parentId)
        if (next === _state.value.mappings) return
        _state.value = _state.value.copy(mappings = next)
        persist()
    }

    private fun startStream(rootId: String, responseId: String) {
        val s = _state.value.settings
        _state.value = _state.value.copy(
            busy = true, error = null, streamingId = responseId, streamingText = "",
        )
        persist()
        val conversation = MessageTree.getConversation(_state.value.mappings, rootId)
        streamJob = viewModelScope.launch {
            client.streamChat(OpenAiClient.Config(s.baseURL, s.apiKey, s.model), conversation)
                .buffer()
                .catch { e -> _state.value = _state.value.copy(error = e.message) }
                .collect { chunk -> appendToResponse(responseId, chunk) }
            finishStreaming()
        }
    }

    fun stop() {
        streamJob?.cancel()
        finishStreaming()
    }

    private fun appendToResponse(responseId: String, chunk: String) {
        // Real-time streaming updates a lightweight buffer only; the tree map is
        // never copied per token. The buffer is committed into the tree once in
        // finishStreaming(). Guard against a stale chunk from a superseded job.
        if (_state.value.streamingId != responseId) return
        _state.value = _state.value.copy(streamingText = _state.value.streamingText + chunk)
    }

    private fun finishStreaming() {
        if (!_state.value.busy) return
        // Commit the accumulated reply into the tree in a single map write, then
        // persist once (per-token writes were intentionally suppressed above).
        val id = _state.value.streamingId
        val committed = if (id != null) {
            MessageTree.setContent(_state.value.mappings, id, _state.value.streamingText)
        } else {
            _state.value.mappings
        }
        _state.value = _state.value.copy(
            mappings = committed,
            busy = false,
            streamingId = null,
            streamingText = "",
        )
        persist()
    }

    private fun persist() {
        // Non-blocking: hand the latest snapshot to the single-consumer save
        // loop. The repository writes only the diff, and skips the DB entirely
        // when nothing changed.
        saveRequests.trySend(_state.value.mappings)
    }
}
