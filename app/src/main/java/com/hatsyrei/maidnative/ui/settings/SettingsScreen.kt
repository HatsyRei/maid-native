package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.data.prefs.SettingsRepository.EndpointPreset
import com.hatsyrei.maidnative.domain.tools.McpServer
import com.hatsyrei.maidnative.ui.chat.ChatUiState
import com.hatsyrei.maidnative.ui.chat.ConfirmDialog
import com.hatsyrei.maidnative.ui.chat.ModelSelector
import com.hatsyrei.maidnative.ui.icons.BookmarksIcon
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    presets: List<EndpointPreset>,
    mcpServers: List<McpServer>,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val baseURL = rememberTextFieldState(state.settings.baseURL)
    val apiKey = rememberTextFieldState(state.settings.apiKey)
    var showPresets by remember { mutableStateOf(false) }
    var showScanOptions by remember { mutableStateOf(false) }
    var editingSampling by remember { mutableStateOf<SamplingGroup?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<EndpointPreset?>(null) }
    var savingNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EndpointPreset?>(null) }
    val focusManager = LocalFocusManager.current

    // Reset the scan button to its idle state each time Settings is opened.
    LaunchedEffect(Unit) { actions.resetScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
                // Shrinks the scroll viewport by the keyboard, so a focused
                // field near the bottom can be scrolled (and is auto-brought)
                // into view instead of sitting under the IME. `consume` stops
                // the nav-bar inset already in `padding` being counted twice.
                .consumeWindowInsets(padding)
                .imePadding()
                // A tap on empty space drops focus, which is what commits a
                // half-typed field. Runs after any child handles the tap.
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_endpoint), style = MaterialTheme.typography.titleMedium)
                // Matches the scan button's width so the two icons share a centre line.
                IconButton(
                    onClick = {
                        // Buttons don't take focus on touch, so commit by hand or
                        // the sheet would save the last committed value.
                        focusManager.clearFocus()
                        showPresets = true
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(BookmarksIcon, contentDescription = stringResource(R.string.settings_saved_endpoints))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AutoSaveTextField(
                    state = baseURL,
                    committed = state.settings.baseURL,
                    label = stringResource(R.string.settings_base_url),
                    onCommit = actions.setBaseURL,
                    modifier = Modifier.weight(1f),
                    placeholder = "http://127.0.0.1:9931/v1",
                    // A URL is not prose: autocorrect, suggestions and sentence
                    // capitalisation only ever corrupt what the user typed.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                )
                val scanSucceeded = state.foundURL != null && state.settings.baseURL == state.foundURL
                ScanButton(
                    scanning = state.scanning,
                    succeeded = scanSucceeded,
                    onScan = {
                        focusManager.clearFocus()
                        showScanOptions = true
                    },
                )
            }

            AutoSaveTextField(
                state = apiKey,
                committed = state.settings.apiKey,
                label = stringResource(R.string.settings_api_key_label),
                onCommit = actions.setApiKey,
                modifier = Modifier.fillMaxWidth(),
                secure = true,
            )

            // The key is bound to the endpoint it was entered for and is never
            // sent anywhere else, so both of these want fixing where it is typed.
            if (state.settings.apiKeyUnreadable) {
                Text(
                    stringResource(R.string.settings_api_key_unreadable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.credentialNotice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_allow_http), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_allow_http_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.allowCleartext,
                    onCheckedChange = actions.setAllowCleartext,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.titleMedium)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.models.isEmpty()) {
                Text(
                    stringResource(R.string.settings_no_models),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ModelSelector(
                    models = state.models,
                    selected = state.settings.model,
                    onSelect = actions.setModel,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = actions.refreshModels,
                    enabled = !state.refreshingModels,
                    label = { Text(stringResource(R.string.settings_refresh_models)) },
                    leadingIcon = {
                        if (state.refreshingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                    },
                )

                SamplingGroup.entries.forEach { group ->
                    SamplingChip(
                        group = group,
                        sampling = state.settings.sampling,
                        onClick = {
                            focusManager.clearFocus()
                            editingSampling = group
                        },
                    )
                }

                ToolsChip(
                    enabled = state.settings.enabledTools,
                    onClick = {
                        focusManager.clearFocus()
                        showTools = true
                    },
                )

                McpChip(
                    servers = mcpServers,
                    onClick = {
                        focusManager.clearFocus()
                        showMcp = true
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_thinking), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_thinking_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.reasoning,
                    onCheckedChange = actions.setReasoning,
                )
            }

            Spacer(Modifier.height(8.dp))

            AppSection(
                accentColor = state.settings.accentColor,
                nameplate = state.settings.nameplate,
                actions = actions,
            )

            Spacer(Modifier.height(8.dp))

            ChatSection(
                userName = state.settings.userName,
                assistantName = state.settings.assistantName,
                userAvatar = state.settings.userAvatar,
                assistantAvatar = state.settings.assistantAvatar,
                exportMedia = state.settings.exportMedia,
                actions = actions,
            )
        }
    }

    if (showPresets) {
        EndpointPresetSheet(
            presets = presets,
            activeBaseURL = state.settings.baseURL,
            activeApiKey = state.settings.apiKey,
            onSaveCurrent = {
                showPresets = false
                savingNew = true
            },
            onApply = {
                showPresets = false
                actions.applyPreset(it)
            },
            onRename = {
                showPresets = false
                naming = it
            },
            onDelete = {
                showPresets = false
                deleting = it
            },
            onDismiss = { showPresets = false },
        )
    }

    if (showScanOptions) {
        ScanOptionsDialog(
            port = state.settings.scanPort,
            prefixLength = state.settings.scanPrefixLength,
            onDismiss = { showScanOptions = false },
            onConfirm = { port, prefixLength ->
                showScanOptions = false
                actions.scan(port, prefixLength)
            },
        )
    }

    editingSampling?.let { group ->
        SamplingDialog(
            group = group,
            sampling = state.settings.sampling,
            onDismiss = { editingSampling = null },
            onConfirm = {
                editingSampling = null
                actions.setSampling(it)
            },
        )
    }

    if (showTools) {
        ToolsDialog(
            enabled = state.settings.enabledTools,
            ask = state.settings.askTools,
            onDismiss = { showTools = false },
            onConfirm = {
                showTools = false
                actions.setToolPolicies(it)
            },
        )
    }

    if (showMcp) {
        McpDialog(
            servers = mcpServers,
            listTools = actions.listMcpTools,
            onSave = actions.setMcpServers,
            onDismiss = { showMcp = false },
        )
    }

    if (savingNew) {
        PresetNameDialog(
            title = stringResource(R.string.preset_save_title),
            confirmLabel = stringResource(R.string.action_save),
            initial = defaultPresetName(baseURL.text.toString()),
            takenNames = presets.map { it.name },
            onDismiss = { savingNew = false },
            onConfirm = {
                savingNew = false
                actions.savePreset(it, baseURL.text.toString(), apiKey.text.toString())
            },
        )
    }

    naming?.let { preset ->
        PresetNameDialog(
            title = stringResource(R.string.preset_rename_title),
            confirmLabel = stringResource(R.string.action_rename),
            initial = preset.name,
            takenNames = presets.filter { it.id != preset.id }.map { it.name },
            onDismiss = { naming = null },
            onConfirm = {
                naming = null
                actions.renamePreset(preset.id, it)
            },
        )
    }

    deleting?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.preset_delete_title),
            message = stringResource(R.string.preset_delete_message, preset.name),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                actions.deletePreset(preset.id)
            },
        )
    }
}

