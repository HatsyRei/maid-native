package com.hatsyrei.maidnative.domain.tools

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FetchUrlTest {

    private fun response(body: String, type: String?, code: Int = 200) = Response.Builder()
        .request(Request.Builder().url("http://192.168.1.2:8080/page").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Not Found")
        .body(body.toResponseBody(type?.toMediaType()))
        .build()

    private fun read(body: String, type: String?) = JSONObject(FetchUrl.read(response(body, type)) { "[$it]" })

    @Test
    fun `plain text comes back as is`() {
        val result = read("line one\nline two", "text/plain; charset=utf-8")
        assertEquals("line one\nline two", result.getString("content"))
        assertEquals("http://192.168.1.2:8080/page", result.getString("url"))
        assertFalse(result.has("truncated"))
    }

    @Test
    fun `html is stripped of hidden parts before conversion`() {
        val html = "<html><head><title>Hi &amp; bye</title><style>p{}</style></head>" +
            "<body><!-- note --><script>x()</script><p>Text</p><SCRIPT type=\"a\">y</SCRIPT></body></html>"
        val result = read(html, "text/html")
        assertEquals("[Hi &amp; bye]", result.getString("title"))
        assertEquals("[<html><body><p>Text</p></body></html>]", result.getString("content"))
    }

    @Test
    fun `header tags survive the head filter`() {
        assertEquals("<header>Top</header>", FetchUrl.stripHidden("<header>Top</header>"))
    }

    @Test
    fun `long content is truncated`() {
        val result = read("a".repeat(30_000), "application/json")
        assertEquals(20_000, result.getString("content").length)
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun `binary content and error statuses are refused`() {
        assertThrows(IllegalArgumentException::class.java) { FetchUrl.read(response("x", "image/png")) { it } }
        assertThrows(IOException::class.java) { FetchUrl.read(response("x", "text/plain", 404)) { it } }
    }

    @Test
    fun `whitespace is tidied`() {
        assertEquals("a b\n\nc", FetchUrl.tidy("  a \u00A0 b \n\n\n\n c  "))
    }

    @Test
    fun `non http urls come back as errors`() = runBlocking {
        for (args in listOf("{}", """{"url":"file:///etc/hosts"}""", """{"url":"ftp://x"}""")) {
            val result = JSONObject(Tools.run(ToolCall("1", "fetch_url", args), listOf(FetchUrl)))
            assertTrue(args, result.has("error"))
        }
    }
}
