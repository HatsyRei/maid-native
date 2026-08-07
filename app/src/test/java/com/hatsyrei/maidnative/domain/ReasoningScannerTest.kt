package com.hatsyrei.maidnative.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningScannerTest {

    /** Mirrors how `ChatViewModel` folds the chunk stream into its two buffers. */
    private class Sink {
        private val reply = StringBuilder()
        private val thought = StringBuilder()
        val emit: (Reasoning.Chunk) -> Unit = { chunk ->
            when (chunk) {
                is Reasoning.Chunk.Reply -> reply.append(chunk.text)
                is Reasoning.Chunk.Thought -> thought.append(chunk.text)
                Reasoning.Chunk.Reclassify -> {
                    thought.append(reply)
                    reply.setLength(0)
                }
            }
        }

        fun reply(): String = reply.toString()

        fun thought(): String = thought.toString()
    }

    private fun scan(vararg deltas: String, finish: Boolean = true): Sink {
        val sink = Sink()
        val scanner = Reasoning.Scanner()
        for (delta in deltas) scanner.feed(delta, sink.emit)
        if (finish) scanner.finish(sink.emit)
        return sink
    }

    @Test
    fun plainText_isEmittedImmediately() {
        val sink = scan("Hello", finish = false)
        assertEquals("Hello", sink.reply())
        assertEquals("", sink.thought())
    }

    @Test
    fun tagsSplitAcrossDeltas_areReassembled() {
        val sink = scan("<thi", "nk>weighing", " options</thi", "nk>The answer")
        assertEquals("The answer", sink.reply())
        assertEquals("weighing options", sink.thought())
    }

    @Test
    fun partialTag_isHeldBackThenFlushedOnFinish() {
        val streaming = scan("done <thi", finish = false)
        assertEquals("done ", streaming.reply())

        val finished = scan("done <thi")
        assertEquals("done <thi", finished.reply())
    }

    @Test
    fun orphanClose_reclassifiesWhatWasAlreadyEmitted() {
        val sink = scan("template opened it", "</think>", "visible")
        assertEquals("visible", sink.reply())
        assertEquals("template opened it", sink.thought())
    }

    @Test
    fun strayCloseAfterCompleteBlock_isDropped() {
        val sink = scan("<think>a</think>b</think>c")
        assertEquals("bc", sink.reply())
        assertEquals("a", sink.thought())
    }

    @Test
    fun pipeDelimitedTag_isSupported() {
        val sink = scan("<|think|>x</|think|>y")
        assertEquals("y", sink.reply())
        assertEquals("x", sink.thought())
    }

    /** The ':' in a namespaced tag must survive the partial-tag holdback. */
    @Test
    fun namespacedTagSplitAcrossDeltas_isReassembled() {
        val sink = scan("<seed", ":think|>x</seed:th", "ink|>y")
        assertEquals("y", sink.reply())
        assertEquals("x", sink.thought())
    }

    @Test
    fun unrelatedAngleBracket_isNotHeldBack() {
        val sink = scan("a < b", finish = false)
        assertEquals("a < b", sink.reply())
    }

    @Test
    fun multipleBlocks_bothLandInTheTrace() {
        val sink = scan("<think>one</think>a", "<think>two</think>b")
        assertEquals("ab", sink.reply())
        assertEquals("onetwo", sink.thought())
    }
}
