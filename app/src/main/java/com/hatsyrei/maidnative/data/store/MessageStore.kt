package com.hatsyrei.maidnative.data.store

import com.hatsyrei.maidnative.domain.tree.Mappings
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Interim persistence for the conversation tree: a single JSON snapshot of the
 * mappings written to the app's files dir. Mirrors the data captured by the RN
 * app's `messages` SQLite table (id, role, content, root, parent, child,
 * metadata).
 *
 * NOTE (tech debt / SPEC M1): replace with Room + incremental diff writes. This
 * whole-map snapshot is fine for the prototype but rewrites everything on save.
 */
class MessageStore(private val file: File) {

    suspend fun load(): Mappings = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext LinkedHashMap()
        runCatching {
            val text = file.readText()
            if (text.isBlank()) return@runCatching LinkedHashMap<String, MessageNode>()
            val arr = JSONArray(text)
            val out = LinkedHashMap<String, MessageNode>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val node = MessageNode(
                    id = o.getString("id"),
                    role = o.getString("role"),
                    content = o.getString("content"),
                    root = o.getString("root"),
                    parent = o.optStringOrNull("parent"),
                    child = o.optStringOrNull("child"),
                    metadata = o.optJSONObject("metadata")?.toMap() ?: emptyMap(),
                )
                out[node.id] = node
            }
            out
        }.getOrElse { LinkedHashMap() }
    }

    suspend fun save(mappings: Mappings): Unit = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (node in mappings.values) {
            val o = JSONObject()
            o.put("id", node.id)
            o.put("role", node.role)
            o.put("content", node.content)
            o.put("root", node.root)
            o.put("parent", node.parent)
            o.put("child", node.child)
            o.put("metadata", JSONObject(node.metadata.mapValues { it.value ?: JSONObject.NULL }))
            arr.put(o)
        }
        // Atomic-ish write: temp then rename.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).ifEmpty { null }

private fun JSONObject.toMap(): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = get(k)
        map[k] = if (v === JSONObject.NULL) null else v
    }
    return map
}
