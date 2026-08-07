package com.hatsyrei.maidnative.domain

/**
 * Separates an assistant reply from its reasoning trace. Two entry points:
 *
 *  - [split] for text that is already complete — a stored message, an edit, a
 *    paste. Ported from utilities/reasoning.ts.
 *  - [Scanner] for a live stream, which classifies each delta once instead of
 *    re-splitting the accumulated reply on every tick.
 *
 * Both exist because real backends are inconsistent:
 *
 *  - The tag varies (`<think>`, `<thinking>`, `<|think|>`, `<seed:think|>`,
 *    ...). An unrecognised tag used to fall through untouched, and the markdown
 *    parser then swallowed it *and everything up to the next blank line* as a
 *    raw HTML block — which is why the rest of the reply appeared to vanish.
 *  - A model may emit several blocks in one reply.
 *  - Some chat templates emit the *opening* tag themselves, so the reply starts
 *    mid-thought and only `</think>` ever arrives.
 *  - While streaming, the tail can be a half-arrived tag (`"<thi"`), which would
 *    otherwise flicker into the rendered bubble.
 *
 * Backends that report reasoning on a dedicated delta field never reach here;
 * `OpenAiClient` maps those straight onto [Chunk.Thought].
 */
object Reasoning {

    /**
     * Word stems that name a reasoning block. Only the stem is listed; the
     * decorations below are applied to every one of them.
     */
    private val STEMS = listOf(
        "think",
        "thinking",
        "thought",
        "thoughts",
        "reason",
        "reasoning",
    )

    /** Vendor prefixes seen on pipe-terminated tags, e.g. `<seed:think|>`. */
    private val NAMESPACES = listOf("seed")

    /**
     * Tag bodies (everything between the angle brackets and after any `/`).
     *
     * Three shapes occur in the chat templates llama.cpp's UI inspects in
     * `chat-template-thinking-detector.ts` — DeepSeek-R1/V3, Nemotron, QwQ,
     * Qwen3, Gemma, Kimi-K2, Apertus, Mistral-Small-3.2 and Seed-OSS:
     *
     *  - bare, `<think>` / `</think>`
     *  - pipe-delimited, `<|think|>` / `</|think|>`
     *  - namespaced, `<seed:think|>` / `</seed:think|>`
     *
     * Only `think` was ever *observed* in the decorated shapes, but the shape
     * is a tokenizer convention rather than a per-model choice, so applying it
     * across every stem costs nothing and means a model shipping `<|thought|>`
     * works without a code change. Anything containing `|` cannot collide with
     * real HTML or XML, so widening here is close to free.
     *
     * Kept as literal strings rather than folded into the regex because
     * [partialTagLength] prefix-matches against this list to hold back a
     * half-arrived tag; a flat list makes that check trivially correct.
     */
    private val TAG_BODIES: List<String> = buildList {
        for (stem in STEMS) {
            add(stem)
            add("|$stem|")
            for (namespace in NAMESPACES) add("$namespace:$stem|")
        }
    }

    /**
     * Group 1 is "/" for a closing tag, group 2 is the tag body. Alternation
     * order is irrelevant: a shorter stem that matches first still backtracks
     * when the mandatory `>` fails, so `<thinking>` cannot be read as `think`.
     */
    private val TAG = Regex(
        "<(/?)(" + TAG_BODIES.joinToString("|") { Regex.escape(it) } + ")\\s*>",
        RegexOption.IGNORE_CASE,
    )

    fun split(raw: String): Pair<String?, String?> {
        // Two gates before the copying path below, because `split` re-runs over
        // the whole accumulated reply on every streaming tick. Neither allocates:
        // a reply with no '<' is handed straight back, and one that merely
        // *contains* a '<' (generics, `a < b`, an HTML sample) still skips the
        // buffers — `stripPartialTag` returns its receiver unless it trims.
        if (raw.indexOf('<') < 0) return raw to null
        if (!TAG.containsMatchIn(raw)) {
            return stripPartialTag(raw).ifEmpty { null } to null
        }

        val content = StringBuilder()
        val reasoning = StringBuilder()
        var index = 0
        var inReasoning = false
        var closedOnce = false

        while (true) {
            val match = TAG.find(raw, index) ?: break
            val text = raw.substring(index, match.range.first)
            (if (inReasoning) reasoning else content).append(text)
            index = match.range.last + 1

            if (match.groupValues[1] == "/") {
                when {
                    inReasoning -> {
                        inReasoning = false
                        closedOnce = true
                    }
                    // Orphan close with nothing opened yet: the chat template
                    // supplied the opening tag, so everything so far was thought.
                    !closedOnce && reasoning.isEmpty() -> {
                        reasoning.append(content)
                        content.setLength(0)
                        closedOnce = true
                    }
                    // Stray close after a complete block: drop the tag only.
                    else -> Unit
                }
            } else if (!inReasoning) {
                // Separate consecutive blocks; a nested/repeated open inside an
                // already-open block is dropped.
                if (reasoning.isNotEmpty()) reasoning.append("\n\n")
                inReasoning = true
            }
        }

        (if (inReasoning) reasoning else content).append(raw, index, raw.length)

        val display = stripPartialTag(content.toString()).trim()
        val thought = reasoning.toString().trim()
        return display.ifEmpty { null } to thought.ifEmpty { null }
    }

