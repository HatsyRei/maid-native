package com.hatsyrei.maidnative.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterModelsTest {

    private val models = listOf(
        "Qwen3-30B-A3B-Instruct-Q4_K_M",
        "Qwen3-30B-A3B-Instruct-Q8_0",
        "gpt-4o-mini",
    )

    @Test
    fun `blank query keeps every model`() {
        assertEquals(models, filterModels(models, "  "))
    }

    @Test
    fun `all terms must match, in any order and case`() {
        assertEquals(listOf("Qwen3-30B-A3B-Instruct-Q8_0"), filterModels(models, "q8 QWEN"))
    }

    @Test
    fun `no match is empty`() {
        assertEquals(emptyList<String>(), filterModels(models, "llama"))
    }
}
