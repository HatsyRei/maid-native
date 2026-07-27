package com.hatsyrei.maidnative.data.remote

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming: no read timeout
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
     * Streams assistant content deltas for a chat completion. The flow emits
     * each content chunk; it completes on `[DONE]` and cancels the underlying
     * request when the collector is cancelled (mirrors AbortController).
     */
    fun streamChat(
        config: Config,
        messages: List<MessageNode>,
        parameters: Map<String, Any?> = emptyMap(),
    ): Flow<String> = callbackFlow {
        val payload = buildPayload(config.model, messages, parameters)
        val request = Request.Builder()
            .url(normalize(config.baseURL) + "/chat/completions")
            .applyAuth(config.apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                val delta = runCatching {
                    val deltaObj = JSONObject(data)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                    // optString returns the literal "null" for a JSON null value
                    // (e.g. the opening {"role":"assistant","content":null} chunk),
                    // so guard on isNull before reading.
                    if (deltaObj == null || deltaObj.isNull("content")) null
                    else deltaObj.optString("content").ifEmpty { null }
                }.getOrNull()
                if (delta != null) trySend(delta)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val code = response?.code
                close(t ?: IOException("stream failed${if (code != null) " (HTTP $code)" else ""}"))
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildPayload(
        model: String,
        messages: List<MessageNode>,
        parameters: Map<String, Any?>,
    ): JSONObject {
        // Drop trailing empty assistant placeholder(s): sending {"role":"assistant","content":""}
        // makes llama.cpp-style backends treat it as an assistant prefix to continue.
        val trimmed = messages.toMutableList()
        while (trimmed.isNotEmpty() &&
            trimmed.last().role == "assistant" &&
            trimmed.last().content.trim().isEmpty()
        ) {
            trimmed.removeAt(trimmed.size - 1)
        }

        val msgArray = JSONArray()
        for (m in trimmed) {
            msgArray.put(
                JSONObject()
                    .put("role", m.role)
                    .put("content", m.content),
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

    private fun normalize(baseURL: String): String = baseURL.trimEnd('/')

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
