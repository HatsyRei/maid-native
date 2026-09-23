package com.hatsyrei.maidnative.ui.chat

import android.os.SystemClock
import com.hatsyrei.maidnative.data.remote.OpenAiClient
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.TurnStats
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
 * One publish tick. A null half did not grow since the last tick, so the caller
 * keeps the string it already holds rather than taking an identical copy.
 */
internal class StreamUpdate(val content: String?, val reasoning: String?)

/**
 * Runs one completion: accumulates the reply as it arrives, publishes it to the
 * caller at a bounded rate, and rejoins reply and trace once at the end.
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
        onUpdate: (StreamUpdate) -> Unit,
        onError: (Throwable) -> Unit,
        onFinish: () -> Unit,
    ) {
        reset()
        val gen = synchronized(lock) {
            // The thread as the endpoint is about to read it, which is what the
            // prompt count it returns will describe.
            promptChars = conversation.sumOf { it.content.length }
            startedAt = SystemClock.elapsedRealtime()
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

            // Collected off the main thread, so a token wakes it only through the
            // throttled pump rather than once per chunk. IO also covers building
            // the request, which reads and base64-encodes every attachment.
            val failure = withContext(Dispatchers.IO) {
                var caught: Throwable? = null
                client.streamChat(config, conversation)
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

            pump.cancel()
            failure?.let(onError)
            onFinish()
        }
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
        }
        true
    }

    private fun accept(chunk: Reasoning.Chunk) {
        when (chunk) {
            is Reasoning.Chunk.Reply -> content.append(chunk.text)
            is Reasoning.Chunk.Thought -> reasoning.append(chunk.text)
            // A chat template supplied the opening tag, so the reply so far was
            // really the trace. Both buffers live here, so the correction is a
            // move rather than a re-parse.
            Reasoning.Chunk.Reclassify -> {
                reasoning.append(content)
                content.setLength(0)
            }
        }
    }

    /** Null when nothing new has arrived since the last tick. */
    private fun publish(): StreamUpdate? = synchronized(lock) {
        // Not `>`: a reclassification shrinks the reply and grows the trace.
        val contentChanged = content.length != publishedContent
        val reasoningChanged = reasoning.length != publishedReasoning
        if (!contentChanged && !reasoningChanged) return null
        publishedContent = content.length
        publishedReasoning = reasoning.length
        return StreamUpdate(
            content = if (contentChanged) content.toString() else null,
            reasoning = if (reasoningChanged) reasoning.toString() else null,
        )
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
        val reply = content.toString().trim()
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

    private companion object {
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
