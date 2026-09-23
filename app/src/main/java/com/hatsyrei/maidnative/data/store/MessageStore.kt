package com.hatsyrei.maidnative.data.store

import com.hatsyrei.maidnative.data.optStringOrNull
import com.hatsyrei.maidnative.data.toJsonObject
import com.hatsyrei.maidnative.data.toMap
import com.hatsyrei.maidnative.domain.Attachment
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
    private fun nodeToJson(node: MessageNode, record: (Attachment) -> JSONObject?): JSONObject =
        JSONObject().apply {
            put("id", node.id)
            put("role", node.role)
            put("content", node.content)
            put("root", node.root)
            put("parent", node.parent)
            put("child", node.child)
            put("metadata", node.persistedMetadata(record).toJsonObject())
        }

    private fun jsonToNode(o: JSONObject, parse: (JSONObject) -> Attachment?): MessageNode = MessageNode(
        id = o.getString("id"),
        role = o.getString("role"),
        content = o.getString("content"),
        root = o.getString("root"),
        parent = o.optStringOrNull("parent"),
        child = o.optStringOrNull("child"),
        metadata = o.optJSONObject("metadata")?.toMap() ?: emptyMap(),
    ).liftAttachments(parse)

    /** Parse a JSON array of nodes into [MessageNode]s. */
    fun decodeNodes(
        arr: JSONArray,
        parse: (JSONObject) -> Attachment? = ::attachmentFromRecord,
    ): List<MessageNode> {
        val out = ArrayList<MessageNode>(arr.length())
        for (i in 0 until arr.length()) out += jsonToNode(arr.getJSONObject(i), parse)
        return out
    }

    /**
     * Serialize a conversation for export. Matches the React Native app's
     * format: a JSON object keyed by node id, values are node objects
     * (a direct dump of the `mappings` map). Insertion order is preserved.
     * [record] renders each attachment, or drops it by returning null.
     */
    fun encodeExport(nodes: Collection<MessageNode>, record: (Attachment) -> JSONObject?): String {
        val obj = JSONObject()
        for (node in nodes) obj.put(node.id, nodeToJson(node, record))
        return obj.toString(2)
    }

    /**
     * Parse an exported conversation. Primary format is the React Native
     * app's `mappings` dump: a JSON object keyed by node id. Also tolerates
     * a bare `[...]` array or an `{ "nodes": [...] }` envelope. [parse] turns
     * each attachment record back into an [Attachment], or drops it.
     */
    fun decodeExport(text: String, parse: (JSONObject) -> Attachment?): List<MessageNode> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return decodeNodes(JSONArray(trimmed), parse)
        val obj = JSONObject(trimmed)
        if (obj.has("nodes")) return decodeNodes(obj.getJSONArray("nodes"), parse)
        // React Native map form: keys are ids, values are node objects.
        val out = ArrayList<MessageNode>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.get(keys.next())
            if (v is JSONObject && v.has("id")) out += jsonToNode(v, parse)
        }
        return out
    }
}
