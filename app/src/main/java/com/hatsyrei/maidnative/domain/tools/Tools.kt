package com.hatsyrei.maidnative.domain.tools

import androidx.compose.runtime.Immutable
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** One function call the model asked for; [result] stays null until it has run. */
@Immutable
data class ToolCall(
    val id: String,
    val name: String,
    /** The model's arguments, as the raw JSON text it produced. */
    val arguments: String,
    val result: String? = null,
    /** The call's `extra_content` as raw JSON, echoed back verbatim (Gemini's thought_signature). */
    val extra: String? = null,
)

/**
 * The calls a reply made, keyed by the short local id its `{{tool:N}}` markers
 * name. The text lives in `content`, markers and all, so the order of text and
 * calls is exactly what the content says; this holds only the call data.
 */
typealias ToolCalls = Map<String, ToolCall>

// A JSON string rather than nested values: it survives Room, export and import
// unchanged, and compares by value in the repository's diff.
private const val KEY_TOOL_CALLS = "toolCalls"

fun MessageNode.toolCalls(): ToolCalls = ToolCallStore.decode(metadata[KEY_TOOL_CALLS] as? String)

object ToolCallStore {

    fun writeInto(calls: ToolCalls, metadata: Map<String, Any?>): Map<String, Any?> =
        if (calls.isEmpty()) metadata - KEY_TOOL_CALLS else metadata + (KEY_TOOL_CALLS to encode(calls))

    // An array, not an object keyed by id: org.json does not keep object key order everywhere.
    fun encode(calls: ToolCalls): String {
        val array = JSONArray()
        for ((key, call) in calls) {
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("arguments", call.arguments)
                    .put("result", call.result ?: JSONObject.NULL)
                    .apply { call.extra?.let { put("extra", it) } },
            )
        }
        return array.toString()
    }

    /** Tolerant: metadata can come from a hand-edited import, so a bad entry is dropped, not thrown. */
    fun decode(raw: String?): ToolCalls {
        if (raw.isNullOrEmpty()) return emptyMap()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, ToolCall>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val key = entry.optString("key").ifEmpty { null } ?: continue
            val name = entry.optString("name").ifEmpty { null } ?: continue
            out[key] = ToolCall(
                id = entry.optString("id"),
                name = name,
                arguments = entry.optString("arguments", "{}"),
                result = if (entry.isNull("result")) null else entry.optString("result"),
                extra = if (entry.isNull("extra")) null else entry.optString("extra"),
            )
        }
        return out
    }
}

/**
 * Where a reply's tool calls sit in its text. A marker is a `{{tool:N}}` line
 * naming a call that exists; anything else, including a marker for an unknown
 * id, is ordinary text. Markers only ever live in storage and the UI: requests
 * split them out into proper tool-call messages.
 */
object ToolText {

    private val MARKER = Regex("""^[ \t]*\{\{tool:([A-Za-z0-9_-]+)\}\}[ \t]*$""", RegexOption.MULTILINE)

    fun marker(key: String): String = "{{tool:$key}}"

    sealed interface Segment {
        data class Text(val text: String) : Segment

        /** Calls the model made together, in one request. */
        data class Calls(val calls: List<ToolCall>) : Segment
    }

    /**
     * Text and call runs in order. Markers with only blank lines between them
     * form one run; each id counts once, so a pasted duplicate stays text.
     */
    fun parse(text: String, calls: ToolCalls): List<Segment> {
        if (calls.isEmpty()) return if (text.isBlank()) emptyList() else listOf(Segment.Text(text))
        val out = ArrayList<Segment>()
        val used = HashSet<String>()
        var run = ArrayList<ToolCall>()
        var index = 0
        for (match in MARKER.findAll(text)) {
            val key = match.groupValues[1]
            val call = calls[key] ?: continue
            if (!used.add(key)) continue
            val between = text.substring(index, match.range.first)
            if (between.isNotBlank()) {
                if (run.isNotEmpty()) out += Segment.Calls(run)
                run = ArrayList()
                out += Segment.Text(between.trim())
            }
            run += call
            index = match.range.last + 1
        }
        if (run.isNotEmpty()) out += Segment.Calls(run)
        val rest = text.substring(index)
        if (rest.isNotBlank()) out += Segment.Text(rest.trim())
        return out
    }

    /**
     * Where the text after the last marker starts, for a reply still streaming.
     * Markers are appended in key order there, so the last key's marker is last.
     */
    fun tailStart(text: String, calls: ToolCalls): Int {
        val last = calls.keys.lastOrNull() ?: return 0
        val at = text.lastIndexOf(marker(last))
        if (at < 0) return 0
        val end = text.indexOf('\n', at)
        return if (end < 0) text.length else end + 1
    }

    /** Keys of every marker line in [text], known or not. */
    fun markerKeys(text: CharSequence): Set<String> =
        MARKER.findAll(text).mapTo(HashSet()) { it.groupValues[1] }

    /** [calls] whose markers are still in [text]; the rest were deleted by hand. */
    fun retain(calls: ToolCalls, text: String): ToolCalls {
        if (calls.isEmpty()) return calls
        val present = markerKeys(text)
        return calls.filterKeys { it in present }
    }

    /** [text] with the marker lines for [keys] removed. */
    fun strip(text: String, keys: Set<String>): String {
        if (keys.isEmpty()) return text
        return MARKER.replace(text) { if (it.groupValues[1] in keys) "" else it.value }
            .replace(BLANK_RUN, "\n\n")
            .trim()
    }

    private val BLANK_RUN = Regex("""\n[ \t]*\n(?:[ \t]*\n)+""")
}

/** A function the model may call, executed on the device. */
interface Tool {
    val name: String
    val label: String

    /** Shown in Settings; [description] is what the model reads. */
    val summary: String
    val description: String

    /** JSON Schema of the arguments object. */
    fun parameters(): JSONObject

    /** Returns the text handed back to the model. */
    suspend fun invoke(arguments: JSONObject): String
}

object Tools {
    val all: List<Tool> = listOf(GetDatetime, RollDice, FetchUrl, RunJavaScript)

    fun enabled(names: Set<String>): List<Tool> = all.filter { it.name in names }

    /** Never throws (short of cancellation): a failure becomes an error the model can read. */
    suspend fun run(call: ToolCall, available: List<Tool>): String {
        val tool = available.firstOrNull { it.name == call.name }
            ?: return error("Unknown tool: ${call.name}")
        val arguments = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return error("Arguments are not a JSON object")
        return try {
            tool.invoke(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(e.message ?: e::class.java.simpleName)
        }
    }

    private fun error(message: String): String = JSONObject().put("error", message).toString()
}
