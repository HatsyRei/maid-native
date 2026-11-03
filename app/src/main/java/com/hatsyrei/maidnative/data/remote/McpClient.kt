package com.hatsyrei.maidnative.data.remote

import com.hatsyrei.maidnative.data.optStringOrNull
import com.hatsyrei.maidnative.domain.tools.Mcp
import com.hatsyrei.maidnative.domain.tools.McpCaller
import com.hatsyrei.maidnative.domain.tools.McpException
import com.hatsyrei.maidnative.domain.tools.McpServer
import com.hatsyrei.maidnative.domain.tools.McpToolInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP over Streamable HTTP: each JSON-RPC request is a POST, answered with
 * JSON or with an SSE stream that carries the response. Only tools are used.
 */
class McpClient : McpCaller {

    private val http = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        // As for chat endpoints: an https -> http redirect would send the arguments in the clear.
        .followSslRedirects(false)
        .build()

    private class Session(val id: String?, val version: String)

    private class Reply(val result: JSONObject?, val sessionId: String?)

    internal class SessionExpired : IOException("Session expired")

    // By URL and token, for the life of the process; a server that dropped one answers 404 and gets a new one.
    private val sessions = ConcurrentHashMap<String, Session>()
    private val opening = ConcurrentHashMap<String, Mutex>()
    private val nextId = AtomicLong()

    suspend fun listTools(url: String, token: String): List<McpToolInfo> {
        val tools = ArrayList<McpToolInfo>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = request(url, token, "tools/list", params)
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until page.length()) page.optJSONObject(i)?.let(Mcp::parseTool)?.let(tools::add)
            cursor = result.optStringOrNull("nextCursor") ?: return tools
        }
        return tools
    }

    override suspend fun call(server: McpServer, tool: String, arguments: JSONObject): JSONObject =
        request(server.url, server.token, "tools/call", JSONObject().put("name", tool).put("arguments", arguments))

    private suspend fun request(url: String, token: String, method: String, params: JSONObject): JSONObject {
        val key = "$url\n$token"
        val session = session(key, url, token)
        val reply = try {
            post(url, token, session, message(method, params))
        } catch (e: SessionExpired) {
            sessions.remove(key, session)
            post(url, token, session(key, url, token), message(method, params))
        }
        return checkNotNull(reply.result)
    }

    private suspend fun session(key: String, url: String, token: String): Session {
        sessions[key]?.let { return it }
        return opening.getOrPut(key) { Mutex() }.withLock {
            sessions[key] ?: open(url, token).also { sessions[key] = it }
        }
    }

    private suspend fun open(url: String, token: String): Session {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION))
        val reply = post(url, token, null, message("initialize", params))
        val version = reply.result?.optStringOrNull("protocolVersion") ?: PROTOCOL_VERSION
        val session = Session(reply.sessionId, version)
        post(url, token, session, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        return session
    }

    private fun message(method: String, params: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", nextId.incrementAndGet())
        .put("method", method)
        .put("params", params)

    private suspend fun post(url: String, token: String, session: Session?, message: JSONObject): Reply {
        val request = Request.Builder()
            .url(url)
            .post(message.toString().toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                session?.id?.let { header("Mcp-Session-Id", it) }
                session?.let { header("MCP-Protocol-Version", it.version) }
            }
            .build()
        val id = message.opt("id")?.toString()
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

                override fun onResponse(call: Call, response: Response) = cont.resumeWith(
                    runCatching {
                        response.use { Reply(read(it, id, session?.id != null), it.header("Mcp-Session-Id")) }
                    },
                )
            })
        }
    }

    /** The `result` answering request [id], or null for a notification, which has no answer. */
    internal fun read(response: Response, id: String?, inSession: Boolean): JSONObject? {
        if (response.code == 404 && inSession) throw SessionExpired()
        if (!response.isSuccessful) throw McpException(failure(response))
        if (id == null) return null
        val body = response.body
        val message = if (body.contentType()?.subtype == "event-stream") {
            answer(body.source(), id)
        } else {
            runCatching { JSONObject(body.string()) }.getOrNull()
                ?: throw McpException("The server's response is not JSON", McpException.Reason.NOT_JSON)
        }
        message.optJSONObject("error")?.let { throw McpException(errorText(it)) }
        return message.optJSONObject("result")
            ?: throw McpException("The server's response has no result", McpException.Reason.NO_RESULT)
    }

    /** Reads SSE events up to the response to [id]; progress, logs and server requests on the way are skipped. */
    internal fun answer(source: BufferedSource, id: String): JSONObject {
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line()
            if (line.isNullOrEmpty()) {
                if (data.isNotEmpty()) {
                    val message = runCatching { JSONObject(data.toString()) }.getOrNull()
                    data.setLength(0)
                    if (message != null && message.opt("id")?.toString() == id &&
                        (message.has("result") || message.has("error"))
                    ) {
                        return message
                    }
                }
                if (line == null) {
                    throw McpException("The server ended the stream without a response", McpException.Reason.STREAM_ENDED)
                }
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }

    private fun failure(response: Response): String {
        // Servers often say why in a JSON-RPC error body.
        val detail = runCatching {
            JSONObject(response.peekBody(4096).string()).optJSONObject("error")?.let(::errorText)
        }.getOrNull()
        return listOfNotNull("HTTP ${response.code} ${response.message}".trim(), detail).joinToString(": ")
    }

    private fun errorText(error: JSONObject): String =
        error.optStringOrNull("message") ?: "Error ${error.opt("code")}"

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "maid-native"
        const val CLIENT_VERSION = "1"
        const val MAX_PAGES = 20
        val JSON = "application/json".toMediaType()
    }
}
