package com.hatsyrei.maidnative.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One-field confirm dialog: rename, system prompt, preset name.
 *
 * The caret is seeded at the end so editing an existing value starts where the
 * user would continue typing rather than before it. [supportingText] is called
 * with the trimmed text and may return an empty string to hold the slot open,
 * which keeps a conditional warning from resizing the dialog as it appears.
 */
@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    description: String? = null,
    label: String? = null,
    multiline: Boolean = false,
    supportingText: ((String) -> String)? = null,
) {
    val text = rememberTextFieldState(initial)
    val trimmed = text.text.toString().trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.liftAboveIme(),
        properties = DialogOverIme,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title) },
        text = {
            Column {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    state = text,
                    label = label?.let { { Text(it) } },
                    lineLimits = if (multiline) {
                        TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 8)
                    } else {
                        TextFieldLineLimits.SingleLine
                    },
                    supportingText = supportingText?.let {
                        { Text(text = it(trimmed), modifier = Modifier.padding(top = 6.dp)) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (description != null) 12.dp else 0.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
