package com.hatsyrei.maidnative.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.StreamingMarkdownState
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.rememberStreamingMarkdownState

/**
 * Module-level LRU cache of parsed markdown, keyed by the raw content string.
 *
 * Parsing (building the AST + reference-link lookup) is the expensive part of
 * rendering a message. Without this cache the library re-parses every bubble
 * each time it scrolls back into the `LazyColumn` viewport (items are disposed
 * when scrolled off), which burns CPU/battery when idly scrolling long chats.
 *
 * Only the parsed [State.Success] is cached; styling (typography, padding,
 * annotator) is still applied fresh at render time, so appearance is unchanged.
 * Access-ordered so the most recently viewed messages stay resident.
 */
private object MarkdownParseCache {
    /**
     * Budget expressed in source characters, not entry count.
     *
     * Retained AST size scales linearly with content length — measured at
     * ~9.5 bytes of AST per content character — so a character budget bounds the
     * heap directly. An entry count does not: 480 short messages retain ~2 MB
     * while 480 long ones retain ~34 MB, so a fixed entry cap is simultaneously
     * unreachable in the common case and far too permissive in the bad one.
     *
     * 512 Ki characters is roughly 5 MB of retained AST, which comfortably holds
     * any realistic single conversation (the cache is cleared on chat switch).
     */
    private const val MAX_CHARS = 512 * 1024

    private var charCount = 0

    private val lru = LinkedHashMap<String, State.Success>(64, 0.75f, /* accessOrder = */ true)

    @Synchronized
    fun get(content: String): State.Success? = lru[content]

    @Synchronized
    fun put(content: String, state: State.Success) {
        if (lru.put(content, state) == null) charCount += content.length
        // Evict least-recently-accessed entries until back under budget. Access
        // order means iteration starts at the eldest; the entry just inserted is
        // the newest, and `size > 1` keeps it resident even if it alone exceeds
        // the budget (it is the one being rendered right now).
        val entries = lru.entries.iterator()
        while (charCount > MAX_CHARS && lru.size > 1 && entries.hasNext()) {
            charCount -= entries.next().key.length
            entries.remove()
        }
    }

    @Synchronized
    fun clear() {
        lru.clear()
        charCount = 0
    }
}

/**
 * Drops all cached markdown parses. Called when switching conversations so the
 * retained ASTs of the previous chat don't linger on the heap for the rest of
 * the session (the cache is a process-lifetime object, not tied to composition).
 */
fun clearMarkdownParseCache() = MarkdownParseCache.clear()

/**
 * Shared chat markdown styling. Body text is pinned to `bodyMedium` to match the
 * rest of the chat message typography.
 */
private class ChatMarkdownStyle(
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
    val annotator: MarkdownAnnotator,
)

@Composable
private fun rememberChatMarkdownStyle(): ChatMarkdownStyle {
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
    val typography: MarkdownTypography = markdownTypography(
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
    )
    // Render a single newline (EOL) as a line break instead of collapsing it to a
    // space (the library's CommonMark-correct default). Matches the RN markdown
    // display, which keeps single newlines as breaks.
    val annotator = remember { markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)) }
    // Wider gap between blocks so a blank line (paragraph break) reads with clear
    // separation, closer to the RN markdown display.
    val padding = markdownPadding(block = 5.dp)
    return ChatMarkdownStyle(typography, padding, annotator)
}

/**
 * Renders settled (non-streaming) assistant markdown using the
 * multiplatform-markdown-renderer library (Material 3 module).
 *
 * The parse happens inside a single `remember`, so it runs once per content
 * value per composition and the result is shared through [MarkdownParseCache]
 * across scroll re-entry.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val style = rememberChatMarkdownStyle()
    val state = remember(markdown) {
        MarkdownParseCache.get(markdown)
            ?: parseMarkdown(markdown).also {
                if (it is State.Success) MarkdownParseCache.put(markdown, it)
            }
    }
    Markdown(
        state = state,
        typography = style.typography,
        padding = style.padding,
        annotator = style.annotator,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Builds an append-only [StreamingMarkdownState] for the reply currently being
 * streamed, and keeps it fed from [content] (the accumulated reply text).
 *
 * Only the delta since the last append is handed to the parser, so the parser
 * re-parses just the trailing unfinished block instead of the whole growing
 * reply — linear over a response rather than quadratic. Because each delta is
 * derived from the accumulated string, dropped/conflated recompositions are
 * harmless: the next append simply carries a larger delta.
 *
 * Hoist this above the message list. It must outlive the individual bubble's
 * composition, which `LazyColumn` disposes when scrolled out of view.
 *
 * @param sessionKey identifies the stream; a new value discards the old state.
 */
@Composable
fun rememberChatStreamingMarkdownState(
    sessionKey: String,
    content: String,
): StreamingMarkdownState = key(sessionKey) {
    val state = rememberStreamingMarkdownState()
    val latestContent = rememberUpdatedState(content)
    LaunchedEffect(state) {
        var appended = 0
        // snapshotFlow conflates, so a burst of tokens arriving faster than the
        // parser collapses into one larger append instead of queueing.
        snapshotFlow { latestContent.value }.collect { text ->
            if (text.length > appended) {
                val delta = text.substring(appended)
                appended = text.length
                state.append(delta)
            }
        }
    }
    state
}

/**
 * Renders the actively streaming reply from an incrementally parsed
 * [StreamingMarkdownState]. Blocks that the parser has already finalised keep
 * their AST node identity across appends, so Compose can skip re-rendering them.
 */
@Composable
fun StreamingMarkdownText(
    state: StreamingMarkdownState,
    modifier: Modifier = Modifier,
) {
    val style = rememberChatMarkdownStyle()
    Markdown(
        streamingMarkdownState = state,
        typography = style.typography,
        padding = style.padding,
        annotator = style.annotator,
        modifier = modifier.fillMaxWidth(),
    )
}

