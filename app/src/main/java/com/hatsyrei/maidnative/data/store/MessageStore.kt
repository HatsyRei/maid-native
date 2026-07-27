package com.hatsyrei.maidnative.data.store

import com.hatsyrei.maidnative.domain.tree.MessageNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization helpers for the conversation tree, in the React
 * Native app's on-disk shapes.
 *
 * Runtime persistence now lives in Room
 * ([com.hatsyrei.maidnative.data.db.MessageRepository]); this object only
 * provides:
 *  - export/import in the RN-compatible format (`encodeExport`/`decodeExport`),
 *  - `decodeNodes` for a one-time migration of the legacy `messages.json`
 *    snapshot into Room.
 */
object MessageStore {

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

    /** Serialize nodes to a JSON array (the legacy on-disk snapshot shape). */
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
