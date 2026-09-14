package com.hatsyrei.maidnative.domain

import com.hatsyrei.maidnative.domain.tree.MessageNode

/**
 * Aggregate figures for one conversation, computed over its ACTIVE THREAD —
 * the same nodes [com.hatsyrei.maidnative.domain.tree.MessageTree.getConversation]
 * returns and the same ones on screen. Branches the user has navigated away
 * from are excluded, deliberately and uniformly: one rule for every figure
 * here, so nothing needs an asterisk.
 *
 * Every field is nullable where the data may not exist. Conversations that
 * predate per-turn stats, and endpoints that report no usage, leave gaps;
 * [statsTurns] says how many replies actually backed the averages so the UI can
 * admit to a partial sample rather than quietly presenting one as complete.
 */
data class ChatStats(
    /**
     * Size of the conversation as the model sees it: the most recent reply's
     * prompt plus its completion.
     *
     * Not a sum over turns. Each request replays the whole history, so the last
     * turn's `prompt_tokens` already counts everything before it — adding the
     * turns together would count most of the conversation many times over and
     * measure cumulative *work*, not size.
     */
    val conversationTokens: Int? = null,
    /** How far [conversationTokens] can be trusted. */
    val sizeAccuracy: SizeAccuracy = SizeAccuracy.EXACT,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    /** Assistant replies that carried timing, i.e. the sample behind the averages. */
    val statsTurns: Int = 0,
    val tokensPerSecond: Double? = null,
    /** Send to last token, averaged: time to first token plus generation. */
    val averageResponseMs: Long? = null,
    val averageTtftMs: Long? = null,
) {
    val messages: Int get() = userMessages + assistantMessages

    /** True when some replies contributed no timing, so the averages are a subset. */
    val partialSample: Boolean get() = statsTurns in 1 until assistantMessages

    enum class SizeAccuracy {
        EXACT,

        /** The thread has grown since it was last counted, so this is a floor. */
        AT_LEAST,

        /** The thread has shrunk since it was last counted, so this is a ceiling. */
        AT_MOST,
    }

    companion object {

        /**
         * One forward pass, arithmetic only. [conversation] is expected to be an
         * active thread; the system root is skipped rather than counted as a
         * message, since it is not one on screen either.
         */
        fun of(conversation: List<MessageNode>): ChatStats {
            var users = 0
            var assistants = 0
            var lastTotal: Int? = null
            var countedChars: Int? = null
            var threadChars = 0

            var timedTurns = 0
            var responseMs = 0L
            var ttftTurns = 0
            var ttftMs = 0L
            var speedTokens = 0L
            var speedMs = 0L

            for (node in conversation) {
                // Includes the system root, which is replayed on every request
                // like any other message.
                threadChars += node.content.length
                when (node.role) {
                    "user" -> { users++; continue }
                    "assistant" -> assistants++
                    else -> continue
                }

                val stats = node.stats()
                // Only a measured PROMPT counts the history. A stopped reply
                // never gets one, so it leaves the size to the last turn that
                // did and shows up instead as text that count has not seen —
                // which is exactly how an edit or a deletion shows up too.
                if (stats.promptTokens != null) {
                    lastTotal = stats.totalTokens
                    countedChars = stats.countedChars ?: threadChars
                }

                val gen = stats.genMs
                // A stopped reply's duration measures when the user lost
                // patience, not what the endpoint was doing, so averaging it in
                // would drag the response time toward however fast they are on
                // the button. Its time to first token is still a real reading.
                if (gen != null && !stats.stopped) {
                    timedTurns++
                    responseMs += gen + (stats.ttftMs ?: 0L)
                    // Pool each turn's own tokens and time rather than its rate,
                    // so a long reply weighs more than a short one. An edited
                    // body's count no longer describes what was timed.
                    val produced = stats.genTokens
                    if (produced != null && gen > 0L &&
                        stats.bound(node.content) == TurnStats.Bound.EXACT
                    ) {
                        speedTokens += produced
                        speedMs += gen
                    }
                }
                stats.ttftMs?.let { ttftTurns++; ttftMs += it }
            }

            val counted = countedChars
            return ChatStats(
                conversationTokens = lastTotal,
                sizeAccuracy = when {
                    // Nothing to qualify: there is no figure to be wrong about.
                    lastTotal == null || counted == null -> SizeAccuracy.EXACT
                    threadChars > counted -> SizeAccuracy.AT_LEAST
                    threadChars < counted -> SizeAccuracy.AT_MOST
                    else -> SizeAccuracy.EXACT
                },
                userMessages = users,
                assistantMessages = assistants,
                statsTurns = timedTurns,
                tokensPerSecond = if (speedMs > 0L) speedTokens * 1000.0 / speedMs else null,
                averageResponseMs = if (timedTurns > 0) responseMs / timedTurns else null,
                averageTtftMs = if (ttftTurns > 0) ttftMs / ttftTurns else null,
            )
        }
    }
}
