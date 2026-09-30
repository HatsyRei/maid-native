package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.ChatStats
import com.hatsyrei.maidnative.domain.tools.LinkedFile

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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, maxLines = 2) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                StatRow(
                    "Conversation size",
                    stats.conversationTokens.tokens(stats.sizeAccuracy),
                )
                StatRow("Average speed", stats.tokensPerSecond.tokensPerSecond())
                StatRow("Average response", stats.averageResponseMs.duration())
                StatRow("Time to first token", stats.averageTtftMs.duration())
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
                        text = "Linked files",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    LinkedFileRows(files = files, onOpen = onOpenFile, onUnlink = onUnlinkFile)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
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
private fun ChatStats.notes(): List<String> = buildList {
    sizeNote()?.let { add(it) }
    if (partialSample) {
        add(
            "Averages cover $statsTurns of $assistantMessages replies; " +
                "metadata unavailable for the rest.",
        )
    }
}

private fun ChatStats.sizeNote(): String? {
    if (conversationTokens == null) return null
    return when (sizeAccuracy) {
        ChatStats.SizeAccuracy.EXACT -> null
        ChatStats.SizeAccuracy.AT_LEAST ->
            "The conversation has grown since it was last counted; the next reply will update it."
        ChatStats.SizeAccuracy.AT_MOST ->
            "The conversation has shrunk since it was last counted; the next reply will update it."
    }
}

private fun Int?.tokens(accuracy: ChatStats.SizeAccuracy): String = when {
    this == null -> UNKNOWN
    accuracy == ChatStats.SizeAccuracy.AT_LEAST -> "\u2265 ${formatted()} tokens"
    accuracy == ChatStats.SizeAccuracy.AT_MOST -> "\u2264 ${formatted()} tokens"
    else -> "${formatted()} tokens"
}

private fun Double?.tokensPerSecond(): String =
    if (this == null) UNKNOWN else formatTokensPerSecond(this)

