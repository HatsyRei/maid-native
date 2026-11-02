package com.hatsyrei.maidnative.domain.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTest {

    private val dice = McpToolInfo(
        name = "roll_d20",
        description = "Roll a 20-sided die.\nReturns 1 to 20.",
        inputSchema = """{"${'$'}schema":"x","properties":{},"title":"roll_d20Arguments","type":"object"}""",
    )

    private fun server(name: String = "Dice", vararg tools: McpToolInfo = arrayOf(dice)) =
        McpServer(id = name, name = name, url = "http://127.0.0.1:8000/", tools = tools.toList())

    @Test
    fun `prefix is a lowercase slug that starts with a letter`() {
        assertEquals("home_assistant", Mcp.prefix("  Home Assistant! "))
        assertEquals("a_b", Mcp.prefix("a__b"))
        assertEquals("mcp_2nd", Mcp.prefix("2nd"))
        assertEquals("mcp", Mcp.prefix("日本"))
        assertTrue(Mcp.prefix("x".repeat(100)).length <= 24)
    }

    @Test
    fun `tool names are prefixed and kept valid and unique`() {
        assertEquals("dice__roll_d20", Mcp.toolName("dice", "roll_d20"))
        val dotted = Mcp.toolName("s", "a.b")
        val underscored = Mcp.toolName("s", "a_b")
        assertNotEquals(dotted, underscored)
        assertTrue(dotted.matches(Regex("[A-Za-z0-9_-]+")))
        val long = Mcp.toolName("server", "t".repeat(80))
        assertEquals(64, long.length)
        assertNotEquals(long, Mcp.toolName("server", "t".repeat(81)))
    }

    @Test
    fun `only enabled servers and tools are offered`() {
        val weather = McpToolInfo(name = "get_weather")
        val on = server("Dice", dice, weather).copy(disabledTools = setOf("get_weather"))
        val off = server("Other").copy(enabled = false)
        val locked = server("Locked").copy(tokenReadable = false)
        val names = Mcp.tools(listOf(on, off, locked), allowCleartext = true) { _, _, _ -> JSONObject() }.map { it.name }
        assertEquals(listOf("dice__roll_d20"), names)
    }

    @Test
    fun `plain HTTP servers are offered only when HTTP is allowed, loopback included`() {
        val local = server("Local")
        val remote = server("Remote").copy(url = "https://mcp.example.com/mcp")
        val none = McpCaller { _, _, _ -> JSONObject() }
        assertEquals(listOf("remote__roll_d20"), Mcp.tools(listOf(local, remote), allowCleartext = false, none).map { it.name })
        assertEquals(
            listOf("local__roll_d20", "remote__roll_d20"),
            Mcp.tools(listOf(local, remote), allowCleartext = true, none).map { it.name },
        )
        assertEquals(setOf("remote__roll_d20"), Mcp.asking(listOf(local, remote), allowCleartext = false).keys)
    }

    @Test
    fun `a call goes to the server under the tool's own name`() = runBlocking {
        var called: Pair<String, String>? = null
        val tool = Mcp.tools(listOf(server()), allowCleartext = true) { s, name, args ->
            called = s.id to name + args
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "17")))
        }.single()
        val result = Tools.run(ToolCall("c1", tool.name, """{"x":1}"""), listOf(tool))
        assertEquals("17", result)
        assertEquals("Dice" to """roll_d20{"x":1}""", called)
        assertEquals("Roll a 20-sided die.", tool.summary)
    }

    @Test
    fun `tools ask unless trusted one by one or by server`() {
        val weather = McpToolInfo(name = "get_weather")
        val off = McpToolInfo(name = "off")
        val server = server("Dice", dice, weather, off).copy(trustedTools = setOf("get_weather"), disabledTools = setOf("off"))
        assertEquals(ToolPolicy.ASK, server.policy("roll_d20"))
        assertEquals(ToolPolicy.ALLOW, server.policy("get_weather"))
        assertEquals(ToolPolicy.OFF, server.policy("off"))
        assertEquals(mapOf("dice__roll_d20" to ("Dice" to "roll_d20")), Mcp.asking(listOf(server), allowCleartext = true))

        val trusted = server.copy(trusted = true)
        assertEquals(ToolPolicy.ALLOW, trusted.policy("roll_d20"))
        assertEquals(ToolPolicy.OFF, trusted.policy("off"))
        assertTrue(Mcp.asking(listOf(trusted), allowCleartext = true).isEmpty())
        assertTrue(Mcp.asking(listOf(server.copy(enabled = false)), allowCleartext = true).isEmpty())
    }

    @Test
    fun `schema is an object without the schema keyword`() {
        val schema = Mcp.schema(dice.inputSchema)
        assertTrue(!schema.has("\$schema"))
        assertEquals("object", schema.getString("type"))
        val fallback = Mcp.schema("not json")
        assertEquals("object", fallback.getString("type"))
        assertEquals(0, fallback.getJSONObject("properties").length())
    }

    @Test
    fun `result content becomes text`() {
        val result = JSONObject()
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "one"))
                    .put(JSONObject().put("type", "image").put("data", "...").put("mimeType", "image/png"))
                    .put(JSONObject().put("type", "resource").put("resource", JSONObject().put("uri", "u").put("text", "two"))),
            )
            .put("structuredContent", JSONObject().put("result", 1))
        assertEquals("one\n[image content (image/png) not shown]\ntwo", Mcp.resultText(result))
    }

    @Test
    fun `structured content stands in for missing text`() {
        val result = JSONObject().put("content", JSONArray()).put("structuredContent", JSONObject().put("result", 7))
        assertEquals("""{"result":7}""", Mcp.resultText(result))
    }

    @Test
    fun `an error result reaches the model as an error`(): Unit = runBlocking {
        val tool = Mcp.tools(listOf(server()), allowCleartext = true) { _, _, _ ->
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Unknown tool: nope")))
                .put("isError", true)
        }.single()
        val result = JSONObject(Tools.run(ToolCall("c1", tool.name, "{}"), listOf(tool)))
        assertEquals("Unknown tool: nope", result.getString("error"))
        assertThrows(McpException::class.java) { Mcp.resultText(JSONObject().put("isError", true)) }
    }

    @Test
    fun `tools round trip through storage`() {
        val tools = listOf(
            dice,
            McpToolInfo(name = "w", title = "Write", readOnly = false, openWorld = true, destructive = false),
        )
        val decoded = Mcp.decodeTools(JSONArray(Mcp.encodeTools(tools).toString()))
        assertEquals(tools.map { it.copy(inputSchema = JSONObject(it.inputSchema).toString()) }, decoded)
    }

    @Test
    fun `effects follow the hints the server states`() {
        assertEquals(emptySet<ToolEffect>(), McpToolInfo(name = "a").effects)
        assertEquals(setOf(ToolEffect.NETWORK), McpToolInfo(name = "a", openWorld = true, readOnly = true).effects)
        assertEquals(setOf(ToolEffect.WRITES), McpToolInfo(name = "a", readOnly = false).effects)
        assertEquals(setOf(ToolEffect.WRITES), McpToolInfo(name = "a", destructive = true).effects)
    }

    @Test
    fun `annotation title is used when the tool has none`() {
        val tool = Mcp.parseTool(
            JSONObject().put("name", "x").put("annotations", JSONObject().put("title", "Nice name").put("readOnlyHint", true)),
        )!!
        assertEquals("Nice name", tool.label)
        assertEquals(true, tool.readOnly)
    }
}
