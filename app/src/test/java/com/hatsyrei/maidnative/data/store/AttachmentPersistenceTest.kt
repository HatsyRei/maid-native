package com.hatsyrei.maidnative.data.store

import com.hatsyrei.maidnative.data.db.MessageEntity
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.domain.tree.MessageTree
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPersistenceTest {

    private val image = Attachment("a1", Attachment.Kind.IMAGE, "cat.jpg", "image/jpeg", 42, "/data/cat.jpg")

    private val node = MessageNode(
        id = "u",
        role = "user",
        content = "look",
        root = "r",
        parent = "r",
        metadata = mapOf("title" to "x"),
        attachments = listOf(image),
    )

    @Test
    fun roomRoundTrip_keepsAttachmentsTyped() {
        assertEquals(node, MessageEntity.from(node).toNode())
    }

    @Test
    fun storedMetadata_carriesRecordsWithPath() {
        val stored = JSONObject(MessageEntity.from(node).metadata)
        val record = stored.getJSONArray("attachments").getJSONObject(0)
        assertEquals("/data/cat.jpg", record.getString("path"))
        assertEquals("IMAGE", record.getString("kind"))
    }

    @Test
    fun nodeWithoutAttachments_storesNoKey() {
        val plain = node.copy(attachments = emptyList())
        assertFalse(JSONObject(MessageEntity.from(plain).metadata).has("attachments"))
    }

    @Test
    fun unknownKind_isDroppedOnRead() {
        val raw = MessageEntity.from(node).let {
            it.copy(metadata = it.metadata.replace("\"IMAGE\"", "\"HOLOGRAM\""))
        }
        assertTrue(raw.toNode().attachments.isEmpty())
    }

    @Test
    fun exportRoundTrip_goesThroughRecordConverters() {
        val text = MessageStore.encodeExport(listOf(node)) { JSONObject().put("kind", it.kind.name).put("name", it.name) }
        val back = MessageStore.decodeExport(text, ::attachmentFromRecord).single()
        assertEquals("cat.jpg", back.attachments.single().name)
        assertEquals("", back.attachments.single().path)
        assertFalse(back.metadata.containsKey("attachments"))
    }

    @Test
    fun updateContent_withEqualAttachments_isNoOp() {
        val mappings = mapOf(node.id to node)
        val same = MessageTree.updateContent(mappings, node.id, { it }, attachments = listOf(image.copy()))
        assertSame(mappings, same)
    }
}
