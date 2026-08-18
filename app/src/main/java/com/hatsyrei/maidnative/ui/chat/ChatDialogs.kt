package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hatsyrei.maidnative.domain.Attachment

/** The (mutually exclusive) modal dialogs [ChatScreen] can show. */
internal sealed interface ChatDialog {
    data class Edit(
        val id: String,
        val initial: String,
        val revise: Boolean,
        val attachments: List<Attachment>,
    ) : ChatDialog

    data class Rename(val id: String, val initial: String) : ChatDialog
    data class SystemPrompt(val initial: String) : ChatDialog
    data class DeleteMessage(val id: String) : ChatDialog
    data class DeleteChat(val id: String) : ChatDialog
}

/**
 * Deliberately not an [AlertDialog]: that puts the field in a floating window
 * the IME can only pan, so a long message loses its tail behind the keyboard
 * and every tap into the text pans back to the top of the (screen-tall) field.
 * A full-window dialog instead shrinks against `safeDrawing`, which bounds the
 * field's height so it scrolls internally and follows the cursor.
 *
 * The field is the `TextFieldState` overload for the same reason: the legacy
 * `value`/`onValueChange` one brings *the selection it captured at focus time*
 * into view (`CoreTextField`, b/216790855), which for a `String` value is index
 * 0 — so tapping into the text jumped straight back to the top.
 */
@Composable
internal fun EditDialog(
    initial: String,
    revise: Boolean,
    initialAttachments: List<Attachment>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<Attachment>) -> Unit,
) {
    val text = rememberTextFieldState(initial)
    var attachments by remember { mutableStateOf(initialAttachments) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The window now spans the screen, so `dismissOnClickOutside`
                // never fires; taps that miss the card stand in for it.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = AlertDialogDefaults.shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = if (revise) "Revise message" else "Edit message",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedTextField(
                        state = text,
                        // `AlertDialog` used to supply this through its text slot.
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(top = 16.dp),
                    )
                    if (attachments.isNotEmpty()) {
                        AttachmentChips(
                            attachments = attachments,
                            onRemove = { attachments = attachments - it },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(
                            onClick = { onConfirm(text.text.toString(), attachments) },
                            enabled = text.text.isNotBlank() || attachments.isNotEmpty(),
                        ) {
                            Text(if (revise) "Send" else "Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
