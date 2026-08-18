package com.hatsyrei.maidnative.ui.chat

import com.hatsyrei.maidnative.data.remote.OpenAiClient
import com.hatsyrei.maidnative.domain.Reasoning
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

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

    private var job: Job? = null

    fun start(
        config: OpenAiClient.Config,
        conversation: List<MessageNode>,
        onUpdate: (StreamUpdate) -> Unit,
        onError: (Throwable) -> Unit,
        onFinish: () -> Unit,
    ) {
        reset()
        job = scope.launch {
            // Publishing is demand-driven, never timer-driven: the pump sleeps on
            // `pending` and is woken only by an arriving token. A free-running
            // ticker would keep waking the main thread ~30x/second for as long as
            // the request was open, which on a stalled stream is pure drain. An
            // idle TCP socket, by contrast, costs essentially nothing.
            val pending = Channel<Unit>(Channel.CONFLATED)

            val pump = launch {
                for (signal in pending) {
                    publish()?.let(onUpdate)
                    // Trailing throttle. Tokens arriving inside this window
                    // collapse into the single conflated signal that follows it,
                    // so the UI updates at most once per interval while the first
                    // token of a burst still lands immediately.
                    delay(PUBLISH_INTERVAL_MS)
                }
            }

            client.streamChat(config, conversation)
                // UNLIMITED rather than the default 64-slot buffer: the SSE
                // listener publishes with `trySend`, which silently drops a
                // chunk when the channel is full. Losing a token corrupts the
                // reply, and the buffer operator fuses with the callbackFlow's
                // own channel, so this just widens that one channel.
                .buffer(Channel.UNLIMITED)
                // Building the request reads every attachment off disk and
                // base64-encodes it, which must not happen on the main thread.
                // Fuses with the buffer above, so it stays one channel.
                .flowOn(Dispatchers.IO)
                .catch { onError(it) }
                .collect { chunk ->
                    accept(chunk)
                    pending.trySend(Unit)
                }

            pump.cancel()
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
        job?.cancel()
    }

    fun reset() {
        content.setLength(0)
        reasoning.setLength(0)
        publishedContent = 0
        publishedReasoning = 0
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
    private fun publish(): StreamUpdate? {
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
    fun committedText(): String {
        val reply = content.toString().trim()
        val thought = reasoning.toString().trim()
        if (thought.isEmpty()) return reply
        return buildString(thought.length + reply.length + THINK_WRAPPER_LENGTH) {
            append("<think>\n").append(thought).append("\n</think>\n\n").append(reply)
        }
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
