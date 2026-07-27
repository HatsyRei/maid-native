package com.hatsyrei.maidnative.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * Renders assistant markdown using the multiplatform-markdown-renderer library
 * (Material 3 module). Body text is pinned to `bodyMedium` to match the rest of
 * the chat message typography.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    Markdown(
        content = markdown,
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            ordered = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyMedium,
            list = MaterialTheme.typography.bodyMedium,
            quote = MaterialTheme.typography.bodyMedium,
        ),
        // Wider gap between blocks so a blank line (paragraph break) reads with
        // clear separation, closer to the RN markdown display.
        padding = markdownPadding(block = 8.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

