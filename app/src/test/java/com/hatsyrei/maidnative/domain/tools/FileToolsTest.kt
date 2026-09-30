package com.hatsyrei.maidnative.domain.tools

import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolsTest {

    private class Memory(vararg files: Pair<String, String>) : Documents {
        val bytes = files.associate { (uri, text) -> uri to text.toByteArray() }.toMutableMap()

        override fun read(uri: String, limit: Int): ByteArray = bytes.getValue(uri).let { it.copyOf(minOf(it.size, limit + 1)) }

        override fun write(uri: String, bytes: ByteArray) {
            this.bytes[uri] = bytes
        }

        fun text(uri: String) = String(bytes.getValue(uri))
    }

    private val notes = LinkedFile("content://a", "notes.md")
    private val other = LinkedFile("content://b", "notes.md")

    private fun run(docs: Documents, files: List<LinkedFile>, name: String, args: String): JSONObject {
        val tools = FileTools.of(files, docs)
        return JSONObject(runBlocking { Tools.run(ToolCall("1", name, args), tools) })
    }

    @Test
    fun `no files offers no tools`() {
        assertTrue(FileTools.of(emptyList(), Memory()).isEmpty())
    }

    @Test
    fun `files round trip through metadata`() {
        val metadata = LinkedFileStore.writeInto(listOf(notes, other), mapOf("title" to "t"))
        val node = MessageNode("r", "system", "", "r", metadata = metadata)
        assertEquals(listOf(notes, other), node.linkedFiles())
        assertTrue(LinkedFileStore.writeInto(emptyList(), metadata) == mapOf("title" to "t"))
        assertTrue(LinkedFileStore.decode("nope").isEmpty())
    }

    @Test
    fun `a thread reaches each file once`() {
        fun node(id: String, vararg files: LinkedFile) =
            MessageNode(id, "user", "", "r", metadata = LinkedFileStore.writeInto(files.toList(), emptyMap()))
        val thread = listOf(node("1", notes), node("2"), node("3", notes.copy(name = "renamed.md"), other))
        assertEquals(listOf(notes, other), LinkedFileStore.inThread(thread))
    }

    @Test
    fun `reads numbered lines and says where to continue`() {
        val docs = Memory("content://a" to "one\r\ntwo\nthree\nfour\n")
        val out = run(docs, listOf(notes), "read_file", """{"file":"notes.md","start_line":2,"max_lines":2}""")
        assertEquals("2\ttwo\n3\tthree", out.getString("content"))
        assertEquals(3, out.getInt("end_line"))
        // The final newline ends line 4 rather than starting a fifth.
        assertEquals(4, out.getInt("total_lines"))
        val past = run(docs, listOf(notes), "read_file", """{"start_line":9}""")
        assertTrue(past.getString("error").contains("4 lines"))
    }

    @Test
    fun `a read stops at the character budget`() {
        val line = "x".repeat(FileTools.MAX_CHARS / 2)
        val out = run(Memory("content://a" to "$line\n$line\n$line"), listOf(notes), "read_file", "{}")
        assertEquals(1, out.getInt("end_line"))
        val huge = run(Memory("content://a" to "y".repeat(FileTools.MAX_CHARS * 2)), listOf(notes), "read_file", "{}")
        assertEquals(1, huge.getInt("end_line"))
        assertTrue(huge.getString("content").endsWith("…"))
    }

    @Test
    fun `repeated names are told apart`() {
        val docs = Memory("content://a" to "first", "content://b" to "second")
        val out = run(docs, listOf(notes, other), "read_file", """{"file":"notes.md (2)"}""")
        assertEquals("1\tsecond", out.getString("content"))
    }

    @Test
    fun `a single file needs no name`() {
        val out = run(Memory("content://a" to "only"), listOf(notes), "read_file", "{}")
        assertEquals("1\tonly", out.getString("content"))
    }

    @Test
    fun `unknown name lists the linked files`() {
        val out = run(Memory("content://a" to "x"), listOf(notes), "read_file", """{"file":"x.txt"}""")
        assertTrue(out.getString("error").contains("\"notes.md\""))
    }

    @Test
    fun `write replaces and append adds on a new line`() {
        val docs = Memory("content://a" to "one")
        run(docs, listOf(notes), "write_file", """{"file":"notes.md","content":"two","append":true}""")
        assertEquals("one\ntwo", docs.text("content://a"))
        run(docs, listOf(notes), "write_file", """{"file":"notes.md","content":"three"}""")
        assertEquals("three", docs.text("content://a"))
    }

    @Test
    fun `edits apply in order and all or nothing`() {
        val docs = Memory("content://a" to "a b a")
        val twice = run(docs, listOf(notes), "edit_file", """{"edits":[{"old_text":"a","new_text":"c"}]}""")
        assertTrue(twice.getString("error").contains("2 times"))
        val partial = run(
            docs, listOf(notes), "edit_file",
            """{"edits":[{"old_text":"a b","new_text":"c"},{"old_text":"z","new_text":"c"}]}""",
        )
        assertTrue(partial.getString("error").contains("edits[1]"))
        assertEquals("a b a", docs.text("content://a"))
        // The second edit sees the first's result: "a b" became "c", leaving one "a".
        val out = run(
            docs, listOf(notes), "edit_file",
            """{"edits":[{"old_text":"a b","new_text":"c"},{"old_text":"a","new_text":"d"}]}""",
        )
        assertEquals(2, out.getInt("edits"))
        assertEquals("c d", docs.text("content://a"))
    }

    @Test
    fun `edits sent as a string are accepted`() {
        val docs = Memory("content://a" to "old")
        val args = JSONObject().put("edits", """[{"old_text":"old","new_text":"new"}]""").toString()
        run(docs, listOf(notes), "edit_file", args)
        assertEquals("new", docs.text("content://a"))
    }

    @Test
    fun `search reports numbered lines with context`() {
        val docs = Memory("content://a" to "alpha\r\nbeta TODO\r\ngamma\r\ntodo: todo\r\nend")
        val out = run(docs, listOf(notes), "search_file", """{"file":"notes.md","query":"todo","context_lines":1}""")
        assertEquals(2, out.getInt("total_matches"))
        val first = out.getJSONArray("matches").getJSONObject(0)
        assertEquals(2, first.getInt("line"))
        assertEquals("1\talpha\n2\tbeta TODO\n3\tgamma", first.getString("text"))
        // Repeats on one line are one match.
        assertEquals(4, out.getJSONArray("matches").getJSONObject(1).getInt("line"))
    }

    @Test
    fun `search honours case, caps results and spans lines`() {
        val docs = Memory("content://a" to "a\nA\na\nb")
        val exact = run(docs, listOf(notes), "search_file", """{"query":"A","case_sensitive":true,"context_lines":0}""")
        assertEquals(1, exact.getInt("total_matches"))
        val capped = run(docs, listOf(notes), "search_file", """{"query":"a","max_results":1,"context_lines":0}""")
        assertEquals(3, capped.getInt("total_matches"))
        assertEquals(1, capped.getJSONArray("matches").length())
        val spanning = run(docs, listOf(notes), "search_file", """{"query":"a\nb","context_lines":0}""")
        assertEquals("3\ta\n4\tb", spanning.getJSONArray("matches").getJSONObject(0).getString("text"))
    }

    @Test
    fun `search cuts long lines around the match`() {
        val line = "x".repeat(1000) + "needle" + "y".repeat(1000)
        val out = run(Memory("content://a" to line), listOf(notes), "search_file", """{"query":"needle"}""")
        val text = out.getJSONArray("matches").getJSONObject(0).getString("text")
        assertTrue(text.startsWith("1\t…") && text.endsWith("…") && "needle" in text)
        assertEquals(FileTools.MAX_LINE + 4, text.length)
    }

    @Test
    fun `binary and oversized files are refused`() {
        val docs = Memory()
        docs.bytes["content://a"] = byteArrayOf(0xC3.toByte(), 0x28)
        assertTrue(run(docs, listOf(notes), "read_file", "{}").getString("error").contains("UTF-8"))
        docs.bytes["content://a"] = ByteArray(FileTools.MAX_BYTES + 1) { 'a'.code.toByte() }
        assertTrue(run(docs, listOf(notes), "read_file", "{}").getString("error").contains("larger"))
    }
}
