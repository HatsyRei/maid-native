package com.hatsyrei.maidnative.domain.tools

import androidx.compose.runtime.Immutable
import com.hatsyrei.maidnative.domain.tree.MessageNode
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** A document the user linked on a message, which the model may read and edit in place. */
@Immutable
data class LinkedFile(val uri: String, val name: String)

// On the user message that linked them, as a JSON string for the same reasons as toolCalls.
private const val KEY_FILES = "files"

fun MessageNode.linkedFiles(): List<LinkedFile> = LinkedFileStore.decode(metadata[KEY_FILES] as? String)

object LinkedFileStore {

    /** Every file linked along [thread], once each: what its model may reach. */
    fun inThread(thread: List<MessageNode>): List<LinkedFile> =
        thread.flatMap { it.linkedFiles() }.distinctBy { it.uri }

    fun writeInto(files: List<LinkedFile>, metadata: Map<String, Any?>): Map<String, Any?> =
        if (files.isEmpty()) metadata - KEY_FILES else metadata + (KEY_FILES to encode(files))

    fun encode(files: List<LinkedFile>): String =
        JSONArray(files.map { JSONObject().put("uri", it.uri).put("name", it.name) }).toString()

    fun decode(raw: String?): List<LinkedFile> {
        if (raw.isNullOrEmpty()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = entry.optString("uri").ifEmpty { null } ?: return@mapNotNull null
            LinkedFile(uri, entry.optString("name").ifEmpty { uri.substringAfterLast('/') })
        }
    }
}

/** Where linked files' bytes live: the ContentResolver on a device. */
interface Documents {
    /** At most [limit] + 1 bytes, so an oversized file shows as one. */
    fun read(uri: String, limit: Int): ByteArray

    fun write(uri: String, bytes: ByteArray)
}

/** read_file, write_file and edit_file over the files linked to one chat. */
class FileTools private constructor(files: List<LinkedFile>, private val documents: Documents) {

    // Models address files by name; a repeated name gets a numbered suffix.
    private val byName: Map<String, LinkedFile> = LinkedHashMap<String, LinkedFile>().apply {
        for (file in files) {
            var name = file.name
            var n = 2
            while (name in this) name = "${file.name} (${n++})"
            put(name, file)
        }
    }

    private val listing = byName.keys.joinToString(", ") { "\"$it\"" }

    private fun fileParam(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(byName.keys))
        .put("description", "Name of the linked file.")

