package com.hatsyrei.maidnative.ui.chat

import android.os.SystemClock
import com.hatsyrei.maidnative.data.remote.OpenAiClient
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.TurnStats
import com.hatsyrei.maidnative.domain.tools.Tool
import com.hatsyrei.maidnative.domain.tools.ToolCall
import com.hatsyrei.maidnative.domain.tools.ToolCalls
import com.hatsyrei.maidnative.domain.tools.ToolText
import com.hatsyrei.maidnative.domain.tools.Tools
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One publish tick. A null field did not change since the last tick, so the
 * caller keeps the value it already holds rather than taking an identical copy.
 */
internal class StreamUpdate(val content: String?, val reasoning: String?, val calls: ToolCalls?)

/**
 * Runs one reply: streams a completion, runs any tools it calls and streams
 * again until the model answers, publishing to the caller at a bounded rate,
 * and rejoins reply and trace once at the end.
 */
internal class StreamController(
    private val scope: CoroutineScope,
    private val client: OpenAiClient,
) {
    // Appending to a StringBuilder is amortised O(1); accumulating with
    // `text + chunk` reallocated and copied the whole reply per token, which is
    // quadratic over a response and was the largest single source of CPU work
    // and GC churn during generation.
    //
    // The trace is kept in its own buffer for the same reason: classifying it
    // once, as it arrives, avoids re-scanning the accumulated text every tick.
    private val content = StringBuilder()
    private val reasoning = StringBuilder()

    // Lengths at the last publish, so an idle tick can skip emitting an
    // identical update (and the O(n) `toString` behind it), and so a tick that
    // only grew one of the two does not copy the other.
    private var publishedContent = 0
    private var publishedReasoning = 0

    // Every call of this reply by marker key, including those still running.
    // Their markers sit in [content], so the reply text says where each one ran.
    private val calls = LinkedHashMap<String, ToolCall>()
    private var callsVersion = 0
    private var publishedCallsVersion = 0
    private var rounds = 0

    // Where the request in flight started writing into [content].
    private var roundStart = 0

    // Tool calls of the request in flight, by the index the endpoint gave them.
    private val deltas = sortedMapOf<Int, PendingCall>()

    // Set once a round ends, so the next round's trace starts on a new paragraph.
    private var reasoningBreak = false

    // Whatever the server reported, plus the two durations we time ourselves.
    // `elapsedRealtime` rather than `currentTimeMillis`: it is monotonic, so an
    // NTP correction or the user changing the clock mid-reply cannot produce a
    // negative duration.
    private var usage = TurnStats()
    private var promptChars = 0
    private var startedAt = 0L
    private var firstTokenAt = 0L
    private var lastTokenAt = 0L
    private var stopped = false

    private var job: Job? = null

    // Chunks are accumulated on an IO thread while the main thread publishes and
    // commits, so every buffer access holds this.
    private val lock = Any()

    // Bumped by every reset and cancel, so a chunk a superseded collector was
    // already holding cannot land in the buffers of the stream that follows.
    private var generation = 0

    fun start(
        config: OpenAiClient.Config,
        conversation: List<MessageNode>,
        tools: List<Tool>,
        onUpdate: (StreamUpdate) -> Unit,
        onError: (Throwable) -> Unit,
        onFinish: () -> Unit,
    ) {
        reset()
        val gen = synchronized(lock) {
            // The thread as the endpoint is about to read it, which is what the
            // prompt count it returns will describe.
            promptChars = conversation.sumOf { it.content.length }
            generation
        }
        job = scope.launch {
            // Publishing is demand-driven, never timer-driven: the pump sleeps on
            // `pending` and is woken only by an arriving token. A free-running
            // ticker would keep waking the main thread ~30x/second for as long as
            // the request was open, which on a stalled stream is pure drain. An
            // idle TCP socket, by contrast, costs essentially nothing.
            val pending = Channel<Unit>(Channel.CONFLATED)

            val pump = launch {
                pending.consumeEach {
                    publish()?.let(onUpdate)
                    // Trailing throttle. Tokens arriving inside this window
                    // collapse into the single conflated signal that follows it,
                    // so the UI updates at most once per interval while the first
                    // token of a burst still lands immediately.
                    delay(PUBLISH_INTERVAL_MS)
                }
            }

            var failure: Throwable? = null
            while (true) {
                val (offered, pendingText, pendingCalls) = synchronized(lock) {
                    // The stats describe the request that produced the answer.
                    startedAt = SystemClock.elapsedRealtime()
                    firstTokenAt = 0L
                    lastTokenAt = 0L
                    roundStart = content.length
                    // Past the cap the model is offered no tools, which leaves it
                    // nothing to do but answer.
                    Triple(if (rounds < MAX_TOOL_ROUNDS) tools else emptyList(), content.toString(), calls.toMap())
                }

                // Collected off the main thread, so a token wakes it only through
                // the throttled pump rather than once per chunk. IO also covers
                // building the request, which reads and base64-encodes every
                // attachment.
                failure = withContext(Dispatchers.IO) {
                    var caught: Throwable? = null
                    client.streamChat(config, conversation, offered, pendingText, pendingCalls)
                        // UNLIMITED rather than the default 64-slot buffer: the SSE
                        // listener publishes with `trySend`, which silently drops a
                        // chunk when the channel is full. Losing a token corrupts the
                        // reply, and the buffer operator fuses with the callbackFlow's
                        // own channel, so this just widens that one channel.
                        .buffer(Channel.UNLIMITED)
                        .catch { caught = it }
                        .collect { chunk ->
                            if (accept(chunk, gen)) pending.trySend(Unit)
                        }
                    caught
                }
                if (failure != null) break

                val requested = beginRound(gen)
                if (requested.isEmpty() || offered.isEmpty()) break
                pending.trySend(Unit)
                val results = withContext(Dispatchers.IO) {
                    requested.map { (key, call) -> key to call.copy(result = Tools.run(call, offered)) }
                }
                synchronized(lock) {
                    if (gen == generation) {
                        calls.putAll(results)
                        callsVersion++
                    }
                }
                pending.trySend(Unit)
            }

            pump.cancel()
            failure?.let(onError)
            onFinish()
        }
    }

    /**
     * Closes the request that just ended. Its tool calls, if any, are keyed and
     * their markers appended to the reply, which the next request then
     * continues after. Empty means the model answered.
     */
    private fun beginRound(gen: Int): List<Pair<String, ToolCall>> = synchronized(lock) {
        if (gen != generation) return emptyList()
        val requested = deltas.entries.mapNotNull { (index, call) ->
            if (call.name.isEmpty()) return@mapNotNull null
            ToolCall(
                // Some endpoints leave the id out; the reply needs one to point at.
                id = call.id.ifEmpty { "call_${rounds}_$index" },
                name = call.name,
                arguments = call.arguments.toString().ifBlank { "{}" },
            )
        }.map { call -> (calls.size + 1).toString().also { calls[it] = call } to call }
        deltas.clear()
        if (requested.isEmpty()) return emptyList()
        rounds++
        callsVersion++
        while (content.isNotEmpty() && content.last().isWhitespace()) content.setLength(content.length - 1)
        if (content.isNotEmpty()) content.append("\n\n")
        requested.joinTo(content, "\n") { (key, _) -> ToolText.marker(key) }
        content.append("\n\n")
        reasoningBreak = reasoning.isNotEmpty()
        requested
    }

    /**
     * Unwinds the collector, which trips `awaitClose` in
     * [OpenAiClient.streamChat] and cancels the underlying EventSource — so this
     * releases the socket, not just the UI state. The caller finishes up itself,
     * since `onFinish` dies with the job.
     */
    fun cancel() {
        synchronized(lock) {
            stopped = true
            generation++
        }
        job?.cancel()
    }

    fun reset() = synchronized(lock) {
        generation++
        content.setLength(0)
        reasoning.setLength(0)
        publishedContent = 0
        publishedReasoning = 0
        calls.clear()
        callsVersion = 0
        publishedCallsVersion = 0
        rounds = 0
        roundStart = 0
        deltas.clear()
        reasoningBreak = false
        usage = TurnStats()
        promptChars = 0
        startedAt = 0L
        firstTokenAt = 0L
        lastTokenAt = 0L
        stopped = false
    }

    /** False when [gen] belongs to a superseded stream and the event was dropped. */
    private fun accept(event: OpenAiClient.StreamEvent, gen: Int): Boolean = synchronized(lock) {
        if (gen != generation) return false
        when (event) {
            is OpenAiClient.StreamEvent.Usage -> usage = event.stats
            is OpenAiClient.StreamEvent.Text -> {
                val now = SystemClock.elapsedRealtime()
                if (firstTokenAt == 0L) firstTokenAt = now
                lastTokenAt = now
                accept(event.chunk)
            }
            is OpenAiClient.StreamEvent.ToolCallDelta -> {
                val now = SystemClock.elapsedRealtime()
                if (firstTokenAt == 0L) firstTokenAt = now
                lastTokenAt = now
                val call = deltas.getOrPut(event.index) { PendingCall() }
                // Only the first fragment names the call; later ones may repeat it.
                if (call.id.isEmpty() && event.id != null) call.id = event.id
                if (call.name.isEmpty() && event.name != null) call.name = event.name
                event.arguments?.let(call.arguments::append)
            }
        }
        true
    }

    private fun accept(chunk: Reasoning.Chunk) {
        if (reasoningBreak && chunk !is Reasoning.Chunk.Reply) {
            reasoning.append("\n\n")
            reasoningBreak = false
        }
        when (chunk) {
            is Reasoning.Chunk.Reply -> content.append(chunk.text)
            is Reasoning.Chunk.Thought -> reasoning.append(chunk.text)
            // A chat template supplied the opening tag, so this request's reply
            // so far was really the trace. Earlier rounds keep their text.
            Reasoning.Chunk.Reclassify -> {
                reasoning.append(content, roundStart, content.length)
                content.setLength(roundStart)
            }
        }
    }

    /** Null when nothing new has arrived since the last tick. */
    private fun publish(): StreamUpdate? = synchronized(lock) {
        // Not `>`: a reclassification shrinks the reply and grows the trace.
        val contentChanged = content.length != publishedContent
        val reasoningChanged = reasoning.length != publishedReasoning
        val callsChanged = callsVersion != publishedCallsVersion
        if (!contentChanged && !reasoningChanged && !callsChanged) return null
        publishedContent = content.length
        publishedReasoning = reasoning.length
        publishedCallsVersion = callsVersion
        return StreamUpdate(
            content = if (contentChanged) content.toString() else null,
            reasoning = if (reasoningChanged) reasoning.toString() else null,
            calls = if (callsChanged) calls.toMap() else null,
        )
    }

    /** The calls that ran; one cut short by a stop is dropped along with its marker. */
    fun committedCalls(): ToolCalls = synchronized(lock) {
        calls.filterValues { it.result != null }
    }

    /**
     * Rejoins the two buffers into the single string the tree and the database
     * store. Keeping one content column means no schema migration and no second
     * write path; `Reasoning.split` takes it apart again once, on render.
     *
     * Read from the buffers rather than from published state: [cancel] stops the
     * job mid-cadence, so the last publish can lag them by up to one tick.
     */
    fun committedText(): String = synchronized(lock) {
        val unfinished = calls.filterValues { it.result == null }.keys
        val reply = ToolText.strip(content.toString(), unfinished).trim()
        val thought = reasoning.toString().trim()
        if (thought.isEmpty()) return reply
        return buildString(thought.length + reply.length + THINK_WRAPPER_LENGTH) {
            append("<think>\n").append(thought).append("\n</think>\n\n").append(reply)
        }
    }

    /**
     * What the turn cost, for [committedText]'s node. Read from the buffers for
     * the same reason [committedText] is: [cancel] can land mid-cadence.
     *
     * A stop is recorded rather than inferred, because whether one leaves a
     * token count behind is the endpoint's choice: llama.cpp and OpenAI send
     * `usage` only on a final chunk an abort never reaches, while endpoints that
     * repeat it on every chunk leave their last one. Both keep a duration that
     * must stay out of the averages, and only the flag says so.
     */
    fun finalStats(): TurnStats = synchronized(lock) {
        val reported = usage.copy(stopped = stopped, promptChars = promptChars)
        if (firstTokenAt == 0L) return reported
        return reported.copy(
            // Server timings win where there are any. Ours run between the FIRST
            // and LAST token rather than to "now", so a reply the user stopped
            // reports the rate it was actually producing instead of being
            // penalised for however long the socket then sat open — and they
            // therefore cover one token fewer than arrived.
            // Floored at a tick: a short reply off a fast endpoint can arrive
            // inside one clock granule, and 0 ms is not a divisible window.
            genMs = reported.genMs ?: (lastTokenAt - firstTokenAt).coerceAtLeast(1L),
            genTokens = reported.genTokens
                ?: reported.completionTokens?.minus(1)?.takeIf { it > 0 },
            // Kept out of the generation window, and reported separately: on a
            // cold model load this is dominated by prompt evaluation and would
            // otherwise drag the tokens/sec figure to something that describes
            // the server's startup, not its throughput.
            ttftMs = if (startedAt == 0L) null else firstTokenAt - startedAt,
        )
    }

    private class PendingCall {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
    }

    private companion object {
        /** Requests per reply that may still call tools, so a looping model cannot run forever. */
        const val MAX_TOOL_ROUNDS = 8

        /**
         * Minimum spacing between UI publishes of a growing reply (~30 Hz).
         * Text streaming reads as perfectly smooth at this rate while doing far
         * less recomposition work than the token rate, which on a fast local
         * endpoint can exceed the display refresh rate.
         */
        const val PUBLISH_INTERVAL_MS = 33L

        /** `<think>\n` + `\n</think>\n\n`, pre-sized so the join never regrows. */
        const val THINK_WRAPPER_LENGTH = 21
    }
}
