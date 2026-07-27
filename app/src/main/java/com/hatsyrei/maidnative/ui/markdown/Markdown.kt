package com.hatsyrei.maidnative.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    // Derive headings proportionally from the body size (rather than the
    // library's oversized display/headline defaults) so they keep a clear
    // hierarchy while staying in scale with the chat text.
    val base = MaterialTheme.typography.bodyMedium
    val heading = @Composable { scale: Float ->
        base.copy(
            fontSize = base.fontSize * scale,
            // Scale line height with the font so wrapped heading lines keep a
            // proportional gap instead of squishing together.
            lineHeight = base.lineHeight * scale,
            fontWeight = FontWeight.Medium,
        )
    }
    Markdown(
        content = markdown,
        typography = markdownTypography(
            h1 = heading(2.0f),
            h2 = heading(1.75f),
            h3 = heading(1.5f),
            h4 = heading(1.3f),
            h5 = heading(1.15f),
            h6 = heading(1.05f),
            text = base,
            paragraph = base,
            ordered = base,
            bullet = base,
            list = base,
            quote = base,
        ),
        // Wider gap between blocks so a blank line (paragraph break) reads with
        // clear separation, closer to the RN markdown display.
        padding = markdownPadding(block = 5.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

