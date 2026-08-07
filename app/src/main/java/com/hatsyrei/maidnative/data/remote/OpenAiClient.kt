package com.hatsyrei.maidnative.data.remote

import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible client (models + streaming chat completions). Replaces the
 * `openai` npm SDK over `expo/fetch`.
 */
class OpenAiClient {

    // Streaming completions: the server pushes tokens over a long-lived socket,
    // so there is deliberately NO read timeout (a slow / "thinking" model must
    // not be cut off mid-generation). Connect is still bounded so an
    // unreachable host fails fast instead of holding the radio awake.
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    // Non-streaming requests (the models list) reuse the streaming client's
    // connection pool + dispatcher (cheap via newBuilder) but add finite read
    // and overall timeouts. Without these the models GET would inherit
    // readTimeout(0) and a half-open connection could hang the request — and
    // keep the socket/radio awake — indefinitely.
    private val client = streamClient.newBuilder()
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    data class Config(val baseURL: String, val apiKey: String, val model: String)

    /** GET {base}/models -> list of model ids. Throws on failure. */
    fun listModels(config: Config): List<String> {
        val request = Request.Builder()
            .url(normalize(config.baseURL) + "/models")
            .applyAuth(config.apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("models request failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.optString("id")?.ifEmpty { null }
            }
        }
    }

    /**
     * Streams an assistant reply as classified [Reasoning.Chunk]s. Reasoning is
     * separated here, at the transport boundary, rather than by re-splitting the
     * accumulated text downstream: backends disagree about how they deliver it
     * (a dedicated delta field, or inline tags) and only this layer sees the
     * difference. The flow completes on `[DONE]` and cancels the underlying
     * request when the collector is cancelled (mirrors AbortController).
     */
    fun streamChat(
        config: Config,
        messages: List<MessageNode>,
        parameters: Map<String, Any?> = emptyMap(),
    ): Flow<Reasoning.Chunk> = callbackFlow {
        val payload = buildPayload(config.model, messages, parameters)
        val request = Request.Builder()
            .url(normalize(config.baseURL) + "/chat/completions")
            .applyAuth(config.apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val listener = object : EventSourceListener() {
            // Only the inline-tag path needs scanning; a backend that fills the
            // dedicated field has already done this work for us.
            private val scanner = Reasoning.Scanner()

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    scanner.finish { trySend(it) }
                    close()
                    return
                }
                val deltaObj = runCatching {
                    JSONObject(data)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                }.getOrNull() ?: return

                // `reasoning_content` is the DeepSeek / vLLM / llama.cpp
                // spelling, `reasoning` is OpenRouter's. Without this, models
                // that stream their trace out-of-band showed no reasoning at all.
                val reasoning = deltaObj.stringOrNull("reasoning_content")
                    ?: deltaObj.stringOrNull("reasoning")
                if (reasoning != null) trySend(Reasoning.Chunk.Thought(reasoning))

                val content = deltaObj.stringOrNull("content")
                if (content != null) scanner.feed(content) { trySend(it) }
            }

            override fun onClosed(eventSource: EventSource) {
                scanner.finish { trySend(it) }
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val code = response?.code
                close(t ?: IOException("stream failed${if (code != null) " (HTTP $code)" else ""}"))
            }
        }

        val eventSource = EventSources.createFactory(streamClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildPayload(
        model: String,
        messages: List<MessageNode>,
        parameters: Map<String, Any?>,
    ): JSONObject {
        // Strip the reasoning trace from assistant turns. It is stored inline in
        // the message so a single content field still round-trips through the
        // tree and the database, but replaying it as assistant *content* burns
        // context and invites the model to continue its own old thought.
        val history = messages.map { m ->
            if (m.role == "assistant") m.role to Reasoning.split(m.content).first.orEmpty()
            else m.role to m.content
        }.toMutableList()

        // Drop trailing empty assistant placeholder(s): sending
        // {"role":"assistant","content":""} makes llama.cpp-style backends treat
        // it as an assistant prefix to continue. A turn that was stopped while
        // still thinking is empty only after the strip above, so this runs after.
        while (history.isNotEmpty() &&
            history.last().first == "assistant" &&
            history.last().second.isBlank()
        ) {
            history.removeAt(history.size - 1)
        }

        val msgArray = JSONArray()
        for ((role, content) in history) {
            msgArray.put(
                JSONObject()
                    .put("role", role)
                    .put("content", content),
            )
        }

        val payload = JSONObject()
            .put("model", model)
            .put("messages", msgArray)
            .put("stream", true)
        for ((key, value) in parameters) {
            payload.put(key, value ?: JSONObject.NULL)
        }
        return payload
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        val key = apiKey.ifEmpty { "local-openai-compatible" }
        return header("Authorization", "Bearer $key")
    }

    /**
     * `optString` returns the literal "null" for a JSON null value (e.g. the
     * opening `{"role":"assistant","content":null}` chunk), so guard on `isNull`.
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun normalize(baseURL: String): String = baseURL.trimEnd('/')

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
