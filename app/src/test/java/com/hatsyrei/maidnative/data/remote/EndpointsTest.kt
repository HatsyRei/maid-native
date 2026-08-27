package com.hatsyrei.maidnative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointsTest {

    @Test
    fun `origin ignores path and normalises the default port`() {
        assertEquals("https://api.openai.com:443", Endpoints.origin("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com:443", Endpoints.origin("https://api.openai.com/props"))
        assertEquals("http://192.168.1.5:8080", Endpoints.origin("http://192.168.1.5:8080/v1"))
    }

    @Test
    fun `a different host or port is a different origin`() {
        val bound = Endpoints.origin("https://api.openai.com/v1")
        assertTrue(bound != Endpoints.origin("http://192.168.1.5:8080/v1"))
        assertTrue(bound != Endpoints.origin("https://api.openai.com.evil.test/v1"))
        assertTrue(
            Endpoints.origin("http://192.168.1.5:8080/v1") !=
                Endpoints.origin("http://192.168.1.5:9931/v1"),
        )
    }

    @Test
    fun `unusable urls have no origin`() {
        assertNull(Endpoints.origin("192.168.1.5:8080"))
        assertNull(Endpoints.origin(""))
        assertNull(Endpoints.origin("ftp://example.test/v1"))
    }

    @Test
    fun `cleartext is only plain http`() {
        assertTrue(Endpoints.isCleartext("http://192.168.1.5:8080/v1"))
        assertFalse(Endpoints.isCleartext("https://api.openai.com/v1"))
        // Nothing can be sent to a url that will not parse, so it is not a risk.
        assertFalse(Endpoints.isCleartext("nonsense"))
    }
}
