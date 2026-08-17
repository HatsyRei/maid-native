package com.hatsyrei.maidnative.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.ui.icons.CloseIcon
import com.hatsyrei.maidnative.ui.icons.SaveAltIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen look at one attachment. Images fill the window on a dark scrim;
 * everything else is shown as the plain text that was inlined into the prompt,
 * which is the only place the user can see what the model actually received.
 *
 * [onSave] hands the destination back to the caller rather than writing here:
 * the copy is app-private, so exporting it is a store concern and its failure
 * belongs on the same error banner as every other write.
 */
@Composable
internal fun AttachmentViewer(
    attachment: Attachment,
    onSave: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val isImage = attachment.kind == Attachment.Kind.IMAGE
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(attachment.mime),
    ) { uri -> uri?.let(onSave) }

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
                .background(
                    if (isImage) Color.Black else MaterialTheme.colorScheme.surface,
                ),
        ) {
            if (isImage) {
                ImagePane(attachment)
            } else {
                TextPane(attachment)
            }
            CompositionLocalProvider(
                LocalContentColor provides
                    if (isImage) Color.White else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    if (isImage) {
                        IconButton(onClick = { saveLauncher.launch(attachment.name) }) {
                            Icon(SaveAltIcon, contentDescription = "Save image")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(CloseIcon, contentDescription = "Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePane(attachment: Attachment) {
    val image by produceState<ImageBitmap?>(null, attachment.path) {
        value = withContext(Dispatchers.IO) { decodeCapped(attachment.path) }
    }
    val bitmap = image
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Missing()
        }
    }
}

@Composable
private fun TextPane(attachment: Attachment) {
    val text by produceState<String?>(null, attachment.path) {
        value = withContext(Dispatchers.IO) { readCapped(attachment.path) }
    }
    val body = text
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (body == null) {
            Missing()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 56.dp, bottom = 24.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Missing() {
    Text(
        text = "This file is no longer available.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Sampled down to roughly the largest screen worth drawing: an imported
 * attachment carries whatever pixel count its origin device chose, so a full
 * decode is not safe to attempt.
 */
private fun decodeCapped(path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= VIEWER_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

private fun readCapped(path: String): String? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    val buffer = ByteArray(MAX_PREVIEW_BYTES)
    var read = 0
    file.inputStream().use { input ->
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
    }
    val body = buffer.copyOf(read).toString(Charsets.UTF_8)
    if (file.length() > MAX_PREVIEW_BYTES) "$body\n\n… truncated" else body
}.getOrNull()

private const val VIEWER_PX = 1440
private const val MAX_PREVIEW_BYTES = 256 * 1024
