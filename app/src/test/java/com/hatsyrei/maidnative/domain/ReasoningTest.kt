package com.hatsyrei.maidnative.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningTest {

    @Test
    fun noTags_returnsContentOnly() {
        val (content, reasoning) = Reasoning.split("just an answer")
        assertEquals("just an answer", content)
        assertNull(reasoning)
    }

    @Test
    fun thinkBlock_splitsBoth() {
        val (content, reasoning) = Reasoning.split("<think>weighing options</think>The answer is 42")
        assertEquals("The answer is 42", content)
        assertEquals("weighing options", reasoning)
    }

    @Test
    fun openWithoutClose_streaming_reasoningOnly() {
        val (content, reasoning) = Reasoning.split("<think>still thinking")
        assertNull(content)
        assertEquals("still thinking", reasoning)
    }

    @Test
    fun reasoningTag_supported() {
        val (content, reasoning) = Reasoning.split("<reasoning>because</reasoning> done")
        assertEquals("done", content)
        assertEquals("because", reasoning)
    }

    @Test
    fun thoughtTag_supported() {
        val (content, reasoning) = Reasoning.split("<thought>hmm</thought>\n\nThe answer is 42")
        assertEquals("The answer is 42", content)
        assertEquals("hmm", reasoning)
    }

    @Test
    fun orphanCloseTag_treatsPrefixAsReasoning() {
        val (content, reasoning) = Reasoning.split("template opened it</think>\n\nvisible")
        assertEquals("visible", content)
        assertEquals("template opened it", reasoning)
    }

    @Test
    fun multipleBlocks_areConcatenated() {
        val (content, reasoning) = Reasoning.split("<think>one</think>a<think>two</think>b")
        assertEquals("ab", content)
        assertEquals("one\n\ntwo", reasoning)
    }

    @Test
    fun partialTag_isHiddenWhileStreaming() {
        val (content, reasoning) = Reasoning.split("<think>done</think>answer <thi")
        assertEquals("answer", content)
        assertEquals("done", reasoning)
    }

    @Test
    fun unrelatedAngleBracket_isPreserved() {
        val (content, reasoning) = Reasoning.split("use <html> or a < b")
        assertEquals("use <html> or a < b", content)
        assertNull(reasoning)
    }

    /** The pipe shape is applied to every stem, not just `think`. */
    @Test
    fun pipeDelimitedThought_supported() {
        val (content, reasoning) = Reasoning.split("<|thought|>hmm</|thought|>answer")
        assertEquals("answer", content)
        assertEquals("hmm", reasoning)
    }

    @Test
    fun namespacedTag_supported() {
        val (content, reasoning) = Reasoning.split("<seed:think|>hmm</seed:think|>answer")
        assertEquals("answer", content)
        assertEquals("hmm", reasoning)
    }
}
