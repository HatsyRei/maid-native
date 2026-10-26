package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.domain.tools.LinkedFile
import com.hatsyrei.maidnative.domain.tools.ToolCall
import com.hatsyrei.maidnative.domain.tools.ToolCalls
import com.hatsyrei.maidnative.domain.tools.ToolText
import com.hatsyrei.maidnative.ui.common.AppDialog
import com.hatsyrei.maidnative.ui.icons.CloseIcon
import com.hatsyrei.maidnative.ui.icons.DescriptionIcon
import org.json.JSONObject

/** Calls the model made together, each collapsed to its name; one waiting for approval opens with its choices. */
@Composable
internal fun ToolCallsBlock(
    calls: List<ToolCall>,
    modifier: Modifier = Modifier,
    awaiting: Set<String> = emptySet(),
    onApprove: (ids: List<String>, allow: Boolean, always: Boolean) -> Unit = { _, _, _ -> },
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (call in calls) {
            if (call.id in awaiting) ApprovalRow(call, onApprove) else ToolCallRow(call)
        }
        val waiting = calls.filter { it.id in awaiting }
        if (waiting.size > 1) {
            TextButton(
                onClick = { onApprove(waiting.map { it.id }, true, false) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.toolcall_allow_all, waiting.size))
            }
        }
    }
}

@Composable
private fun ApprovalRow(call: ToolCall, onApprove: (ids: List<String>, allow: Boolean, always: Boolean) -> Unit) {
    var always by remember(call.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp)) {
            ToolHeader(call)
            if (call.arguments.isNotBlank() && call.arguments != "{}") {
                ToolDetail(label = stringResource(R.string.toolcall_arguments), text = prettyJson(call.arguments))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .toggleable(value = always, role = Role.Checkbox, onValueChange = { always = it }),
                ) {
                    Checkbox(checked = always, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(R.string.toolcall_dont_ask_again),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onApprove(listOf(call.id), false, false) }) {
                    Text(stringResource(R.string.action_deny), color = colors.error)
                }
                TextButton(onClick = { onApprove(listOf(call.id), true, always) }) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

@Composable
private fun ToolCallRow(call: ToolCall) {
    var expanded by remember(call.id) { mutableStateOf(false) }
    val running = call.result == null
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { expanded = !expanded },
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            ToolHeader(call) {
                if (running) {
                    Text(
                        text = stringResource(R.string.toolcall_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.toolcall_hide_result else R.string.toolcall_show_result,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded) {
                if (call.arguments.isNotBlank() && call.arguments != "{}") {
                    ToolDetail(label = stringResource(R.string.toolcall_arguments), text = call.arguments)
                }
                ToolDetail(label = stringResource(R.string.toolcall_result), text = call.result.orEmpty())
            }
        }
    }
}

@Composable
private fun ToolHeader(call: ToolCall, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = call.name,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // Keeps both ends of `server__tool` in view.
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

private fun prettyJson(raw: String): String = runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)

@Composable
private fun ToolDetail(label: String, text: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Files linked on a message, in the tool rows' style: tap to view, × to unlink. */
@Composable
internal fun LinkedFileRows(
    files: List<LinkedFile>,
    onOpen: (LinkedFile) -> Unit,
    onUnlink: (LinkedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (file in files) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { onOpen(file) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        DescriptionIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onUnlink(file) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            CloseIcon,
                            contentDescription = stringResource(R.string.toolcall_unlink, file.name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The Modify dialog's tool calls, labelled with the key their `{{tool:N}}`
 * marker names. Opening one edits its arguments and result; removing one also
 * takes its marker out of [text]. A chip whose marker was deleted by hand is
 * dimmed, since saving will drop it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolCallChips(
    calls: ToolCalls,
    text: CharSequence,
    onEdit: (String, ToolCall) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(true) }
    // Typing wants the room, so the chips fold away as the keyboard opens; the header reopens them.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) expanded = false }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.toolcall_calls_header, calls.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.toolcall_hide_calls else R.string.toolcall_show_calls,
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            FlowRow(
                modifier = Modifier
                    // Full width, so the space beside the chips scrolls them too.
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val present = ToolText.markerKeys(text)
                for ((key, call) in calls) {
                    EditChip(
                        label = "$key · ${call.name}",
                        unused = key !in present,
                        removeDescription = stringResource(R.string.toolcall_remove_call, call.name),
                        onOpen = { editing = key },
                        onRemove = { onRemove(key) },
                    )
                }
            }
        }
    }

    editing?.let { key ->
        val call = calls[key] ?: return@let
        ToolCallDialog(
            call = call,
            onDismiss = { editing = null },
            onConfirm = { edited ->
                onEdit(key, edited)
                editing = null
            },
        )
    }
}

@Composable
private fun EditChip(
    label: String,
    unused: Boolean,
    removeDescription: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.alpha(if (unused) 0.5f else 1f),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                textDecoration = if (unused) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .clickable(onClick = onOpen),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    CloseIcon,
                    contentDescription = removeDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallDialog(
    call: ToolCall,
    onDismiss: () -> Unit,
    onConfirm: (ToolCall) -> Unit,
) {
    val arguments = rememberTextFieldState(call.arguments)
    val result = rememberTextFieldState(call.result.orEmpty())
    val argumentsText = arguments.text.toString().trim().ifEmpty { "{}" }
    // The endpoint rejects a call whose arguments are not a JSON object.
    val argumentsValid = runCatching { JSONObject(argumentsText) }.isSuccess
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(call.name, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    state = arguments,
                    label = { Text(stringResource(R.string.toolcall_arguments)) },
                    textStyle = mono,
                    isError = !argumentsValid,
                    supportingText = if (argumentsValid) null else { { Text(stringResource(R.string.toolcall_invalid_json)) } },
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    state = result,
                    label = { Text(stringResource(R.string.toolcall_result)) },
                    textStyle = mono,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(call.copy(arguments = argumentsText, result = result.text.toString())) },
                enabled = argumentsValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
