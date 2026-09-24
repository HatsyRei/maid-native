package com.hatsyrei.maidnative.domain.tools

import com.hatsyrei.maidnative.domain.tools.ToolText.Segment
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ToolsTest {

    private fun call(id: String) = ToolCall(id, "get_datetime", "{}", "{\"datetime\":\"$id\"}")

    private val calls: ToolCalls = linkedMapOf("1" to call("a"), "2" to call("b"))

    @Test
    fun `calls round trip through metadata in order`() {
        val metadata = ToolCallStore.writeInto(calls, mapOf("title" to "t"))
        val node = MessageNode("a", "assistant", "x", "r", metadata = metadata)
        assertEquals(calls.toList(), node.toolCalls().toList())
        assertEquals("t", metadata["title"])
    }

    @Test
    fun `no calls leaves no key behind`() {
        val metadata = ToolCallStore.writeInto(calls, emptyMap())
        assertTrue(ToolCallStore.writeInto(emptyMap(), metadata).isEmpty())
    }

    @Test
    fun `malformed calls decode to nothing`() {
        assertTrue(ToolCallStore.decode("not json").isEmpty())
        assertTrue(ToolCallStore.decode("[{\"key\":\"1\"}]").isEmpty())
    }

    @Test
    fun `text and call runs parse in order`() {
        val text = "Before.\n\n{{tool:1}}\n\nBetween.\n\n{{tool:2}}\n\nAfter."
        assertEquals(
            listOf(
                Segment.Text("Before."),
                Segment.Calls(listOf(call("a"))),
                Segment.Text("Between."),
                Segment.Calls(listOf(call("b"))),
                Segment.Text("After."),
            ),
            ToolText.parse(text, calls),
        )
    }

    @Test
    fun `adjacent markers are one run`() {
        assertEquals(
            listOf(Segment.Calls(listOf(call("a"), call("b"))), Segment.Text("Done.")),
            ToolText.parse("{{tool:1}}\n\n{{tool:2}}\nDone.", calls),
        )
    }

    @Test
    fun `unknown, inline and repeated markers stay text`() {
        val text = "{{tool:9}}\nsay {{tool:1}} inline\n{{tool:2}}\n{{tool:2}}"
        assertEquals(
            listOf(
                Segment.Text("{{tool:9}}\nsay {{tool:1}} inline"),
                Segment.Calls(listOf(call("b"))),
                Segment.Text("{{tool:2}}"),
            ),
            ToolText.parse(text, calls),
        )
    }

    @Test
    fun `tail starts after the last marker`() {
        val text = "Hi.\n\n{{tool:1}}\n{{tool:2}}\n\nStreaming"
        assertEquals("\nStreaming", text.substring(ToolText.tailStart(text, calls)))
        assertEquals(0, ToolText.tailStart("plain", emptyMap()))
    }

    @Test
    fun `retain drops calls whose markers are gone`() {
        assertEquals(setOf("2"), ToolText.retain(calls, "x\n{{tool:2}}\ny").keys)
    }

    @Test
    fun `strip removes marker lines and their gap`() {
        assertEquals("Before.\n\nAfter.", ToolText.strip("Before.\n\n{{tool:1}}\n\nAfter.", setOf("1")))
    }

    @Test
    fun `datetime carries local offset, weekday and zone`() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 24, 14, 5, 9)
        }
        val result = JSONObject(GetDatetime.format(calendar))
        assertEquals("2026-09-24T14:05:09+05:30", result.getString("datetime"))
        assertEquals("Thursday", result.getString("weekday"))
        assertEquals("Asia/Kolkata", result.getString("timezone"))
        assertFalse(GetDatetime.format(calendar).contains("\\/"))
    }

    @Test
    fun `negative offsets keep their sign`() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT-03:30")).apply {
            clear()
            set(2026, Calendar.JANUARY, 1, 0, 0, 0)
        }
        assertTrue(JSONObject(GetDatetime.format(calendar)).getString("datetime").endsWith("-03:30"))
    }

    @Test
    fun `calls to unknown or disabled tools come back as errors`() = runBlocking {
        val result = Tools.run(ToolCall("1", "get_datetime", "{}"), available = emptyList())
        assertTrue(JSONObject(result).getString("error").contains("get_datetime"))
    }

    @Test
    fun `malformed arguments come back as an error`() = runBlocking {
        val result = Tools.run(ToolCall("1", "get_datetime", "{oops"), listOf(GetDatetime))
        assertTrue(JSONObject(result).has("error"))
    }

    @Test
    fun `get_datetime runs`() = runBlocking {
        val result = Tools.run(ToolCall("1", "get_datetime", ""), listOf(GetDatetime))
        assertTrue(JSONObject(result).has("datetime"))
    }
}