/**
 * Rewrites a field, parking the caret at whichever end should stay in view when
 * the value is wider than the field (the field scrolls to follow the caret).
 */
internal fun TextFieldState.replaceText(value: String, caretAtStart: Boolean = false) {
    edit {
        replace(0, length, value)
        selection = TextRange(if (caretAtStart) 0 else length)
    }
}

/**
 * An [OutlinedTextField] that commits its value on IME "Done" and when it loses
 * focus (only when the text actually differs from the last [committed] value).
 *
 * Deliberately the [TextFieldState] overload: the legacy `value`/`onValueChange`
 * one recomputes the dragged offset from the gesture's own deltas alone, so a
 * cursor or selection handle held at the edge of a value wider than the field
 * stops there instead of scrolling. The state-based field compensates for the
 * scroll it causes, which keeps the drag going (`handleDragPosition`).
 */
@Composable
internal fun AutoSaveTextField(
    state: TextFieldState,
    committed: String,
    label: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    caretAtStart: Boolean = false,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
) {
    // Adopt values the store writes behind the field's back (a scan result, a
    // loaded preset). No-op for the round trip of the field's own commit.
    LaunchedEffect(committed) {
        if (state.text.toString() != committed) state.replaceText(committed, caretAtStart)
    }

    var focused by remember { mutableStateOf(false) }
    // The field asks the scroller for its caret line only, and it asks before the
    // keyboard has taken its space, so a field near the bottom ends up half under
    // it. Re-ask for the whole box for as long as the inset is moving.
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collectLatest { requester.bringIntoView() }
    }

    // [committed] lags a commit by a store round trip, so IME "Done" — which also
    // drops focus — would otherwise fire the same write a second time.
    var sent by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val commit = { value: String ->
        if (value != committed && value != sent) {
            sent = value
            onCommit(value)
        }
    }
    val fieldModifier = modifier
        .bringIntoViewRequester(requester)
        .onFocusChanged { focus ->
            if (focused && !focus.isFocused) commit(state.text.toString())
            focused = focus.isFocused
        }
    val onKeyboardAction = KeyboardActionHandler {
        commit(state.text.toString())
        focusManager.clearFocus()
    }
    if (secure) {
        OutlinedSecureTextField(
            state = state,
            label = { Text(label) },
            textObfuscationMode = TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = onKeyboardAction,
            modifier = fieldModifier,
        )
    } else {
        OutlinedTextField(
            state = state,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            modifier = fieldModifier,
        )
    }
}
