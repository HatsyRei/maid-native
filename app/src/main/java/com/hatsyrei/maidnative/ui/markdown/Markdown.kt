package com.hatsyrei.maidnative.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A small, dependency-free Markdown renderer for the common subset used in chat
 * replies: headings, bold/italic, inline code, fenced code blocks, blockquotes,
 * bullet/ordered lists, links, and horizontal rules. Deliberately simple (no
 * nested inline styles, no tables/images) to keep the binary tiny.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    text = inline(block.text, linkColor, inlineCodeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                )

                is Block.Paragraph -> Text(
                    text = inline(block.text, linkColor, inlineCodeBg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )

                is Block.Code -> CodeBlockView(block.code)

                is Block.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = inline(block.text, linkColor, inlineCodeBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (block.ordered) "${index + 1}. " else "\u2022 ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                            Text(
                                text = inline(item, linkColor, inlineCodeBg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                        }
                    }
                }

                Block.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CodeBlockView(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(12.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val code: String) : Block
    data class Quote(val text: String) : Block
    data class ListBlock(val items: List<String>, val ordered: Boolean) : Block
    data object Rule : Block
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)")
private val ORDERED = Regex("^\\d+\\.\\s+(.*)")

private fun isBullet(line: String) =
    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")

private fun stripBullet(line: String) = line.drop(2).trim()

private fun parseBlocks(md: String): List<Block> {
    val lines = md.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<Block>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        when {
            line.startsWith("```") -> {
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    sb.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // consume closing fence
                blocks += Block.Code(sb.toString().trimEnd('\n'))
            }

            line.isEmpty() -> i++

            HEADING.matches(line) -> {
                val m = HEADING.find(line)!!
                blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                i++
            }

            line == "---" || line == "***" || line == "___" -> {
                blocks += Block.Rule
                i++
            }

            line.startsWith(">") -> {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks += Block.Quote(sb.toString())
            }

            isBullet(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && isBullet(lines[i].trim())) {
                    items += stripBullet(lines[i].trim())
                    i++
                }
                blocks += Block.ListBlock(items, ordered = false)
            }

            ORDERED.matches(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED.matches(lines[i].trim())) {
                    items += ORDERED.find(lines[i].trim())!!.groupValues[1].trim()
                    i++
                }
                blocks += Block.ListBlock(items, ordered = true)
            }

            else -> {
                val sb = StringBuilder()
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.isEmpty() || l.startsWith("```") || HEADING.matches(l) ||
                        l.startsWith(">") || isBullet(l) || ORDERED.matches(l) ||
                        l == "---" || l == "***" || l == "___"
                    ) {
                        break
                    }
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(l)
                    i++
                }
                blocks += Block.Paragraph(sb.toString())
            }
        }
    }
    return blocks
}

/** Single-level inline parser: bold, italic, inline code, and links. */
private fun inline(text: String, linkColor: Color, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val rest = text.length - i

            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            if (c == '*' && rest >= 2 && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            if (c == '*' || c == '_') {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            if (c == '[') {
                val closeLabel = text.indexOf(']', i + 1)
                if (closeLabel > i && closeLabel + 1 < text.length && text[closeLabel + 1] == '(') {
                    val closeUrl = text.indexOf(')', closeLabel + 2)
                    if (closeUrl > closeLabel) {
                        val label = text.substring(i + 1, closeLabel)
                        val url = text.substring(closeLabel + 2, closeUrl)
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            append(label)
                        }
                        i = closeUrl + 1
                        continue
                    }
                }
            }

            append(c)
            i++
        }
    }
