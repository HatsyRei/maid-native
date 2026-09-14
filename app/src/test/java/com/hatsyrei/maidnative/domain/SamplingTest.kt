package com.hatsyrei.maidnative.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {

    @Test
    fun `nothing overridden sends nothing`() {
        assertTrue(Sampling().isEmpty)
        assertTrue(Sampling().parameters().isEmpty())
    }

    @Test
    fun `only overridden fields reach the request`() {
        val sampling = Sampling().with(SamplingParam.TEMPERATURE, 0.7f)
        assertEquals(mapOf("temperature" to 0.7), sampling.parameters())
    }

    @Test
    fun `top k stays an integer on the wire`() {
        val sampling = Sampling().with(SamplingParam.TOP_K, 39.6f)
        assertEquals(40, sampling.topK)
        assertEquals(mapOf("top_k" to 40), sampling.parameters())
    }

    @Test
    fun `values are rounded and clamped to the field's range`() {
        assertEquals(0.13f, Sampling().with(SamplingParam.MIN_P, 0.1284f).minP)
        assertEquals(2f, Sampling().with(SamplingParam.TEMPERATURE, 9f).temperature)
        assertEquals(-2f, Sampling().with(SamplingParam.PRESENCE_PENALTY, -8f).presencePenalty)
    }

    @Test
    fun `a null value hands the field back to the endpoint`() {
        val sampling = Sampling().with(SamplingParam.TOP_P, 0.9f).with(SamplingParam.TOP_P, null)
        assertNull(sampling.topP)
        assertTrue(sampling.isEmpty)
    }

    @Test
    fun `overrides counts only the fields that were set`() {
        val sampling = Sampling()
            .with(SamplingParam.TEMPERATURE, 0.7f)
            .with(SamplingParam.TOP_K, 20f)
        assertEquals(2, sampling.overrides)
    }

    @Test
    fun `round trips through the stored form`() {
        val sampling = Sampling()
            .with(SamplingParam.TEMPERATURE, 0.65f)
            .with(SamplingParam.TOP_K, 20f)
            .with(SamplingParam.PRESENCE_PENALTY, -1.5f)
        assertEquals(sampling, Sampling.decode(sampling.encode()))
    }

    @Test
    fun `an empty override set encodes to nothing`() {
        assertEquals("", Sampling().encode())
        assertEquals(Sampling(), Sampling.decode(""))
        assertEquals(Sampling(), Sampling.decode(null))
    }

    @Test
    fun `unknown or malformed stored entries are dropped`() {
        val decoded = Sampling.decode("temperature=0.5;mirostat=2;top_p=;=0.4;rubbish")
        assertEquals(Sampling(temperature = 0.5f), decoded)
    }

    @Test
    fun `formatting is locale-fixed and matches the field's precision`() {
        assertEquals("0.80", SamplingParam.TEMPERATURE.format(0.8f))
        assertEquals("40", SamplingParam.TOP_K.format(40f))
        assertEquals("-1.50", SamplingParam.PRESENCE_PENALTY.format(-1.5f))
    }
}
