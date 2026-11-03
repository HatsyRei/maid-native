package com.hatsyrei.maidnative.domain.tools

import androidx.compose.runtime.Immutable
import com.hatsyrei.maidnative.data.optStringOrNull
import com.hatsyrei.maidnative.data.remote.Endpoints
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.zip.CRC32

/** A remote MCP server whose tools the model may call, reached over Streamable HTTP. */
@Immutable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    /** Sent as a bearer token. */
    val token: String = "",
    /** The ciphertext as stored, or empty once [token] has been replaced; see `EndpointPreset.storedKey`. */
    val storedToken: String = "",
    val tokenReadable: Boolean = true,
    val enabled: Boolean = true,
    /** The tools as last listed; requests offer these without asking the server again. */
    val tools: List<McpToolInfo> = emptyList(),
    /** Server-side names of the tools switched off, so a tool the server adds later starts on. */
    val disabledTools: Set<String> = emptySet(),
    /** Every tool runs without asking, including ones the server adds later. */
    val trusted: Boolean = false,
    /** Server-side names of the tools set to run without asking; the rest ask. */
    val trustedTools: Set<String> = emptySet(),
) {
    val prefix: String get() = Mcp.prefix(name)

    /** Plain HTTP, which "Allow HTTP endpoints" governs as it does the chat endpoint. */
    val cleartext: Boolean get() = Endpoints.isCleartext(url)

    fun policy(tool: String): ToolPolicy = when {
        tool in disabledTools -> ToolPolicy.OFF
        trusted || tool in trustedTools -> ToolPolicy.ALLOW
        else -> ToolPolicy.ASK
    }
}

/** One tool a server listed, with the annotations the Tools dialog tags. */
@Immutable
data class McpToolInfo(
    override val name: String,
    val title: String? = null,
    val description: String = "",
    /** The tool's `inputSchema`, as raw JSON. */
    val inputSchema: String = "{}",
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
    val openWorld: Boolean? = null,
) : ToolSwitch {
    override val label: String get() = title ?: name
    override val summary: String
        get() = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    // Only hints the server states: the spec's defaults for missing ones are the worst case, which would tag every tool.
    override val effects: Set<ToolEffect>
        get() = buildSet {
            if (openWorld == true) add(ToolEffect.NETWORK)
            if (readOnly == false || (readOnly == null && destructive == true)) add(ToolEffect.WRITES)
        }
}

/** An MCP error: a failed request, or a tool that reported one. [message] is what the model reads. */
class McpException(message: String, val reason: Reason? = null) : IOException(message) {
    /** Failures worded by this app, which the UI can show in the app language. */
    enum class Reason { NOT_JSON, NO_RESULT, STREAM_ENDED }
}

/** Runs `tools/call` on a server and returns the call's `result`. */
fun interface McpCaller {
    suspend fun call(server: McpServer, tool: String, arguments: JSONObject): JSONObject
}

object Mcp {
    private const val MAX_NAME = 64
    private const val MAX_PREFIX = 24
    private const val SEPARATOR = "__"
    private const val MAX_RESULT_CHARS = 50_000

    private val NON_SLUG = Regex("[^a-z0-9]+")
    private val INVALID_NAME = Regex("[^A-Za-z0-9_-]")

    /** The server's tool-name prefix: lowercase letters, digits and single underscores, starting with a letter. */
    fun prefix(serverName: String): String {
        val slug = serverName.trim().lowercase().replace(NON_SLUG, "_").trim('_').take(MAX_PREFIX).trimEnd('_')
        return when {
            slug.isEmpty() -> "mcp"
            slug[0].isLetter() -> slug
            else -> "mcp_$slug"
        }
    }

    /** What the model calls [tool] by: apart from built-ins and other servers, and within the 64 characters endpoints accept. */
    fun toolName(prefix: String, tool: String): String {
        val clean = tool.replace(INVALID_NAME, "_")
        val full = prefix + SEPARATOR + clean
        if (clean == tool && full.length <= MAX_NAME) return full
        // Rewritten names could meet, so a hash of the original keeps them apart.
        val hash = CRC32().apply { update(tool.toByteArray()) }.value.toString(36)
        return full.take(MAX_NAME - hash.length - 1) + "_" + hash
    }

    /** The tools [servers] offer: enabled servers, less the tools switched off. */
    fun tools(servers: List<McpServer>, allowCleartext: Boolean, caller: McpCaller): List<Tool> =
        offered(servers, allowCleartext)
            .map { (server, info) -> McpTool(server, info, caller) }
            .distinctBy { it.name }

