package com.hatsyrei.maidnative.data.remote

import com.hatsyrei.maidnative.domain.tools.GetDatetime
import com.hatsyrei.maidnative.domain.tools.ToolCall
import com.hatsyrei.maidnative.domain.tools.ToolCallStore
import com.hatsyrei.maidnative.domain.tools.ToolCalls
import com.hatsyrei.maidnative.domain.tree.MessageNode
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolRequestTest {

    private val client = OpenAiClient()
    private val config = OpenAiClient.Config("http://localhost:8080/v1", "", "m")

    private val calls: ToolCalls = mapOf(
        "1" to ToolCall("call_1", "get_datetime", "{}", "{\"datetime\":\"2026\"}"),
    )

    private fun request(
        messages: List<MessageNode>,
        pendingText: String = "",
        pendingCalls: ToolCalls = emptyMap(),
        tools: Boolean = true,
    ): JSONObject {
        val body = client.buildBody(
            config,
            messages,
            if (tools) listOf(GetDatetime) else emptyList(),
            pendingText,
            pendingCalls,
            emptyMap(),
            thinkingControl = false,
        )
        return JSONObject(Buffer().also(body::writeTo).readUtf8())
    }

    private fun roles(json: JSONObject): List<String> {
        val messages = json.getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
    }

    @Test
    fun `enabled tools are declared as functions`() {
        val json = request(listOf(MessageNode("u", "user", "time?", "r")))
        val function = json.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertEquals("get_datetime", function.getString("name"))
        assertEquals("object", function.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun `no tools means no tools field`() {
        val json = request(listOf(MessageNode("u", "user", "hi", "r")), tools = false)
        assertFalse(json.has("tools"))
    }

    @Test
    fun `the reply in flight follows the thread, placeholder dropped`() {
        val json = request(
            listOf(
                MessageNode("u", "user", "time?", "r"),
                MessageNode("a", "assistant", "", "r", parent = "u"),
            ),
            pendingText = "Checking.\n\n{{tool:1}}\n\n",
            pendingCalls = calls,
        )
        assertEquals(listOf("user", "assistant", "tool"), roles(json))
        val messages = json.getJSONArray("messages")
        assertEquals("Checking.", messages.getJSONObject(1).getString("content"))
        val call = messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("get_datetime", call.getJSONObject("function").getString("name"))
        assertEquals("call_1", messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("{\"datetime\":\"2026\"}", messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `a call's extra_content is replayed verbatim`() {
        val signed = mapOf("1" to calls.getValue("1").copy(extra = "{\"google\":{\"thought_signature\":\"sig\"}}"))
        val json = request(
            listOf(MessageNode("u", "user", "time?", "r")),
            pendingText = "{{tool:1}}\n\n",
            pendingCalls = signed,
        )
        val call = json.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("sig", call.getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
    }

    @Test
    fun `a call without extra sends no extra_content`() {
        val json = request(listOf(MessageNode("u", "user", "time?", "r")), "{{tool:1}}\n\n", calls)
        val call = json.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)
        assertFalse(call.has("extra_content"))
    }

    @Test
    fun `extra_content stays off calls before the last user message`() {
        val signed = mapOf("1" to calls.getValue("1").copy(extra = "{\"google\":{\"thought_signature\":\"sig\"}}"))
        val answer = MessageNode(
            "a", "assistant", "{{tool:1}}\n\nIt is 2026.", "r",
            parent = "u",
            metadata = ToolCallStore.writeInto(signed, emptyMap()),
        )
        val json = request(
            listOf(MessageNode("u", "user", "time?", "r"), answer, MessageNode("u2", "user", "thanks", "r")),
        )
        assertFalse(json.toString().contains("extra_content"))
    }

    @Test
    fun `markers split a stored reply into turns, never sent as text`() {
        val answer = MessageNode(
            "a", "assistant", "<think>\nhm\n</think>\n\nLet me see.\n\n{{tool:1}}\n\nIt is 2026.", "r",
            parent = "u",
            metadata = ToolCallStore.writeInto(calls, emptyMap()),
        )
        val json = request(
            listOf(MessageNode("u", "user", "time?", "r"), answer, MessageNode("u2", "user", "thanks", "r")),
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant", "user"), roles(json))
        val messages = json.getJSONArray("messages")
        assertEquals("Let me see.", messages.getJSONObject(1).getString("content"))
        assertEquals("It is 2026.", messages.getJSONObject(3).getString("content"))
        assertFalse(json.toString().contains("{{tool:"))
    }
}
