package com.hatsyrei.maidnative.ui.chat

import com.hatsyrei.maidnative.domain.TurnStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnFootnoteTest {

    /** The body as it was when the count was taken, so nothing is qualified. */
    private val body = "a".repeat(1600)

    @Test
    fun `reports what the turn measured`() {
        val footnote = formatTurnFootnote(
            TurnStats(
                promptTokens = 900,
                completionTokens = 401,
                genMs = 4000L,
                genTokens = 400,
                chars = 1600,
            ),
            body,
        )
        assertEquals("401 tokens · 4.0 s · 100 tok/s", footnote)
    }

    /** Pre-feature messages render nothing at all rather than a row of N/A. */
    @Test
    fun `an unmeasured turn has no footnote`() {
        assertNull(formatTurnFootnote(TurnStats(), body))
    }

    /** No usage arrives for an aborted stream, so only our own clock is left. */
    @Test
    fun `a stopped reply is labelled so its duration is not read as slowness`() {
        assertEquals(
            "2.0 s · stopped",
            formatTurnFootnote(TurnStats(genMs = 2000L, ttftMs = 300L, stopped = true), ""),
        )
    }

    /** An endpoint that reports no usage still leaves a timing behind. */
    @Test
    fun `timing alone still renders`() {
        assertEquals("1.5 s", formatTurnFootnote(TurnStats(genMs = 1500L), body))
    }

    @Test
    fun `token counts are grouped`() {
        val footnote = formatTurnFootnote(
            TurnStats(completionTokens = 12345, genMs = 10_000L, genTokens = 12340),
            body,
        )
        assertEquals("12,345 tokens · 10.0 s · 1234 tok/s", footnote)
    }

    /**
     * A reply edited longer than the text that was counted can only have cost
     * more, and its duration no longer covers what is on screen, so no rate.
     */
    @Test
    fun `a lengthened reply reports a floor and drops the rate`() {
        val footnote = formatTurnFootnote(
            TurnStats(completionTokens = 401, genMs = 4000L, genTokens = 400, chars = 1600),
            body + "more",
        )
        assertEquals("≥ 401 tokens · 4.0 s", footnote)
    }

    @Test
    fun `a shortened reply reports a ceiling`() {
        val footnote = formatTurnFootnote(
            TurnStats(completionTokens = 401, genMs = 4000L, genTokens = 400, chars = 1600),
            body.dropLast(1),
        )
        assertEquals("≤ 401 tokens · 4.0 s", footnote)
    }
}
