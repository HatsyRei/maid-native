package com.hatsyrei.maidnative.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.hatsyrei.maidnative.domain.tree.MessageNode
import org.json.JSONObject

/**
 * Room mirror of the RN app's `messages` table
 * (`id, role, content, root, parent, child, metadata`).
 *
 * The natural key `id` is the primary key. Combined with `@Upsert` in the DAO
 * this preserves each row's hidden `rowid` across content updates, so a
 * `SELECT ... ORDER BY rowid` reconstructs the original insertion order — which
 * the conversation-tree logic depends on for sibling navigation and root
 * selection (see [com.hatsyrei.maidnative.domain.tree.MessageTree]).
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val root: String,
    val parent: String?,
    val child: String?,
    val metadata: String,
) {
    fun toNode(): MessageNode = MessageNode(
        id = id,
        role = role,
        content = content,
        root = root,
        parent = parent,
        child = child,
        metadata = MetadataConverter.decode(metadata),
    )

    companion object {
        fun from(node: MessageNode): MessageEntity = MessageEntity(
            id = node.id,
            role = node.role,
            content = node.content,
            root = node.root,
            parent = node.parent,
            child = node.child,
            metadata = MetadataConverter.encode(node.metadata),
        )
    }
}

/** Serializes [MessageNode.metadata] to/from a JSON object string for storage. */
object MetadataConverter {

    @TypeConverter
    fun encode(map: Map<String, Any?>): String =
        JSONObject(map.mapValues { it.value ?: JSONObject.NULL }).toString()

    @TypeConverter
    fun decode(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        val obj = JSONObject(json)
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            out[k] = if (v === JSONObject.NULL) null else v
        }
        return out
    }
}
