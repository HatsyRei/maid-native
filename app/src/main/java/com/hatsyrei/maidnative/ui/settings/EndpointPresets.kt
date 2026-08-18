package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository.EndpointPreset
import com.hatsyrei.maidnative.ui.common.TextInputDialog

private val MenuItemPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)

/** On top of the 12dp the menu item already puts between label and trailing icon. */
private val MenuIconGap = 8.dp

/**
 * The saved Base URL + API key pairs, as a bottom sheet: saving and loading share
 * one affordance, and the extra height over a dialog leaves room for each row's
 * rename/delete menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EndpointPresetSheet(
    presets: List<EndpointPreset>,
    activeBaseURL: String,
    activeApiKey: String,
    onSaveCurrent: () -> Unit,
    onApply: (EndpointPreset) -> Unit,
    onRename: (EndpointPreset) -> Unit,
    onDelete: (EndpointPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = "Saved endpoints",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Save current endpoint") },
                supportingContent = {
                    Text(activeBaseURL, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                colors = TransparentListItem(),
                modifier = Modifier.clickable(onClick = onSaveCurrent),
            )
            HorizontalDivider()
            if (presets.isEmpty()) {
                Text(
                    text = "Nothing saved yet. A preset keeps a Base URL and its API key " +
                        "together, so switching endpoints takes one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(presets, key = { it.id }) { preset ->
                        PresetRow(
                            preset = preset,
                            active = preset.baseURL == activeBaseURL && preset.apiKey == activeApiKey,
                            onApply = { onApply(preset) },
                            onRename = { onRename(preset) },
                            onDelete = { onDelete(preset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: EndpointPreset,
    active: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                text = preset.name,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(preset.baseURL, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(maskKey(preset.apiKey), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = {
            if (active) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "In use",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Preset options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.padding(start = MenuIconGap),
                            )
                        },
                        contentPadding = MenuItemPadding,
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = MenuIconGap),
                            )
                        },
                        contentPadding = MenuItemPadding,
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        colors = TransparentListItem(),
        modifier = Modifier.clickable(onClick = onApply),
    )
}

/** Rows sit on the sheet's own container instead of repainting it with `surface`. */
@Composable
private fun TransparentListItem() =
    ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun PresetNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    takenNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    TextInputDialog(
        title = title,
        initial = initial,
        confirmLabel = confirmLabel,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        label = "Preset name",
        // Returns "" rather than nothing so the slot stays occupied, and the
        // warning appearing doesn't resize the dialog.
        supportingText = { name ->
            if (takenNames.any { it.equals(name, ignoreCase = true) }) {
                "Replaces an existing preset"
            } else {
                ""
            }
        },
    )
}

/** Seeds the save dialog with the endpoint's host, which is the name most users would type anyway. */
internal fun defaultPresetName(baseURL: String): String =
    baseURL.substringAfter("://").substringBefore("/").substringBefore(":").ifBlank { "Endpoint" }

/** Never renders a key that could be read off the screen; short keys show no digits at all. */
private fun maskKey(key: String): String = when {
    key.isEmpty() -> "No API key"
    key.length <= 8 -> "••••"
    else -> "••••" + key.takeLast(4)
}
