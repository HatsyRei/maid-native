package com.hatsyrei.maidnative.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.remote.EndpointScanner
import com.hatsyrei.maidnative.data.remote.OpenAiClient
import com.hatsyrei.maidnative.data.store.MessageStore
import com.hatsyrei.maidnative.domain.tree.Mappings
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
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
) {
    /** Visible conversation = active thread from root, skipping the system node. */
    val conversation: List<MessageNode>
        get() = root?.let { MessageTree.getConversation(mappings, it).drop(1) } ?: emptyList()

    val ready: Boolean
        get() = settings.model.isNotEmpty() && models.contains(settings.model)
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val client = OpenAiClient()
    private val store = MessageStore(File(app.filesDir, "messages.json"))

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = store.load()
            val root = MessageTree.getRoots(loaded).firstOrNull()?.id
            _state.value = _state.value.copy(mappings = loaded, root = root)
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

    /**
     * Discover a local OpenAI-compatible endpoint (mirrors base-url-field.tsx).
     * If the current base URL already normalizes and validates, adopt it instead
     * of scanning the whole subnet; otherwise scan and adopt the first match.
     */
    fun scanEndpoint() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, error = null)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = EndpointScanner.normalizeBaseUrl(_state.value.settings.baseURL)
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
            null, null, null, mapOf("title" to "New Chat"),
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
        val t = title.trim().ifEmpty { "New Chat" }
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
                null, null, null, mapOf("title" to "New Chat"),
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
        _state.value = _state.value.copy(busy = true, error = null)
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
        val updated = MessageTree.updateContent(_state.value.mappings, responseId, { it + chunk })
        _state.value = _state.value.copy(mappings = updated)
    }

    private fun finishStreaming() {
        if (!_state.value.busy) return
        _state.value = _state.value.copy(busy = false)
        persist()
    }

    private fun persist() {
        val snapshot = _state.value.mappings
        viewModelScope.launch { runCatching { store.save(snapshot) } }
    }
}