    /** The offered tools that wait for the user, by the name the model calls them: server id to server-side name. */
    fun asking(servers: List<McpServer>, allowCleartext: Boolean): Map<String, Pair<String, String>> =
        offered(servers, allowCleartext)
            .filter { (server, info) -> server.policy(info.name) == ToolPolicy.ASK }
            .associate { (server, info) -> toolName(server.prefix, info.name) to (server.id to info.name) }

    private fun offered(servers: List<McpServer>, allowCleartext: Boolean): List<Pair<McpServer, McpToolInfo>> = servers
        // A token that can't be decrypted would turn into unauthenticated requests.
        .filter { it.enabled && it.tokenReadable && (allowCleartext || !it.cleartext) }
        .flatMap { server ->
            server.tools.filter { it.name !in server.disabledTools }.map { server to it }
        }

    /** [raw] as an object schema, which is what every endpoint expects of a function's parameters. */
    fun schema(raw: String): JSONObject {
        val schema = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
        schema.remove("\$schema")
        if (schema.opt("type") != "object") schema.put("type", "object")
        if (!schema.has("properties")) schema.put("properties", JSONObject())
        return schema
    }

    /** A `tools/call` result as the text the model reads; an error result throws, so it reaches the model as one. */
    fun resultText(result: JSONObject): String {
        val parts = ArrayList<String>()
        val content = result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            when (val type = item.optStringOrNull("type")) {
                "text" -> parts += item.optStringOrNull("text").orEmpty()
                "resource" -> item.optJSONObject("resource")?.let { resource ->
                    parts += resource.optStringOrNull("text") ?: "[Resource ${resource.optStringOrNull("uri").orEmpty()}, not text]"
                }
                "resource_link" -> parts += "[Resource link: ${item.optStringOrNull("uri").orEmpty()}]"
                else -> parts += "[${type ?: "Unknown"} content (${item.optStringOrNull("mimeType") ?: "no type"}) not shown]"
            }
        }
        var text = parts.joinToString("\n")
        if (text.isBlank()) result.optJSONObject("structuredContent")?.let { text = it.toString() }
        if (result.optBoolean("isError")) throw McpException(text.ifBlank { "The tool reported an error" })
        if (text.length > MAX_RESULT_CHARS) {
            text = text.take(MAX_RESULT_CHARS) + "\n[Cut at $MAX_RESULT_CHARS of ${text.length} characters]"
        }
        return text.ifEmpty { "(no output)" }
    }

    /** One entry of a `tools/list` result, which is also how tools are stored. */
    fun parseTool(json: JSONObject): McpToolInfo? {
        val name = json.optStringOrNull("name") ?: return null
        val annotations = json.optJSONObject("annotations")
        return McpToolInfo(
            name = name,
            title = (json.optStringOrNull("title") ?: annotations?.optStringOrNull("title"))?.ifBlank { null },
            description = json.optStringOrNull("description").orEmpty(),
            inputSchema = json.optJSONObject("inputSchema")?.toString() ?: "{}",
            readOnly = annotations?.opt("readOnlyHint") as? Boolean,
            destructive = annotations?.opt("destructiveHint") as? Boolean,
            openWorld = annotations?.opt("openWorldHint") as? Boolean,
        )
    }

    fun encodeTools(tools: List<McpToolInfo>): JSONArray = JSONArray().apply {
        for (tool in tools) {
            val annotations = JSONObject().apply {
                tool.readOnly?.let { put("readOnlyHint", it) }
                tool.destructive?.let { put("destructiveHint", it) }
                tool.openWorld?.let { put("openWorldHint", it) }
            }
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("inputSchema", runCatching { JSONObject(tool.inputSchema) }.getOrDefault(JSONObject()))
                    .apply {
                        tool.title?.let { put("title", it) }
                        if (annotations.length() > 0) put("annotations", annotations)
                    },
            )
        }
    }

    fun decodeTools(array: JSONArray?): List<McpToolInfo> =
        (0 until (array?.length() ?: 0)).mapNotNull { i -> array?.optJSONObject(i)?.let(::parseTool) }
}

private class McpTool(
    private val server: McpServer,
    private val info: McpToolInfo,
    private val caller: McpCaller,
) : Tool {
    override val name = Mcp.toolName(server.prefix, info.name)
    override val label = info.label
    override val summary = info.summary
    override val effects = info.effects
    override val description = info.description.ifBlank { info.label }

    override fun parameters(): JSONObject = Mcp.schema(info.inputSchema)

    override suspend fun invoke(arguments: JSONObject): String =
        Mcp.resultText(caller.call(server, info.name, arguments))
}
