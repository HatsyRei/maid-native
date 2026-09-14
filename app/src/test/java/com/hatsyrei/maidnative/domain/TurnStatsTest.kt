package com.hatsyrei.maidnative.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStatsTest {

    @Test
    fun `round trips through metadata`() {
        val stats = TurnStats(
            promptTokens = 1234,
            completionTokens = 567,
            genMs = 8900L,
            genTokens = 566,
            ttftMs = 210L,
            chars = 2048,
            promptChars = 4096,
        )
        assertEquals(stats, TurnStats.from(stats.writeInto(emptyMap())))
    }

    @Test
    fun `writing preserves unrelated metadata`() {
        val before = mapOf("title" to "Steampunk D&D", "attachments" to "[]")
        val after = TurnStats(promptTokens = 10).writeInto(before)
        assertEquals("Steampunk D&D", after["title"])
        assertEquals("[]", after["attachments"])
    }

    /** JSON round-trips an Int as an Int or a Long by magnitude; both must read. */
    @Test
    fun `reads numbers back regardless of boxed type`() {
        val stats = TurnStats.from(
            mapOf(
                "promptTokens" to 42L,
                "completionTokens" to 7,
                "genMs" to 900,
                "genTokens" to 6L,
                "ttftMs" to 12L,
                "chars" to 30L,
                "promptChars" to 90,
            ),
        )
        assertEquals(42, stats.promptTokens)
        assertEquals(7, stats.completionTokens)
        assertEquals(900L, stats.genMs)
        assertEquals(6, stats.genTokens)
        assertEquals(12L, stats.ttftMs)
        assertEquals(30, stats.chars)
        assertEquals(90, stats.promptChars)
    }

    @Test
    fun `absent counts stay null rather than zero`() {
        val stats = TurnStats.from(mapOf("title" to "x"))
        assertTrue(stats.isEmpty)
        assertNull(stats.promptTokens)
        assertNull(stats.completionTokens)
        assertNull(stats.totalTokens)
    }

    @Test
    fun `empty stats write nothing`() {
        val before = mapOf<String, Any?>("title" to "x")
        assertSame(before, TurnStats().writeInto(before))
    }

    @Test
    fun `total counts prompt plus completion`() {
        assertEquals(1801, TurnStats(promptTokens = 1234, completionTokens = 567).totalTokens)
        assertEquals(567, TurnStats(completionTokens = 567).totalTokens)
    }

    @Test
    fun `partial usage is not empty`() {
        assertFalse(TurnStats(completionTokens = 1).isEmpty)
    }

    /** A stop with no measurements is still worth recording, so it must survive the write. */
    @Test
    fun `the stopped flag round trips and is not treated as empty`() {
        val stats = TurnStats(stopped = true)
        assertFalse(stats.isEmpty)
        val written = stats.writeInto(emptyMap())
        assertEquals(true, written["stopped"])
        assertTrue(TurnStats.from(written).stopped)
    }

    @Test
    fun `an unstopped record writes no stopped key`() {
        val written = TurnStats(completionTokens = 5).writeInto(emptyMap())
        assertFalse(written.containsKey("stopped"))
        assertFalse(TurnStats.from(written).stopped)
    }

    /**
     * An endpoint that reports no usage would otherwise carry a dead anchor on
     * every message it ever produced.
     */
    @Test
    fun `an anchor is not stored without the count it qualifies`() {
        val written = TurnStats(genMs = 500L, chars = 20, promptChars = 400).writeInto(emptyMap())
        assertFalse(written.containsKey("chars"))
        assertFalse(written.containsKey("promptChars"))
    }

    @Test
    fun `each anchor is stored once its own count exists`() {
        val completion = TurnStats(completionTokens = 5, chars = 20, promptChars = 400)
            .writeInto(emptyMap())
        assertEquals(20, completion["chars"])
        assertFalse(completion.containsKey("promptChars"))

        val prompt = TurnStats(promptTokens = 100, chars = 20, promptChars = 400)
            .writeInto(emptyMap())
        assertEquals(400, prompt["promptChars"])
        assertFalse(prompt.containsKey("chars"))
    }

    // --- rate ---------------------------------------------------------------

    @Test
    fun `rate divides the timed tokens by the window that produced them`() {
        assertEquals(
            40.0,
            TurnStats(completionTokens = 21, genTokens = 20, genMs = 500L).tokensPerSecond("ok")!!,
            0.001,
        )
    }

    /**
     * A server-timed window covers the whole reply where ours covers only the
     * gaps between tokens. The recorded count says which, so the rate never has
     * to correct for one or the other.
     */
    @Test
    fun `rate trusts the recorded token count over the completion count`() {
        assertEquals(
            42.0,
            TurnStats(completionTokens = 21, genTokens = 21, genMs = 500L).tokensPerSecond("ok")!!,
            0.001,
        )
    }

    @Test
    fun `a window that produced nothing has no rate`() {
        assertNull(TurnStats(completionTokens = 1, genTokens = 0, genMs = 5L).tokensPerSecond("ok"))
    }

    @Test
    fun `rate is null unless both halves are known`() {
        assertNull(TurnStats(completionTokens = 21, genTokens = 20).tokensPerSecond("ok"))
        assertNull(TurnStats(genMs = 500L).tokensPerSecond("ok"))
        assertNull(TurnStats(completionTokens = 21, genMs = 500L).tokensPerSecond("ok"))
        assertNull(TurnStats().tokensPerSecond("ok"))
    }

    /** Its duration says when the user gave up, not how fast the endpoint was. */
    @Test
    fun `a stopped reply has no rate`() {
        assertNull(TurnStats(genMs = 500L, stopped = true).tokensPerSecond("ok"))
        assertNull(
            TurnStats(completionTokens = 21, genTokens = 20, genMs = 500L, stopped = true)
                .tokensPerSecond("ok"),
        )
    }

    @Test
    fun `a zero window does not divide by zero`() {
        assertNull(
            TurnStats(completionTokens = 21, genTokens = 20, genMs = 0L).tokensPerSecond("ok"),
        )
    }

    /**
     * Metadata also arrives from imported conversation files, which are
     * user-supplied. A negative count would otherwise yield a negative rate.
     */
    @Test
    fun `rejects negative values from untrusted metadata`() {
        val stats = TurnStats.from(
            mapOf(
                "promptTokens" to -5,
                "completionTokens" to -21,
                "genMs" to -500L,
                "genTokens" to -20,
                "ttftMs" to -1L,
                "chars" to -9,
                "promptChars" to -9,
            ),
        )
        assertEquals(TurnStats(), stats)
        assertNull(stats.tokensPerSecond("ok"))
        assertNull(stats.totalTokens)
        assertNull(stats.countedChars)
    }

    // --- bounds -------------------------------------------------------------

    @Test
    fun `a body matching what was counted is exact`() {
        assertEquals(
            TurnStats.Bound.EXACT,
            TurnStats(completionTokens = 100, chars = 2).bound("ok"),
        )
    }

    /** A longer body cannot cost fewer tokens, and a shorter one cannot cost more. */
    @Test
    fun `an edit bounds the count by which way the body moved`() {
        val stats = TurnStats(completionTokens = 100, chars = 10)
        assertEquals(TurnStats.Bound.AT_LEAST, stats.bound("a".repeat(11)))
        assertEquals(TurnStats.Bound.AT_MOST, stats.bound("a".repeat(9)))
        assertEquals(TurnStats.Bound.AT_MOST, stats.bound(""))
    }

    /** Nothing to compare against means nothing to qualify. */
    @Test
    fun `a record with no anchor or no count is exact`() {
        assertEquals(TurnStats.Bound.EXACT, TurnStats(completionTokens = 100).bound("anything"))
        assertEquals(TurnStats.Bound.EXACT, TurnStats(chars = 10).bound("anything"))
        assertEquals(TurnStats.Bound.EXACT, TurnStats().bound("anything"))
    }

    /**
     * The duration timed the text that was counted, so dividing it by a body
     * that has since been rewritten would name a rate nothing ever ran at.
     */
    @Test
    fun `an edited body yields no rate`() {
        val stats = TurnStats(completionTokens = 101, genTokens = 100, genMs = 1000L, chars = 10)
        assertEquals(100.0, stats.tokensPerSecond("a".repeat(10))!!, 0.001)
        assertNull(stats.tokensPerSecond("a".repeat(11)))
        assertNull(stats.tokensPerSecond("a".repeat(9)))
    }

    // --- the thread that was counted ----------------------------------------

    /** The prompt as it was read, plus the body that answered it. */
    @Test
    fun `counted chars span the whole thread the figure described`() {
        assertEquals(
            1600,
            TurnStats(promptTokens = 1, completionTokens = 1, promptChars = 1500, chars = 100)
                .countedChars,
        )
    }

    /** A conversation from before the anchors existed must claim no bound at all. */
    @Test
    fun `counted chars are unknown unless both halves were recorded`() {
        assertNull(TurnStats(promptChars = 1500).countedChars)
        assertNull(TurnStats(chars = 100).countedChars)
        assertNull(TurnStats().countedChars)
    }
}
