package com.hatsyrei.maidnative.domain

import com.hatsyrei.maidnative.domain.tree.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStatsTest {

    private fun system(content: String = "") =
        MessageNode("r", "system", content, root = "r", parent = null)

    private fun user(id: String, content: String = "hi") =
        MessageNode(id, "user", content, root = "r", parent = null)

    /**
     * [chars] and [promptChars] default to a turn nobody has touched since it was
     * measured, so only the tests about staleness have to mention them.
     */
    private fun assistant(
        id: String,
        content: String = "ok",
        prompt: Int? = null,
        completion: Int? = null,
        genMs: Long? = null,
        genTokens: Int? = null,
        ttftMs: Long? = null,
        stopped: Boolean = false,
        chars: Int? = content.length,
        promptChars: Int? = null,
    ) = MessageNode(
        id = id,
        role = "assistant",
        content = content,
        root = "r",
        parent = null,
        metadata = TurnStats(
            promptTokens = prompt,
            completionTokens = completion,
            genMs = genMs,
            genTokens = genTokens,
            ttftMs = ttftMs,
            stopped = stopped,
            chars = chars,
            promptChars = promptChars,
        ).writeInto(emptyMap()),
    )

    @Test
    fun `counts messages and ignores the system root`() {
        val stats = ChatStats.of(listOf(system(), user("u1"), assistant("a1"), user("u2")))
        assertEquals(2, stats.userMessages)
        assertEquals(1, stats.assistantMessages)
        assertEquals(3, stats.messages)
    }

    /** Conversation size is the LAST reply's prompt+completion, never a sum. */
    @Test
    fun `conversation size does not accumulate across turns`() {
        val stats = ChatStats.of(
            listOf(
                system(),
                user("u1"),
                assistant("a1", prompt = 100, completion = 50),
                user("u2"),
                assistant("a2", prompt = 400, completion = 60),
            ),
        )
        assertEquals(460, stats.conversationTokens)
    }

    @Test
    fun `conversation size falls back to the newest turn that reported one`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", prompt = 100, completion = 50),
                assistant("a2", genMs = 500L),
            ),
        )
        assertEquals(150, stats.conversationTokens)
    }

    // --- averages -----------------------------------------------------------

    @Test
    fun `speed pools tokens and time rather than averaging per-turn rates`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 101, genTokens = 100, genMs = 1000L),
                assistant("a2", completion = 51, genTokens = 50, genMs = 1000L),
            ),
        )
        assertEquals(75.0, stats.tokensPerSecond!!, 0.001)
    }

    @Test
    fun `response time includes time to first token`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 10, genMs = 1000L, ttftMs = 500L),
                assistant("a2", completion = 10, genMs = 2000L, ttftMs = 500L),
            ),
        )
        assertEquals(2000L, stats.averageResponseMs)
        assertEquals(500L, stats.averageTtftMs)
    }

    @Test
    fun `a turn with no timing is excluded from the averages`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 101, genTokens = 100, genMs = 1000L, ttftMs = 200L),
                assistant("a2"),
            ),
        )
        assertEquals(100.0, stats.tokensPerSecond!!, 0.001)
        assertEquals(1200L, stats.averageResponseMs)
        assertEquals(1, stats.statsTurns)
        assertEquals(2, stats.assistantMessages)
        assertTrue(stats.partialSample)
    }

    /**
     * A hand-edited reply keeps its count and its real duration, so it still
     * sizes the conversation and still times the endpoint — but the count no
     * longer matches the text that duration covered, so it cannot set a rate.
     */
    @Test
    fun `an edited turn counts everywhere except the speed`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 101, genTokens = 100, genMs = 1000L, ttftMs = 200L),
                assistant(
                    "a2",
                    prompt = 400,
                    completion = 51,
                    genTokens = 50,
                    genMs = 3000L,
                    chars = 99,
                ),
            ),
        )
        assertEquals(100.0, stats.tokensPerSecond!!, 0.001)
        assertEquals(2100L, stats.averageResponseMs)
        assertEquals(2, stats.statsTurns)
        assertEquals(451, stats.conversationTokens)
    }

    @Test
    fun `a fully covered conversation is not flagged as partial`() {
        val stats = ChatStats.of(listOf(assistant("a1", completion = 5, genMs = 100L)))
        assertFalse(stats.partialSample)
    }

    /** A reply that arrived in a single burst must not divide by zero. */
    @Test
    fun `a zero generation window is excluded from speed`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 10, genTokens = 9, genMs = 0L),
                assistant("a2", completion = 101, genTokens = 100, genMs = 1000L),
            ),
        )
        assertEquals(100.0, stats.tokensPerSecond!!, 0.001)
        assertEquals(2, stats.statsTurns)
    }

    @Test
    fun `timing without token counts still gives a response time`() {
        val stats = ChatStats.of(listOf(assistant("a1", genMs = 1000L, ttftMs = 100L)))
        assertNull(stats.tokensPerSecond)
        assertEquals(1100L, stats.averageResponseMs)
    }

    // --- stopped replies ----------------------------------------------------

    /** The user stopped it, so the duration says when they gave up, not how fast the server is. */
    @Test
    fun `a stopped reply is excluded from the response average`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 100, genTokens = 99, genMs = 4000L, ttftMs = 200L),
                assistant("a2", genMs = 300L, ttftMs = 200L, stopped = true),
            ),
        )
        assertEquals(4200L, stats.averageResponseMs)
        assertEquals(1, stats.statsTurns)
        assertEquals(2, stats.assistantMessages)
    }

    /** The server did answer; only the tail was cut short. */
    @Test
    fun `a stopped reply still contributes its time to first token`() {
        val stats = ChatStats.of(
            listOf(
                assistant("a1", completion = 10, genMs = 1000L, ttftMs = 100L),
                assistant("a2", genMs = 300L, ttftMs = 300L, stopped = true),
            ),
        )
        assertEquals(200L, stats.averageTtftMs)
    }

    @Test
    fun `a stopped reply never reaches the speed figure`() {
        val stats = ChatStats.of(
            listOf(assistant("a1", completion = 50, genTokens = 49, genMs = 500L, stopped = true)),
        )
        assertNull(stats.tokensPerSecond)
    }

    /**
     * An aborted stream carries no `usage`, so a stopped reply never counts the
     * history — it is simply text the last counted turn did not see.
     */
    @Test
    fun `a stopped reply leaves the size to the last turn that counted it`() {
        val stats = ChatStats.of(
            listOf(
                system(),
                assistant("a1", content = "ok", prompt = 400, completion = 60, promptChars = 0),
                assistant("a2", content = "half a rep", genMs = 500L, stopped = true),
            ),
        )
        assertEquals(460, stats.conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.AT_LEAST, stats.sizeAccuracy)
    }

    /** Nothing ever counted the history, so there is no figure to qualify. */
    @Test
    fun `a stopped reply alone does not invent a size`() {
        val stats = ChatStats.of(
            listOf(assistant("a1", genMs = 500L, stopped = true)),
        )
        assertNull(stats.conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.EXACT, stats.sizeAccuracy)
    }

    /** Stopping before the first token leaves only the flag. */
    @Test
    fun `a reply stopped before any token counts as a message only`() {
        val stats = ChatStats.of(listOf(user("u1"), assistant("a1", stopped = true)))
        assertEquals(2, stats.messages)
        assertEquals(0, stats.statsTurns)
        assertNull(stats.averageResponseMs)
        assertNull(stats.averageTtftMs)
    }

    // --- size accuracy ------------------------------------------------------

    @Test
    fun `size is exact when the newest reply counted the thread as it stands`() {
        val stats = ChatStats.of(
            listOf(
                system(),
                user("u1", "hello"),
                assistant("a1", content = "reply", prompt = 100, completion = 50, promptChars = 5),
            ),
        )
        assertEquals(150, stats.conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.EXACT, stats.sizeAccuracy)
    }

    /** An endpoint that reports no usage leaves the thread grown but uncounted. */
    @Test
    fun `size is a lower bound when the thread has grown since it was counted`() {
        val stats = ChatStats.of(
            listOf(
                system(),
                user("u1", "hello"),
                assistant("a1", content = "reply", prompt = 100, completion = 50, promptChars = 5),
                user("u2", "more"),
                assistant("a2", genMs = 300L),
            ),
        )
        assertEquals(150, stats.conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.AT_LEAST, stats.sizeAccuracy)
    }

    /**
     * Deleting a message shortens the thread without touching any stored count.
     * Nothing records the deletion, so the figure has to notice it by comparing
     * the thread it described against the thread that is left.
     */
    @Test
    fun `size is an upper bound once a message has been deleted`() {
        val stats = ChatStats.of(
            listOf(
                system(),
                assistant("a1", content = "ok", prompt = 100, completion = 50, promptChars = 40),
            ),
        )
        assertEquals(150, stats.conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.AT_MOST, stats.sizeAccuracy)
    }

    /** The system prompt is replayed on every request, so rewriting it moves the count. */
    @Test
    fun `rewriting the system prompt leaves the size qualified`() {
        val stats = ChatStats.of(
            listOf(
                system("you are a much longer system prompt than before"),
                assistant("a1", content = "ok", prompt = 100, completion = 50, promptChars = 4),
            ),
        )
        assertEquals(ChatStats.SizeAccuracy.AT_LEAST, stats.sizeAccuracy)
    }

    /** Editing a reply back to the length it was counted at is no change at all. */
    @Test
    fun `size is exact when edits cancel out`() {
        val stats = ChatStats.of(
            listOf(
                system(),
                user("u1", "hello"),
                assistant(
                    "a1",
                    content = "reply",
                    prompt = 100,
                    completion = 50,
                    chars = 4,
                    promptChars = 6,
                ),
            ),
        )
        assertEquals(ChatStats.SizeAccuracy.EXACT, stats.sizeAccuracy)
    }

    /**
     * Conversations from before the anchors existed fall back to the thread as
     * it stood at that turn: growth after it still shows, an edit inside it
     * cannot, and neither is claimed as more certain than it is.
     */
    @Test
    fun `a turn that recorded no thread length still notices later growth`() {
        val counted = listOf(
            system(),
            user("u1", "hello"),
            assistant("a1", content = "reply", prompt = 100, completion = 50),
        )
        assertEquals(150, ChatStats.of(counted).conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.EXACT, ChatStats.of(counted).sizeAccuracy)
        assertEquals(
            ChatStats.SizeAccuracy.AT_LEAST,
            ChatStats.of(counted + user("u2", "more")).sizeAccuracy,
        )
    }

    @Test
    fun `size is not qualified when no reply ever reported usage`() {
        val stats = ChatStats.of(
            listOf(system("a prompt"), assistant("a1", genMs = 300L, stopped = true)),
        )
        assertNull(stats.conversationTokens)
        assertEquals(ChatStats.SizeAccuracy.EXACT, stats.sizeAccuracy)
    }

    // --- empty cases --------------------------------------------------------

    /** Pre-feature chats report nothing rather than zeroes. */
    @Test
    fun `a conversation with no stats yields nulls`() {
        val stats = ChatStats.of(listOf(system(), user("u1"), assistant("a1")))
        assertNull(stats.conversationTokens)
        assertNull(stats.tokensPerSecond)
        assertNull(stats.averageResponseMs)
        assertNull(stats.averageTtftMs)
        assertEquals(0, stats.statsTurns)
        assertFalse(stats.partialSample)
    }

    @Test
    fun `an empty conversation is safe`() {
        val stats = ChatStats.of(emptyList())
        assertEquals(0, stats.messages)
        assertNull(stats.tokensPerSecond)
        assertFalse(stats.partialSample)
    }
}
