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
}
