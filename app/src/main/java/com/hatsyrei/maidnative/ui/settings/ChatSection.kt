package com.hatsyrei.maidnative.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.store.AvatarStore
import com.hatsyrei.maidnative.ui.chat.MenuOption
import com.hatsyrei.maidnative.ui.chat.TapContextMenu
import com.hatsyrei.maidnative.ui.common.Avatar
import com.hatsyrei.maidnative.ui.common.rememberAvatar
import com.hatsyrei.maidnative.ui.icons.ImageIcon

private val AVATAR_SIZE = 56.dp

/** Display names and pictures used for the role labels on message cards. */
@Composable
internal fun ChatSection(
    userName: String,
    assistantName: String,
    userAvatar: Long,
    assistantAvatar: Long,
    exportMedia: Boolean,
    actions: SettingsActions,
) {
    var user by remember(userName) { mutableStateOf(userName) }
    var assistant by remember(assistantName) { mutableStateOf(assistantName) }

    Text("Chat", style = MaterialTheme.typography.titleMedium)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // A labelled OutlinedTextField reserves 8dp above its border for the
        // floating label, so only its bottom edge lines up with the circle.
        verticalAlignment = Alignment.Bottom,
    ) {
        AvatarPicker(
            role = AvatarStore.Role.USER,
            stamp = userAvatar,
            name = user,
            label = "Your profile picture",
            actions = actions,
        )
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
                actions.setUserName(name)
            },
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AvatarPicker(
            role = AvatarStore.Role.ASSISTANT,
            stamp = assistantAvatar,
            name = assistant,
            label = "Assistant profile picture",
            actions = actions,
        )
        AutoSaveTextField(
            text = assistant,
            onTextChange = { assistant = it },
            committed = assistantName,
            label = "Assistant name",
            onCommit = { value ->
                val name = value.trim().ifBlank { SettingsRepository.DEFAULT_ASSISTANT_NAME }
                assistant = name
                actions.setAssistantName(name)
            },
            modifier = Modifier.weight(1f),
        )
    }

    Text(
        "Pictures are cropped to a circle and copied into the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Switch(checked = exportMedia, onCheckedChange = actions.setExportMedia)
    }
}

/** Preview of the stored picture, tapped to choose a new one or clear it. */
@Composable
private fun AvatarPicker(
    role: AvatarStore.Role,
    stamp: Long,
    name: String,
    label: String,
    actions: SettingsActions,
) {
    var open by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { actions.importAvatar(role, it) } }
    val painter = rememberAvatar(role, stamp)
    // The shared menu centres itself on the touch point; this circle is small
    // enough that its middle stands in for wherever the finger landed.
    val density = LocalDensity.current
    val centre = remember(density) {
        val half = with(density) { AVATAR_SIZE.toPx() } / 2f
        Offset(half, half)
    }

    Box {
        Avatar(
            painter = painter,
            name = name,
            size = AVATAR_SIZE,
            modifier = Modifier
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable { open = true }
                .semantics { contentDescription = label },
        )
        TapContextMenu(
            expanded = open,
            touchOffset = centre,
            onDismiss = { open = false },
        ) {
            MenuOption(
                text = if (stamp == 0L) "Select image" else "Change image",
                trailingIcon = { Icon(ImageIcon, contentDescription = null) },
                onClick = {
                    open = false
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            MenuOption(
                text = "Remove",
                trailingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                enabled = stamp != 0L,
                onClick = {
                    open = false
                    actions.removeAvatar(role)
                },
            )
        }
    }
}