    /**
     * Drops a trailing fragment that is a prefix of one of our tags (`"<"`,
     * `"</thi"`, ...) so a mid-stream tag never reaches the markdown renderer.
     */
    private fun stripPartialTag(text: String): String {
        val partial = partialTagLength(text)
        return if (partial == 0) text else text.substring(0, text.length - partial)
    }

    /**
     * Length of the trailing run that could still grow into one of our tags, or
     * 0 when the text cannot be ending mid-tag. Scans in place from the last
     * `'<'` rather than slicing out a candidate, because this runs on every
     * streaming tick.
     */
    private fun partialTagLength(text: String): Int {
        val open = text.lastIndexOf('<')
        if (open < 0) return 0
        var i = open + 1
        if (i < text.length && text[i] == '/') i++
        val bodyStart = i
        // Any other character — including the '>' of a complete tag — means this
        // is not a tag still in flight.
        while (i < text.length) {
            if (!isTagBodyChar(text[i])) return 0
            i++
        }
        val length = i - bodyStart
        val isPrefix = TAG_BODIES.any {
            it.regionMatches(0, text, bodyStart, length, ignoreCase = true)
        }
        return if (isPrefix) text.length - open else 0
    }

    private fun isTagBodyChar(c: Char): Boolean = c.isLetter() || c == '|' || c == ':'

    /** One classified piece of a streamed reply. */
    sealed interface Chunk {
        /** Text belonging to the visible reply. */
        data class Reply(val text: String) : Chunk

        /** Text belonging to the reasoning trace. */
        data class Thought(val text: String) : Chunk

        /**
         * Everything emitted as [Reply] so far was actually reasoning. Sent when
         * a closing tag arrives with nothing ever opened, which is what a chat
         * template that ends its prompt with `<think>` produces — the reply
         * starts mid-thought and only `</think>` is ever generated.
         */
        data object Reclassify : Chunk
    }

    /**
     * Incremental counterpart to [split], for classifying a reply as it streams.
     *
     * Splitting the accumulated text on every tick is quadratic over a response;
     * this walks each delta exactly once instead. A tag can straddle two chunks
     * (`"<thi"` then `"nk>"`), so a suffix that might still grow into a tag is
     * held back until the next delta resolves it. Everything else is emitted
     * immediately, which is what keeps both the trace and the reply live.
     *
     * One instance per stream. Not thread-safe; the SSE callback is serial.
     */
    class Scanner {
        private val pending = StringBuilder()
        private var inReasoning = false
        private var closedOnce = false
        private var sawReasoning = false

        /** Classifies one delta, emitting zero or more chunks in order. */
        fun feed(text: String, emit: (Chunk) -> Unit) {
            if (text.isEmpty()) return
            pending.append(text)
            val buf = pending.toString()
            pending.setLength(0)

            var index = 0
            while (true) {
                val match = TAG.find(buf, index) ?: break
                emitText(buf.substring(index, match.range.first), emit)
                index = match.range.last + 1

                if (match.groupValues[1] == "/") {
                    when {
                        inReasoning -> {
                            inReasoning = false
                            closedOnce = true
                        }
                        !closedOnce && !sawReasoning -> {
                            emit(Chunk.Reclassify)
                            sawReasoning = true
                            closedOnce = true
                        }
                        // Stray close after a complete block: drop the tag only.
                        else -> Unit
                    }
                } else if (!inReasoning) {
                    inReasoning = true
                    sawReasoning = true
                }
            }

            val tail = buf.substring(index)
            val keep = tail.length - partialTagLength(tail)
            emitText(tail.substring(0, keep), emit)
            pending.append(tail, keep, tail.length)
        }

        /**
         * Flushes a held-back suffix that never became a tag. Call once when the
         * stream ends, otherwise a reply ending in `"<"` would lose it.
         */
        fun finish(emit: (Chunk) -> Unit) {
            if (pending.isEmpty()) return
            emitText(pending.toString(), emit)
            pending.setLength(0)
        }

        private fun emitText(text: String, emit: (Chunk) -> Unit) {
            if (text.isEmpty()) return
            emit(if (inReasoning) Chunk.Thought(text) else Chunk.Reply(text))
        }
    }
}
