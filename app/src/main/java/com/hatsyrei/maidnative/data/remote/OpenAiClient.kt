package com.hatsyrei.maidnative.data.remote

import android.util.Base64
import com.hatsyrei.maidnative.data.store.attachments
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.domain.Modalities
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.Support
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
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
        // OkHttp follows an https -> http redirect by default. It strips the
        // Authorization header on the scheme change, but the request BODY —
        // the conversation and any attached images — would still go out in the
        // clear, and the allow-HTTP setting cannot catch it because that gate
        // applies to the configured base URL, not to a redirect target. No
        // real OpenAI-compatible endpoint downgrades mid-request.
        .followSslRedirects(false)
        .build()

    // Non-streaming requests (the models list) reuse the streaming client's
    // connection pool + dispatcher (cheap via newBuilder, which also inherits
    // the redirect policy above) but add finite read and overall timeouts.
    // Without these the models GET would inherit readTimeout(0) and a half-open
    // connection could hang the request — and keep the socket/radio awake —
    // indefinitely.
    private val client = streamClient.newBuilder()
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    // Base URLs whose models list identified a server that honours
    // `chat_template_kwargs`. Filled in by [listModels].
    private val thinkingControl: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class Config(
        val baseURL: String,
        val apiKey: String,
        val model: String,
        /** Requested thinking mode, forced on the server rather than left to the chat template. */
        val reasoning: Boolean = true,
    )

    data class ModelInfo(val id: String, val modalities: Modalities)

    /** GET {base}/models -> the models the endpoint offers. Throws on failure. */
    fun listModels(config: Config): List<ModelInfo> {
        val base = normalize(config.baseURL)
        val request = Request.Builder()
            .url("$base/models")
            .applyAuth(config.apiKey)
            .get()
            .build()
        val entries = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("models request failed: HTTP ${response.code}")
            }
            val body = response.body.string()
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        }

        // This call doubles as capability detection. `owned_by` names the
        // server implementation, and only some accept the thinking
        // extension; deciding here rather than from a rejected completion
        // means the first turn is already correct. The ordering holds
        // because ChatUiState.ready requires this call to have succeeded for
        // the active endpoint before anything can be sent.
        if (entries.any { it.optString("owned_by") in THINKING_CONTROL_VENDORS }) {
            thinkingControl.add(base)
        } else {
            thinkingControl.remove(base)
        }

        val models = entries.mapNotNull { entry ->
            val id = entry.optString("id").ifEmpty { return@mapNotNull null }
            ModelInfo(id, entry.architectureModalities())
        }
        // A llama.cpp router describes every model it knows here, loaded or
        // not. Anything else leaves `architecture` out entirely, so fall back
        // to the server-wide /props — the only place a single-model llama.cpp
        // states its modalities.
        if (models.isNotEmpty() && models.all { it.modalities == Modalities.UNKNOWN }) {
            val props = fetchProps(base, config.apiKey)
            if (props != null) return models.map { it.copy(modalities = props) }
        }
        return models
    }

    /**
     * `architecture.input_modalities` is the router's per-model answer, and it
     * is authoritative in both directions: a model listed without "image" is
     * text-only, not merely unreported.
     */
    private fun JSONObject.architectureModalities(): Modalities {
        val list = optJSONObject("architecture")?.optJSONArray("input_modalities")
            ?: return Modalities.UNKNOWN
        val names = (0 until list.length()).map { list.optString(it) }
        return Modalities(
            vision = names.supportFor("image"),
            audio = names.supportFor("audio"),
        )
    }

    /**
     * `/props` hangs off the server root, not `/v1`, and a router answers it
     * with no `modalities` key at all (it has no one model to describe).
     * Failure is silent by design: this is optional enrichment, a strict
     * OpenAI endpoint simply 404s, and modalities then stay UNKNOWN.
     */
    private fun fetchProps(base: String, apiKey: String): Modalities? {
        val request = Request.Builder()
            .url("${base.removeSuffix("/v1")}/props")
            .applyAuth(apiKey)
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val modalities = JSONObject(response.body.string())
                    .optJSONObject("modalities") ?: return@use null
                Modalities(
                    vision = modalities.supportFor("vision"),
                    audio = modalities.supportFor("audio"),
                )
            }
        }.getOrNull()
    }

    private fun List<String>.supportFor(name: String): Support =
        if (contains(name)) Support.YES else Support.NO

    private fun JSONObject.supportFor(key: String): Support =
        if (optBoolean(key)) Support.YES else Support.NO

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
        val base = normalize(config.baseURL)
        val payload = buildPayload(config, messages, parameters, base in thinkingControl)
        val request = Request.Builder()
            .url("$base/chat/completions")
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
        config: Config,
        messages: List<MessageNode>,
        parameters: Map<String, Any?>,
        thinkingControl: Boolean,
    ): JSONObject {
        // Strip the reasoning trace from assistant turns. It is stored inline in
        // the message so a single content field still round-trips through the
        // tree and the database, but replaying it as assistant *content* burns
        // context and invites the model to continue its own old thought.
        val history = messages.map { m ->
            Turn(
                role = m.role,
                text = if (m.role == "assistant") Reasoning.split(m.content).first.orEmpty() else m.content,
                attachments = if (m.role == "user") m.attachments() else emptyList(),
            )
        }.toMutableList()

        // Drop trailing empty assistant placeholder(s): sending
        // {"role":"assistant","content":""} makes llama.cpp-style backends treat
        // it as an assistant prefix to continue. A turn that was stopped while
        // still thinking is empty only after the strip above, so this runs after.
        while (history.isNotEmpty() &&
            history.last().role == "assistant" &&
            history.last().text.isBlank()
        ) {
            history.removeAt(history.size - 1)
        }

        val msgArray = JSONArray()
        for (turn in history) {
            msgArray.put(
                JSONObject()
                    .put("role", turn.role)
                    .put("content", contentFor(turn)),
            )
        }

        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", msgArray)
            .put("stream", true)
        // `chat_template_kwargs` is the llama.cpp / vLLM spelling, and is sent
        // in both directions on purpose: a server started with reasoning off (or
        // a models.ini entry that disables it) only turns thinking back on if
        // the request says so. It is omitted entirely for endpoints that did not
        // identify themselves as supporting it, since a strict OpenAI-compatible
        // server rejects the whole request over one unknown argument.
        if (thinkingControl) {
            payload.put(
                "chat_template_kwargs",
                JSONObject().put("enable_thinking", config.reasoning),
            )
        }
        for ((key, value) in parameters) {
            payload.put(key, value ?: JSONObject.NULL)
        }
        return payload
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        val key = apiKey.ifEmpty { "local-openai-compatible" }
        return header("Authorization", "Bearer $key")
    }

    private class Turn(val role: String, val text: String, val attachments: List<Attachment>)

    /**
     * Plain string content unless the turn carries attachments, in which case
     * it becomes an OpenAI content-part array. Attachments lead and the typed
     * text follows, matching the ordering every vision chat template expects.
     *
     * Bytes are read and base64-encoded here, at request time, so the encoded
     * copy lives only for the length of the call instead of sitting in the
     * message tree. An attachment whose file has gone missing is skipped rather
     * than failing the send.
     */
    private fun contentFor(turn: Turn): Any {
        if (turn.attachments.isEmpty()) return turn.text
        val parts = JSONArray()
        for (attachment in turn.attachments) {
            val bytes = runCatching { File(attachment.path).readBytes() }.getOrNull() ?: continue
            when (attachment.kind) {
                Attachment.Kind.IMAGE -> parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:${attachment.mime};base64,${base64(bytes)}"),
                        ),
                )

                Attachment.Kind.AUDIO -> parts.put(
                    JSONObject()
                        .put("type", "input_audio")
                        .put(
                            "input_audio",
                            JSONObject()
                                .put("data", base64(bytes))
                                .put("format", audioFormat(attachment.mime)),
                        ),
                )

                // No modality is involved: a text file is just prompt text, so
                // it is inlined with a header naming it.
                Attachment.Kind.TEXT -> parts.put(
                    textPart("File: ${attachment.name}\n\n${bytes.toString(Charsets.UTF_8)}"),
                )
            }
        }
        if (parts.length() == 0) return turn.text
        if (turn.text.isNotBlank()) parts.put(textPart(turn.text))
        return parts
    }

    private fun textPart(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** llama.cpp accepts only these two spellings; anything else is sent as wav. */
    private fun audioFormat(mime: String): String =
        if (mime == "audio/mpeg" || mime == "audio/mp3") "mp3" else "wav"

    /**
     * `optString` returns the literal "null" for a JSON null value (e.g. the
     * opening `{"role":"assistant","content":null}` chunk), so guard on `isNull`.
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun normalize(baseURL: String): String = baseURL.trimEnd('/')

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // `owned_by` values of servers that honour
        // `chat_template_kwargs.enable_thinking`. Anything else is treated as a
        // strict OpenAI-compatible endpoint, where the argument would 400.
        private val THINKING_CONTROL_VENDORS = setOf("llamacpp", "vllm")
    }
}
