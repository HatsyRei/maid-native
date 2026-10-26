package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.domain.ChatStats
import com.hatsyrei.maidnative.domain.tools.LinkedFile
import com.hatsyrei.maidnative.ui.common.AppDialog

/**
 * Statistics and linked files for one conversation.
 *
 * Takes an already-computed [ChatStats] rather than the tree: every parameter
 * is then stable, so while a reply streams behind the dialog this skips instead
 * of recomposing once per token. The caller owns the `remember` that keeps the
 * aggregation itself from re-running.
 */
@Composable
internal fun ChatPropertiesDialog(
    title: String,
    stats: ChatStats,
    files: List<LinkedFile>,
    onOpenFile: (LinkedFile) -> Unit,
    onUnlinkFile: (LinkedFile) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2) },
        text = {
            Column {
                StatRow(
                    stringResource(R.string.properties_conversation_size),
                    stats.conversationTokens.tokens(stats.sizeAccuracy),
                )
                StatRow(stringResource(R.string.properties_average_speed), stats.tokensPerSecond.tokensPerSecond())
                StatRow(stringResource(R.string.properties_average_response), stats.averageResponseMs.duration())
                StatRow(stringResource(R.string.properties_time_to_first_token), stats.averageTtftMs.duration())
                val notes = stats.notes()
                if (notes.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(top = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        notes.forEach { Note(it) }
                    }
                }
                if (files.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.properties_linked_files),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    LinkedFileRows(files = files, onOpen = onOpenFile, onUnlink = onUnlinkFile)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Note(text: String) {
    Row {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Everything the figures above need qualified, in the order they appear. */
@Composable
private fun ChatStats.notes(): List<String> {
    val size = sizeNote()
    val partial = if (partialSample) {
        pluralStringResource(R.plurals.properties_partial_sample, assistantMessages, statsTurns, assistantMessages)
    } else {
        null
    }
    return listOfNotNull(size, partial)
}

@Composable
private fun ChatStats.sizeNote(): String? {
    if (conversationTokens == null) return null
    return when (sizeAccuracy) {
        ChatStats.SizeAccuracy.EXACT -> null
        ChatStats.SizeAccuracy.AT_LEAST -> stringResource(R.string.properties_size_grown)
        ChatStats.SizeAccuracy.AT_MOST -> stringResource(R.string.properties_size_shrunk)
    }
}

@Composable
private fun Int?.tokens(accuracy: ChatStats.SizeAccuracy): String {
    if (this == null) return UNKNOWN
    val count = pluralStringResource(R.plurals.properties_tokens, this, formatted())
    return when (accuracy) {
        ChatStats.SizeAccuracy.AT_LEAST -> stringResource(R.string.properties_at_least, count)
        ChatStats.SizeAccuracy.AT_MOST -> stringResource(R.string.properties_at_most, count)
        else -> count
    }
}

private fun Double?.tokensPerSecond(): String =
    if (this == null) UNKNOWN else formatTokensPerSecond(this)