    private fun schema(required: List<String>, vararg properties: Pair<String, JSONObject>): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { for ((k, v) in properties) put(k, v) })
            .put("required", JSONArray(required))

    private fun target(arguments: JSONObject): Pair<String, LinkedFile> {
        val name = (arguments.opt("file") as? String)?.trim().orEmpty()
        byName[name]?.let { return name to it }
        // One file leaves nothing to choose between.
        if (name.isEmpty() && byName.size == 1) return byName.entries.first().toPair()
        throw IllegalArgumentException("No linked file named \"$name\". Linked files: $listing")
    }

    private fun load(name: String, file: LinkedFile): String {
        val bytes = documents.read(file.uri, MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "\"$name\" is larger than ${MAX_BYTES / 1024} KB" }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("\"$name\" is not a UTF-8 text file")
        }
    }

    private fun save(name: String, file: LinkedFile, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "The new content is larger than ${MAX_BYTES / 1024} KB" }
        documents.write(file.uri, bytes)
        return JSONObject().put("file", name).put("saved", true).put("total_lines", lines(text).size)
    }

    private val read = object : Tool {
        override val name = "read_file"
        override val label = "Read file"
        override val summary = "Read a file linked to the chat."
        override val description =
            "Read lines of a text file the user linked to this chat. Linked files: $listing. " +
                "Returns up to max_lines lines from start_line, each prefixed with its line number and " +
                "a tab, which are not part of the file, plus end_line and total_lines; if end_line is " +
                "below total_lines and you need more, call again with start_line = end_line + 1. Output " +
                "stops early at $MAX_CHARS characters. In a long file, search_file finds a passage " +
                "without reading it all."

        override fun parameters(): JSONObject = schema(
            listOf("file"),
            "file" to fileParam(),
            "start_line" to JSONObject().put("type", "integer").put("minimum", 1)
                .put("description", "First line to return, counting from 1. Defaults to 1."),
            "max_lines" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_LINES)
                .put("description", "Most lines to return. Defaults to $DEFAULT_LINES."),
        )

        override suspend fun invoke(arguments: JSONObject): String {
            val (name, file) = target(arguments)
            val first = arguments.int("start_line", default = 1, range = 1..Int.MAX_VALUE)
            val maxLines = arguments.int("max_lines", default = DEFAULT_LINES, range = 1..MAX_LINES)
            val all = lines(load(name, file))
            require(first <= maxOf(all.size, 1)) { "start_line is past the end; \"$name\" has ${all.size} lines" }
            val content = StringBuilder()
            var last = first - 1
            while (last < all.size && last - first + 1 < maxLines) {
                // A single line longer than the budget (minified data) is cut rather than skipped.
                val line = numbered(last + 1, all[last]).let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "…" else it }
                if (content.isNotEmpty() && content.length + 1 + line.length > MAX_CHARS) break
                if (content.isNotEmpty()) content.append('\n')
                content.append(line)
                last++
            }
            return JSONObject()
                .put("file", name)
                .put("start_line", first)
                .put("end_line", last)
                .put("total_lines", all.size)
                .put("content", content.toString())
                .toString()
        }
    }

    private val search = object : Tool {
        override val name = "search_file"
        override val label = "Search file"
        override val summary = "Find text in a file linked to the chat."
        override val description =
            "Find text in a text file the user linked to this chat without reading all of it. " +
                "Linked files: $listing. Plain text, not a regex. Returns each matching line's number " +
                "with context_lines lines on each side, numbered like read_file's output. Lines over " +
                "$MAX_LINE characters are cut short around the match, marked with …."

        override fun parameters(): JSONObject = schema(
            listOf("file", "query"),
            "file" to fileParam(),
            "query" to JSONObject().put("type", "string").put("description", "The text to find."),
            "case_sensitive" to JSONObject().put("type", "boolean")
                .put("description", "Match letter case exactly. Defaults to false."),
            "context_lines" to JSONObject().put("type", "integer").put("minimum", 0).put("maximum", MAX_CONTEXT)
                .put("description", "Lines to show before and after each match. Defaults to $DEFAULT_CONTEXT."),
            "max_results" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_RESULTS)
                .put("description", "Most matches to return. Defaults to $DEFAULT_RESULTS."),
        )

        override suspend fun invoke(arguments: JSONObject): String {
            val (name, file) = target(arguments)
            val query = arguments.opt("query") as? String
            require(!query.isNullOrEmpty()) { "\"query\" is required" }
            val ignoreCase = !arguments.optBoolean("case_sensitive", false)
            val context = arguments.int("context_lines", default = DEFAULT_CONTEXT, range = 0..MAX_CONTEXT)
            val maxResults = arguments.int("max_results", default = DEFAULT_RESULTS, range = 1..MAX_RESULTS)
            val text = load(name, file)
            val lines = lines(text)
            val starts = IntArray(lines.size)
            for (i in 1 until lines.size) starts[i] = starts[i - 1] + lines[i - 1].length + 1
            // A query spanning lines shows every line it covers.
            val span = query.count { it == '\n' }

            val matches = JSONArray()
            var total = 0
            var lastLine = -1
            var at = text.indexOf(query, 0, ignoreCase)
            while (at >= 0) {
                val line = starts.binarySearch(at).let { if (it >= 0) it else -it - 2 }
                // One entry per line, however often it matches.
                if (line != lastLine) {
                    lastLine = line
                    total++
                    if (matches.length() < maxResults) {
                        val from = maxOf(0, line - context)
                        val to = minOf(lines.lastIndex, line + span + context)
                        val snippet = (from..to).joinToString("\n") { i ->
                            numbered(i + 1, clip(lines[i], if (i == line) at - starts[line] else 0))
                        }
                        matches.put(JSONObject().put("line", line + 1).put("text", snippet))
                    }
                }
                at = text.indexOf(query, at + query.length, ignoreCase)
            }
            return JSONObject()
                .put("file", name)
                .put("total_matches", total)
                .put("matches", matches)
                .toString()
        }

        private fun clip(line: String, around: Int): String {
            if (line.length <= MAX_LINE) return line
            val from = (around - MAX_LINE / 2).coerceIn(0, line.length - MAX_LINE)
            val to = from + MAX_LINE
            return (if (from > 0) "…" else "") + line.substring(from, to) + (if (to < line.length) "…" else "")
        }
    }

    private val write = object : Tool {
        override val name = "write_file"
        override val label = "Write file"
        override val summary = "Replace or append to a file linked to the chat."
        override val description =
            "Replace the whole content of a text file the user linked to this chat, or add to its " +
                "end with append. Linked files: $listing. The user's file is changed immediately. " +
                "For a small change to a longer file, use edit_file instead."

        override fun parameters(): JSONObject = schema(
            listOf("file", "content"),
            "file" to fileParam(),
            "content" to JSONObject().put("type", "string").put("description", "The text to write."),
            "append" to JSONObject().put("type", "boolean")
                .put("description", "Add content to the end of the file instead of replacing it. Defaults to false."),
        )

        override suspend fun invoke(arguments: JSONObject): String {
            val (name, file) = target(arguments)
            val content = arguments.opt("content") as? String
                ?: throw IllegalArgumentException("\"content\" is required")
            if (!arguments.optBoolean("append", false)) return save(name, file, content).toString()
            val existing = load(name, file)
            val separator = if (existing.isEmpty() || existing.endsWith('\n')) "" else "\n"
            return save(name, file, existing + separator + content).toString()
        }
    }

    private val edit = object : Tool {
        override val name = "edit_file"
        override val label = "Edit file"
        override val summary = "Replace passages in a file linked to the chat."
        override val description =
            "Replace one or more exact passages of a text file the user linked to this chat. " +
                "Linked files: $listing. Edits apply in order, each to the result of the one before. " +
                "Each old_text must match the file exactly, whitespace included and without " +
                "read_file's line-number prefixes, and occur exactly once; include surrounding lines " +
                "to make it unique. If any edit fails, nothing is saved. search_file finds the exact " +
                "text in a long file. The user's file is changed immediately."

        override fun parameters(): JSONObject = schema(
            listOf("file", "edits"),
            "file" to fileParam(),
            "edits" to JSONObject()
                .put("type", "array")
                .put("minItems", 1)
                .put("maxItems", MAX_EDITS)
                .put(
                    "items",
                    schema(
                        listOf("old_text", "new_text"),
                        "old_text" to JSONObject().put("type", "string").put("description", "The exact text to replace."),
                        "new_text" to JSONObject().put("type", "string").put("description", "The text to put in its place."),
                    ),
                ),
        )

        override suspend fun invoke(arguments: JSONObject): String {
            val (name, file) = target(arguments)
            // Some models send a nested array as a JSON string.
            val edits = when (val raw = arguments.opt("edits")) {
                is JSONArray -> raw
                is String -> runCatching { JSONArray(raw) }.getOrNull()
                else -> null
            } ?: throw IllegalArgumentException("\"edits\" must be an array of {old_text, new_text}")
            require(edits.length() in 1..MAX_EDITS) { "\"edits\" must hold 1 to $MAX_EDITS edits" }
            var text = load(name, file)
            for (i in 0 until edits.length()) {
                val entry = edits.optJSONObject(i) ?: throw IllegalArgumentException("edits[$i] is not an object")
                val old = entry.opt("old_text") as? String
                require(!old.isNullOrEmpty()) { "edits[$i].old_text is required" }
                val new = entry.opt("new_text") as? String
                    ?: throw IllegalArgumentException("edits[$i].new_text is required")
                val at = text.indexOf(old)
                require(at >= 0) {
                    "edits[$i].old_text was not found in \"$name\", so nothing was saved; copy it exactly, " +
                        "without line-number prefixes"
                }
                val count = generateSequence(at) { text.indexOf(old, it + old.length).takeIf { n -> n >= 0 } }.count()
                require(count == 1) {
                    "edits[$i].old_text occurs $count times in \"$name\", so nothing was saved; include more surrounding text"
                }
                text = text.replaceRange(at, at + old.length, new)
            }
            return save(name, file, text).put("edits", edits.length()).toString()
        }
    }

    companion object : ToolSwitch {
        override val name = "file_operations"
        override val label = "File operations"
        override val summary = "Read and edit selected files."

        internal const val MAX_BYTES = 1024 * 1024
        internal const val MAX_CHARS = 50_000
        private const val DEFAULT_LINES = 500
        private const val MAX_LINES = 2_000
        private const val DEFAULT_CONTEXT = 2
        private const val MAX_CONTEXT = 10
        private const val DEFAULT_RESULTS = 20
        private const val MAX_RESULTS = 100
        internal const val MAX_LINE = 400
        private const val MAX_EDITS = 50

        /** Lines as numbered in the tools' output: a final newline ends the last line rather than starting one. */
        private fun lines(text: String): List<String> =
            if (text.isEmpty()) emptyList() else text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }

        private fun numbered(number: Int, line: String): String = "$number\t${line.removeSuffix("\r")}"

        /** Nothing when no file is linked, so the model is not offered tools it cannot use. */
        fun of(files: List<LinkedFile>, documents: Documents): List<Tool> =
            if (files.isEmpty()) {
                emptyList()
            } else {
                FileTools(files, documents).let { listOf(it.read, it.search, it.write, it.edit) }
            }
    }
}
