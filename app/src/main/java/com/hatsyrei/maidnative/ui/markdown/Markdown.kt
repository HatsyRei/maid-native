package com.hatsyrei.maidnative.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState

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
    private const val MAX_ENTRIES = 480

    private val lru = object : LinkedHashMap<String, State.Success>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, State.Success>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(content: String): State.Success? = lru[content]

    @Synchronized
    fun put(content: String, state: State.Success) {
        lru[content] = state
    }

    @Synchronized
    fun clear() {
        lru.clear()
    }
}

/**
 * Drops all cached markdown parses. Called when switching conversations so the
 * retained ASTs of the previous chat don't linger on the heap for the rest of
 * the session (the cache is a process-lifetime object, not tied to composition).
 */
fun clearMarkdownParseCache() = MarkdownParseCache.clear()

/**
 * Renders assistant markdown using the multiplatform-markdown-renderer library
 * (Material 3 module). Body text is pinned to `bodyMedium` to match the rest of
 * the chat message typography.
 *
 * @param cache when true, the parsed result is cached and reused across scroll
 * re-entry. Pass false for the actively streaming bubble, whose content changes
 * every token and would otherwise flood the cache with transient partials.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    cache: Boolean = true,
) {
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

    // Read the cache once per content value: a hit (message scrolled back into
    // view) renders the already-parsed AST directly; a miss parses via the
    // library and stores the result below.
    val cached = remember(markdown) { if (cache) MarkdownParseCache.get(markdown) else null }
    if (cached != null) {
        Markdown(
            state = cached,
            typography = typography,
            padding = padding,
            annotator = annotator,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    // Parse synchronously (`immediate = true`) so a fresh `MarkdownState` is
    // already in its `Success` state within the same composition. The default
    // string overload parses in a `LaunchedEffect`, which flips back to the
    // Loading slot on every streamed chunk and causes a visible flash.
    val state = rememberMarkdownState(content = markdown, immediate = true)
    if (cache) {
        // Stash the parsed result so the next scroll re-entry is a cache hit.
        val current by state.state.collectAsState()
        LaunchedEffect(current) {
            (current as? State.Success)?.let { MarkdownParseCache.put(markdown, it) }
        }
    }
    Markdown(
        markdownState = state,
        typography = typography,
        padding = padding,
        annotator = annotator,
        modifier = modifier.fillMaxWidth(),
    )
}

