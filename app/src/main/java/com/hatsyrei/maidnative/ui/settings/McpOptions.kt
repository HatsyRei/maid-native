package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.domain.tools.Mcp
import com.hatsyrei.maidnative.domain.tools.McpServer
import com.hatsyrei.maidnative.domain.tools.McpToolInfo
import com.hatsyrei.maidnative.domain.tools.ToolPolicy
import com.hatsyrei.maidnative.ui.chat.ConfirmDialog
import com.hatsyrei.maidnative.ui.common.AppDialog
import com.hatsyrei.maidnative.ui.icons.AddIcon
import com.hatsyrei.maidnative.ui.icons.DnsIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Opens the MCP dialog, and carries how many servers are on. */
@Composable
internal fun McpChip(servers: List<McpServer>, onClick: () -> Unit) {
    val count = servers.count { it.enabled }
    val active = count > 0
    AssistChip(
        onClick = onClick,
        label = {
            Text(if (active) stringResource(R.string.mcp_chip_count, count) else stringResource(R.string.mcp_chip))
        },
        leadingIcon = { Icon(DnsIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = if (!active) {
            AssistChipDefaults.assistChipColors()
        } else {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        border = if (active) null else AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

/**
 * The server list. Adding, editing and deleting a server is written at once;
 * the enable checkboxes are a draft that only Save applies.
 */
@Composable
internal fun McpDialog(
    servers: List<McpServer>,
    listTools: suspend (url: String, token: String) -> List<McpToolInfo>,
    onSave: (List<McpServer>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Held here rather than read back from [servers], which lags each write by a store round trip.
    var saved by remember { mutableStateOf(servers) }
    var toggles by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    // A server not yet in [saved] is being added.
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var deleting by remember { mutableStateOf(false) }

    fun write(next: List<McpServer>) {
        saved = next
        onSave(next)
    }

    editing?.let { server ->
        McpServerDialog(
            server = server,
            isNew = saved.none { it.id == server.id },
            takenPrefixes = saved.filter { it.id != server.id }.mapTo(HashSet()) { it.prefix },
            listTools = listTools,
            onDismiss = { editing = null },
            onDelete = { deleting = true },
            onConfirm = { edited ->
                write(
                    if (saved.any { it.id == edited.id }) {
                        saved.map { if (it.id == edited.id) edited else it }
                    } else {
                        saved + edited
                    },
                )
                editing = null
            },
        )
        // Over the editor, so backing out of it keeps what was typed.
        if (deleting) {
            ConfirmDialog(
                title = stringResource(R.string.mcp_delete_server),
                message = stringResource(R.string.mcp_delete_server_message, server.name),
                onDismiss = { deleting = false },
                onConfirm = {
                    write(saved.filterNot { it.id == server.id })
                    toggles = toggles - server.id
                    deleting = false
                    editing = null
                },
            )
        }
        return
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_servers)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (saved.isEmpty()) {
                    Text(
                        stringResource(R.string.mcp_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                saved.forEach { server ->
                    ServerRow(
                        server = server,
                        enabled = toggles[server.id] ?: server.enabled,
                        onToggle = { on -> toggles = toggles + (server.id to on) },
                        onEdit = { editing = server },
                    )
                }
                TextButton(
                    onClick = { editing = McpServer(id = UUID.randomUUID().toString(), name = "", url = "") },
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                ) {
                    Icon(AddIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        stringResource(R.string.mcp_add_server),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (saved.any { server -> toggles[server.id].let { it != null && it != server.enabled } }) {
                        write(saved.map { it.copy(enabled = toggles[it.id] ?: it.enabled) })
                    }
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ServerRow(server: McpServer, enabled: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val on = server.tools.count { it.name !in server.disabledTools }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onEdit),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DnsIcon, contentDescription = null, tint = if (enabled) colors.primary else colors.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (server.tokenReadable) {
                    pluralStringResource(R.plurals.mcp_server_tools, server.tools.size, on, server.tools.size)
                } else {
                    stringResource(R.string.mcp_token_unreadable_short)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (server.tokenReadable) colors.onSurfaceVariant else colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun McpServerDialog(
    server: McpServer,
    isNew: Boolean,
    takenPrefixes: Set<String>,
    listTools: suspend (url: String, token: String) -> List<McpToolInfo>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (McpServer) -> Unit,
) {
    val name = rememberTextFieldState(server.name)
    val url = rememberTextFieldState(server.url)
    val token = rememberTextFieldState(server.token)
    var tools by remember { mutableStateOf(server.tools) }
    var disabled by remember { mutableStateOf(server.disabledTools) }
    var trusted by remember { mutableStateOf(server.trusted) }
    var trustedTools by remember { mutableStateOf(server.trustedTools) }
    // The URL and token [tools] were listed with. Save waits for a listing of the current pair.
    var listedFor by remember { mutableStateOf(if (isNew) null else server.url to server.token) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The pair last fetched, so leaving a field only fetches again once one of them changed.
    var attempted by remember { mutableStateOf(listedFor) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val newName = name.text.toString().trim()
    val newUrl = url.text.toString().trim()
    val newToken = token.text.toString().trim()
    val prefix = Mcp.prefix(newName)
    val nameTaken = newName.isNotEmpty() && prefix in takenPrefixes
    val parsedUrl = newUrl.toHttpUrlOrNull()
    val listed = listedFor == (newUrl to newToken)
    val colors = MaterialTheme.colorScheme

    // Read from the fields rather than the composition's values, since focus callbacks can run before a recompose.
    fun current() = url.text.toString().trim() to token.text.toString().trim()

    fun connect() {
        val target = current()
        attempted = target
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            runCatching { listTools(target.first, target.second) }
                .onSuccess {
                    tools = it
                    listedFor = target
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    error = it.message ?: it::class.java.simpleName
                }
            loading = false
        }
    }

    // Like the models list: fetched when the endpoint is committed, never retried on its own.
    fun commit() {
        val target = current()
        if (target != attempted && target.first.toHttpUrlOrNull() != null) connect()
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.mcp_add_mcp_server else R.string.mcp_edit_mcp_server)) },
        text = {
            // The dialog's own window; the caller's focus manager can't clear focus in here.
            val focusManager = LocalFocusManager.current
            val nameTakenHint = stringResource(R.string.mcp_name_taken)
            val prefixHint = stringResource(R.string.mcp_prefix_hint, prefix)
            val invalidUrlHint = stringResource(R.string.mcp_url_invalid)
            val unencryptedHint = stringResource(R.string.mcp_url_unencrypted)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // A tap on empty space drops focus, as in Settings. Runs after any child handles the tap.
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
            ) {
                OutlinedTextField(
                    state = name,
                    label = { Text(stringResource(R.string.mcp_name)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    isError = nameTaken,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    supportingText = when {
                        nameTaken -> nameTakenHint
                        newName.isEmpty() -> null
                        else -> prefixHint
                    }?.let { hint -> { Text(hint) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    state = url,
                    label = { Text(stringResource(R.string.mcp_url)) },
                    placeholder = { Text("http://127.0.0.1:8000/mcp") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    isError = newUrl.isNotEmpty() && parsedUrl == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = when {
                        newUrl.isNotEmpty() && parsedUrl == null -> invalidUrlHint
                        parsedUrl != null && !parsedUrl.isHttps && !isLoopback(parsedUrl.host) ->
                            unencryptedHint
                        else -> null
                    }?.let { hint -> { Text(hint) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commit() },
                )
                OutlinedSecureTextField(
                    state = token,
                    label = { Text(stringResource(R.string.mcp_bearer_token)) },
                    textObfuscationMode = TextObfuscationMode.Hidden,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    onKeyboardAction = { focusManager.clearFocus() },
                    supportingText = if (server.tokenReadable) {
                        null
                    } else {
                        { Text(stringResource(R.string.mcp_token_unreadable)) }
                    },
                    isError = !server.tokenReadable && newToken.isEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commit() },
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            // Buttons don't take focus on touch; dropping it here may already start a fetch.
                            focusManager.clearFocus()
                            if (!loading) connect()
                        },
                        enabled = parsedUrl != null && !loading,
                    ) {
                        Text(stringResource(if (listedFor == null) R.string.mcp_connect else R.string.mcp_refresh_tools))
                    }
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }

                if (listedFor != null) {
                    Text(
                        stringResource(if (tools.isEmpty()) R.string.mcp_no_tools else R.string.tool_tools),
                        style = if (tools.isEmpty()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                        color = if (tools.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (tools.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .heightIn(min = 56.dp)
                                    .toggleable(value = trusted, role = Role.Switch) {
                                        focusManager.clearFocus()
                                        trusted = it
                                    },
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.mcp_trust_server), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.mcp_trust_server_summary),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = trusted, onCheckedChange = null)
                            }
                        }
                        tools.forEach { tool ->
                            val policy = when {
                                tool.name in disabled -> ToolPolicy.OFF
                                trusted || tool.name in trustedTools -> ToolPolicy.ALLOW
                                else -> ToolPolicy.ASK
                            }
                            ToolRow(tool, policy = policy, askEnabled = !trusted) { choice ->
                                focusManager.clearFocus()
                                disabled = if (choice == ToolPolicy.OFF) disabled + tool.name else disabled - tool.name
                                trustedTools = when {
                                    choice == ToolPolicy.ASK -> trustedTools - tool.name
                                    choice == ToolPolicy.ALLOW && !trusted -> trustedTools + tool.name
                                    else -> trustedTools
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val tokenChanged = newToken != server.token
                    onConfirm(
                        server.copy(
                            name = newName,
                            url = newUrl,
                            token = newToken,
                            storedToken = if (tokenChanged) "" else server.storedToken,
                            tokenReadable = tokenChanged || server.tokenReadable,
                            tools = tools,
                            // Names the server no longer lists would only pile up.
                            disabledTools = disabled.filterTo(HashSet()) { off -> tools.any { it.name == off } },
                            trusted = trusted,
                            trustedTools = trustedTools.filterTo(HashSet()) { on -> tools.any { it.name == on } },
                        ),
                    )
                },
                enabled = newName.isNotEmpty() && !nameTaken && parsedUrl != null && listed && !loading,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        neutralButton = if (isNew) {
            null
        } else {
            { TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = colors.error) } }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun isLoopback(host: String): Boolean =
    host == "localhost" || host.startsWith("127.") || host == "::1"
