package com.hatsyrei.maidnative.domain

import com.hatsyrei.maidnative.domain.tree.MessageNode

private const val KEY_PROMPT = "promptTokens"
private const val KEY_COMPLETION = "completionTokens"
private const val KEY_GEN_MS = "genMs"
private const val KEY_GEN_TOKENS = "genTokens"
private const val KEY_TTFT_MS = "ttftMs"
private const val KEY_STOPPED = "stopped"
private const val KEY_CHARS = "chars"
private const val KEY_PROMPT_CHARS = "promptChars"

/**
 * What one assistant turn cost. Token counts come from the server's `usage`
 * chunk (OpenAI's `stream_options.include_usage`); the generation window comes
 * from llama.cpp's `timings` where the endpoint sends one, and from our own
 * clock otherwise.
 *
 * Stored as flat keys in [MessageNode.metadata], which is already a JSON text
 * column, so this needs no Room migration.
 *
 * Every field is nullable-by-absence rather than zero-filled: an endpoint that
 * reports no usage must render as "unknown", never as a confident 0.
 */
data class TurnStats(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** Wall time spent generating, excluding the wait for the first token. */
    val genMs: Long? = null,
    /**
     * Tokens produced within [genMs]. One fewer than [completionTokens] when the
     * window came from our own clock, which sees only the gaps BETWEEN arriving
     * tokens and so cannot cover the first one's generation.
     */
    val genTokens: Int? = null,
    /** Wall time from sending the request to the first token. */
    val ttftMs: Long? = null,
    /**
     * The user stopped this reply. An aborted stream carries no `usage`, so a
     * stopped turn has no token count, and its duration measures when the user
     * lost patience rather than what the endpoint was doing.
     */
    val stopped: Boolean = false,
    /**
     * Length of the body [completionTokens] was counted against. Comparing it
     * with the body now is how an edited reply knows which way its count moved,
     * without having to estimate anything.
     */
    val chars: Int? = null,
    /** Length of the thread [promptTokens] was counted against. */
    val promptChars: Int? = null,
) {
    val isEmpty: Boolean
        get() = promptTokens == null && completionTokens == null &&
            genMs == null && ttftMs == null && !stopped

    /** Total context occupied by this turn: everything read, plus everything written. */
    val totalTokens: Int?
        get() = if (promptTokens == null && completionTokens == null) {
            null
        } else {
            (promptTokens ?: 0) + (completionTokens ?: 0)
        }

    /**
     * Thread length, in characters, that [totalTokens] described — the prompt as
     * it was read, plus the body that answered it. Comparing it against the
     * thread as it now stands is how a conversation size knows it is stale,
     * whatever moved it: an edit, a deletion, a branch switch, or a turn that
     * reported nothing.
     */
    val countedChars: Int?
        get() = if (promptChars == null || chars == null) null else promptChars + chars

    /**
     * Which way [completionTokens] is wrong for [content], if at all. A body
     * longer than the one counted can only cost more tokens, a shorter one
     * fewer — so an edit needs no estimate, just a comparison.
     */
    fun bound(content: String): Bound = when {
        completionTokens == null || chars == null -> Bound.EXACT
        content.length > chars -> Bound.AT_LEAST
        content.length < chars -> Bound.AT_MOST
        else -> Bound.EXACT
    }

    /**
     * What this one turn generated at, or null when no honest figure exists: the
     * count must still describe [content], and the window must be long enough to
     * divide by.
     */
    fun tokensPerSecond(content: String): Double? {
        if (stopped || bound(content) != Bound.EXACT) return null
        val tokens = genTokens?.takeIf { it > 0 } ?: return null
        val ms = genMs ?: return null
        return if (ms > 0L) tokens * 1000.0 / ms else null
    }

    fun writeInto(metadata: Map<String, Any?>): Map<String, Any?> {
        if (isEmpty) return metadata
        return metadata +
            mapOf(
                KEY_PROMPT to promptTokens,
                KEY_COMPLETION to completionTokens,
                KEY_GEN_MS to genMs,
                KEY_GEN_TOKENS to genTokens,
                KEY_TTFT_MS to ttftMs,
                KEY_STOPPED to true.takeIf { stopped },
                // Each anchor is dead weight without the count it qualifies, and
                // an endpoint that reports no usage would otherwise carry one on
                // every message it ever produced.
                KEY_CHARS to chars?.takeIf { completionTokens != null },
                KEY_PROMPT_CHARS to promptChars?.takeIf { promptTokens != null },
            ).filterValues { it != null }
    }

    /** Which side of a token count the truth lies on. */
    enum class Bound { EXACT, AT_LEAST, AT_MOST }

    companion object {

        /**
         * Read back what [writeInto] stored. JSON round-trips an Int as an Int
         * or a Long depending on magnitude, so every read goes through
         * [Number] rather than a direct cast.
         *
         * Negatives are dropped rather than trusted: metadata arrives from
         * imported conversation files, which are user-supplied and may be
         * hand-edited or corrupt, and a negative count would otherwise produce
         * a negative rate or silently skew an average.
         */
        fun from(metadata: Map<String, Any?>): TurnStats = TurnStats(
            promptTokens = metadata.int(KEY_PROMPT),
            completionTokens = metadata.int(KEY_COMPLETION),
            genMs = metadata.long(KEY_GEN_MS),
            genTokens = metadata.int(KEY_GEN_TOKENS),
            ttftMs = metadata.long(KEY_TTFT_MS),
            stopped = metadata[KEY_STOPPED] == true,
            chars = metadata.int(KEY_CHARS),
            promptChars = metadata.int(KEY_PROMPT_CHARS),
        )

        private fun Map<String, Any?>.int(key: String): Int? =
            (get(key) as? Number)?.toInt()?.takeIf { it >= 0 }

        private fun Map<String, Any?>.long(key: String): Long? =
            (get(key) as? Number)?.toLong()?.takeIf { it >= 0L }
    }
}

/** Per-turn cost recorded on an assistant node, empty when the endpoint reported none. */
fun MessageNode.stats(): TurnStats = TurnStats.from(metadata)

