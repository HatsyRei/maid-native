package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.remote.EndpointScanner

/** Ports that OpenAI-compatible servers ship with, offered as one-tap fills. */
private val COMMON_PORTS = listOf(8080, 9931, 1234, 11434, 8000)

/** The Base URL field's scan action; tapping it opens the scan options dialog. */
@Composable
internal fun ScanButton(
    scanning: Boolean,
    succeeded: Boolean,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onScan,
        enabled = !scanning && !succeeded,
        modifier = modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        when {
            scanning -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            succeeded -> Icon(Icons.Filled.Check, contentDescription = "Endpoint found")
            else -> Icon(Icons.Filled.Search, contentDescription = "Scan for endpoint")
        }
    }
}

/** Port + subnet size for the scan the dialog starts; the choice is persisted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScanOptionsDialog(
    port: Int,
    prefixLength: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var portText by remember { mutableStateOf(port.toString()) }
    var prefix by remember { mutableIntStateOf(prefixLength) }
    val parsedPort = portText.toIntOrNull()
    val portValid = parsedPort != null &&
        parsedPort in SettingsRepository.MIN_PORT..SettingsRepository.MAX_PORT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The local subnet is probed on this port for an " +
                        "OpenAI-compatible endpoint.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = portText,
                    // Digits only, so the field can never hold something the
                    // keyboard type would otherwise let through (paste, hardware).
                    onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = !portValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        Text(if (portValid) "" else "Enter a port between 1 and 65535")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    COMMON_PORTS.forEach { common ->
                        FilterChip(
                            selected = parsedPort == common,
                            onClick = { portText = common.toString() },
                            label = { Text(common.toString()) },
                        )
                    }
                }

                Text(
                    "Subnet",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EndpointScanner.PREFIX_CHOICES.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = prefix == choice,
                            onClick = { prefix = choice },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = EndpointScanner.PREFIX_CHOICES.size,
                            ),
                            // Dropping the check mark keeps five segments legible
                            // at dialog width.
                            icon = {},
                            label = { Text("/$choice") },
                        )
                    }
                }
                Text(
                    "${hostCount(prefix)} addresses probed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsedPort ?: EndpointScanner.DEFAULT_PORT, prefix) },
                enabled = portValid,
            ) {
                Text("Scan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Usable hosts in a /[prefixLength], i.e. minus the network and broadcast addresses. */
private fun hostCount(prefixLength: Int): Long = (1L shl (32 - prefixLength)) - 2
