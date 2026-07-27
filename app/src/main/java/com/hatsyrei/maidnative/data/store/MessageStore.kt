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
            val out = LinkedHashMap<String, MessageNode>()
            for (node in decodeNodes(JSONArray(text))) out[node.id] = node
            out
        }.getOrElse { LinkedHashMap() }
    }

    suspend fun save(mappings: Mappings): Unit = withContext(Dispatchers.IO) {
        val text = encodeNodes(mappings.values).toString()
        // Atomic-ish write: temp then rename.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        /** Serialize a single node to its JSON object shape. */
        private fun nodeToJson(node: MessageNode): JSONObject = JSONObject().apply {
            put("id", node.id)
            put("role", node.role)
            put("content", node.content)
            put("root", node.root)
            put("parent", node.parent)
            put("child", node.child)
            put("metadata", JSONObject(node.metadata.mapValues { it.value ?: JSONObject.NULL }))
        }

        private fun jsonToNode(o: JSONObject): MessageNode = MessageNode(
            id = o.getString("id"),
            role = o.getString("role"),
            content = o.getString("content"),
            root = o.getString("root"),
            parent = o.optStringOrNull("parent"),
            child = o.optStringOrNull("child"),
            metadata = o.optJSONObject("metadata")?.toMap() ?: emptyMap(),
        )

        /** Serialize nodes to a JSON array (the on-disk snapshot shape). */
        fun encodeNodes(nodes: Collection<MessageNode>): JSONArray {
            val arr = JSONArray()
            for (node in nodes) arr.put(nodeToJson(node))
            return arr
        }

        /** Parse a JSON array of nodes into [MessageNode]s. */
        fun decodeNodes(arr: JSONArray): List<MessageNode> {
            val out = ArrayList<MessageNode>(arr.length())
            for (i in 0 until arr.length()) out += jsonToNode(arr.getJSONObject(i))
            return out
        }

        /**
         * Serialize a conversation for export. Matches the React Native app's
         * format: a JSON object keyed by node id, values are node objects
         * (a direct dump of the `mappings` map). Insertion order is preserved.
         */
        fun encodeExport(nodes: Collection<MessageNode>): String {
            val obj = JSONObject()
            for (node in nodes) obj.put(node.id, nodeToJson(node))
            return obj.toString(2)
        }

        /**
         * Parse an exported conversation. Primary format is the React Native
         * app's `mappings` dump: a JSON object keyed by node id. Also tolerates
         * a bare `[...]` array or an `{ "nodes": [...] }` envelope.
         */
        fun decodeExport(text: String): List<MessageNode> {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) return decodeNodes(JSONArray(trimmed))
            val obj = JSONObject(trimmed)
            if (obj.has("nodes")) return decodeNodes(obj.getJSONArray("nodes"))
            // React Native map form: keys are ids, values are node objects.
            val out = ArrayList<MessageNode>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val v = obj.get(keys.next())
                if (v is JSONObject && v.has("id")) out += jsonToNode(v)
            }
            return out
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
