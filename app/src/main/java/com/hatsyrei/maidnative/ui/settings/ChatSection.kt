package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository

/** Display names used for the role labels on message cards. */
@Composable
internal fun ChatSection(
    userName: String,
    assistantName: String,
    exportMedia: Boolean,
    onUserName: (String) -> Unit,
    onAssistantName: (String) -> Unit,
    onExportMedia: (Boolean) -> Unit,
) {
    var user by remember(userName) { mutableStateOf(userName) }
    var assistant by remember(assistantName) { mutableStateOf(assistantName) }

    Text("Chat", style = MaterialTheme.typography.titleMedium)

    AutoSaveTextField(
        text = user,
        onTextChange = { user = it },
        committed = userName,
        label = "Your name",
        onCommit = { value ->
            // Normalise locally too: the stored value falls back to the default
            // when blank, and an unchanged fallback wouldn't re-key the field.
            val name = value.trim().ifBlank { SettingsRepository.DEFAULT_USER_NAME }
            user = name
            onUserName(name)
        },
        modifier = Modifier.fillMaxWidth(),
    )

    AutoSaveTextField(
        text = assistant,
        onTextChange = { assistant = it },
        committed = assistantName,
        label = "Assistant name",
        onCommit = { value ->
            val name = value.trim().ifBlank { SettingsRepository.DEFAULT_ASSISTANT_NAME }
            assistant = name
            onAssistantName(name)
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Export media", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Includes attached images and audio in exported conversations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = exportMedia, onCheckedChange = onExportMedia)
    }
}
