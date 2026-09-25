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

    private fun read(body: String, type: String?, start: Int = 0, maxChars: Int = FetchUrl.DEFAULT_CHARS) =
        JSONObject(FetchUrl.read(response(body, type), start, maxChars) { "[$it]" })

    @Test
    fun `plain text comes back as is`() {
        val result = read("line one\nline two", "text/plain; charset=utf-8")
        assertEquals("line one\nline two", result.getString("content"))
        assertEquals("http://192.168.1.2:8080/page", result.getString("url"))
        assertEquals(17, result.getInt("total_chars"))
        assertFalse(result.has("download_capped"))
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
    fun `long content is paged by start and max_chars`() {
        val body = "a".repeat(10_000) + "b".repeat(20_000)
        val first = read(body, "application/json")
        assertEquals("a".repeat(FetchUrl.DEFAULT_CHARS), first.getString("content"))
        assertEquals(30_000, first.getInt("total_chars"))

        val middle = read(body, "application/json", start = 9_998, maxChars = 4)
        assertEquals("aabb", middle.getString("content"))
        assertEquals(9_998, middle.getInt("start"))

        assertEquals("b".repeat(5), read(body, "application/json", start = 29_995).getString("content"))
        val past = read(body, "application/json", start = 40_000)
        assertEquals("", past.getString("content"))
        assertEquals(30_000, past.getInt("start"))
    }

    @Test
    fun `binary content and error statuses are refused`() {
        assertThrows(IllegalArgumentException::class.java) { FetchUrl.read(response("x", "image/png"), 0, 10) { it } }
        assertThrows(IOException::class.java) { FetchUrl.read(response("x", "text/plain", 404), 0, 10) { it } }
    }

    @Test
    fun `whitespace is tidied`() {
        assertEquals("a b\n\nc", FetchUrl.tidy("  a \u00A0 b \n\n\n\n c  "))
    }

    @Test
    fun `non http urls and bad ranges come back as errors`() = runBlocking {
        for (args in listOf(
            "{}", """{"url":"file:///etc/hosts"}""", """{"url":"ftp://x"}""",
            """{"url":"http://x","start":-1}""", """{"url":"http://x","max_chars":0}""",
        )) {
            val result = JSONObject(Tools.run(ToolCall("1", "fetch_url", args), listOf(FetchUrl)))
            assertTrue(args, result.has("error"))
        }
    }
}
