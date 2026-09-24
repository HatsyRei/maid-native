package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.tools.Tools

/** Opens the tools dialog, and carries how many tools are currently enabled. */
@Composable
internal fun ToolsChip(enabled: Set<String>, onClick: () -> Unit) {
    val count = Tools.all.count { it.name in enabled }
    val active = count > 0
    AssistChip(
        onClick = onClick,
        label = { Text(if (active) "Tools · $count" else "Tools") },
        // The wrench fills its box more than the other chip glyphs, so it runs a size down.
        leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp)) },
        // Filled for the same reason as the sampling chip: requests carry more than the chat.
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

@Composable
internal fun ToolsDialog(
    enabled: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    // Names of tools this build no longer has are dropped on save.
    var draft by remember { mutableStateOf(enabled.filter { name -> Tools.all.any { it.name == name } }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Tools") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Tools.all.forEach { tool ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tool.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                tool.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tool.name in draft,
                            onCheckedChange = { on -> draft = if (on) draft + tool.name else draft - tool.name },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
