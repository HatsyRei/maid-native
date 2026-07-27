package com.hatsyrei.maidnative.domain.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTreeTest {

    private fun linear(): Mappings {
        // system(s) -> user(u) -> assistant(a)
        var m: Mappings = LinkedHashMap()
        m = MessageTree.addNode(m, "s", "system", "sys", null, null, null, mapOf("title" to "New Chat"))
        m = MessageTree.addNode(m, "u", "user", "hello", "s", "s")
        m = MessageTree.addNode(m, "a", "assistant", "", "s", "u")
        return m
    }

    @Test
    fun addNode_setsRootAndLinks() {
        val m = linear()
        assertEquals("s", MessageTree.getNode(m, "s")!!.root)
        assertEquals("s", MessageTree.getNode(m, "u")!!.root)
        assertEquals("s", MessageTree.getNode(m, "a")!!.root)
        // parent.child points to the newly added active child
        assertEquals("u", MessageTree.getNode(m, "s")!!.child)
        assertEquals("a", MessageTree.getNode(m, "u")!!.child)
        assertEquals("New Chat", MessageTree.getNode(m, "s")!!.metadata["title"])
    }

    @Test
    fun addNode_duplicateId_isNoOp() {
        val m = linear()
        val again = MessageTree.addNode(m, "u", "user", "dup", "s", "s")
        assertSame(m, again)
    }

    @Test
    fun getConversation_followsActiveChildChain() {
        val m = linear()
        val convo = MessageTree.getConversation(m, "s").map { it.id }
        assertEquals(listOf("s", "u", "a"), convo)
    }

    @Test
    fun getRoots_returnsSelfRootedNodes() {
        val m = linear()
        assertEquals(listOf("s"), MessageTree.getRoots(m).map { it.id })
    }

    @Test
    fun updateContent_appends() {
        var m = linear()
        m = MessageTree.updateContent(m, "a", { it + "Hi" })
        m = MessageTree.updateContent(m, "a", { it + " there" })
        assertEquals("Hi there", MessageTree.getNode(m, "a")!!.content)
    }

    @Test
    fun updateContent_noChange_isReferentialNoOp() {
        val m = linear()
        val same = MessageTree.updateContent(m, "a", { it })
        assertSame(m, same)
    }

    @Test
    fun branchNode_createsSiblingAndActivatesIt() {
        var m = linear()
        // Branch a sibling of the assistant "a" under user "u".
        m = MessageTree.branchNode(m, "a", "a2", "regenerated")
        val children = MessageTree.getChildren(m, "u").map { it.id }
        assertEquals(listOf("a", "a2"), children)
        // Parent's active child switches to the new branch.
        assertEquals("a2", MessageTree.getNode(m, "u")!!.child)
        // Conversation now follows the new branch.
        assertEquals(listOf("s", "u", "a2"), MessageTree.getConversation(m, "s").map { it.id })
    }

    @Test
    fun nextChild_lastChild_navigateSiblings() {
        var m = linear()
        m = MessageTree.branchNode(m, "a", "a2", "second")
        // Active is a2 (index 1). Move back to a.
        m = MessageTree.lastChild(m, "u")
        assertEquals("a", MessageTree.getNode(m, "u")!!.child)
        // Move forward to a2.
        m = MessageTree.nextChild(m, "u")
        assertEquals("a2", MessageTree.getNode(m, "u")!!.child)
        // No next beyond the last -> no-op.
        assertSame(m, MessageTree.nextChild(m, "u"))
    }

    @Test
    fun deleteNode_removesSubtreeAndRepointsParent() {
        var m = linear()
        m = MessageTree.branchNode(m, "a", "a2", "second")
        // Delete active branch a2 -> parent u should repoint to remaining child a.
        m = MessageTree.deleteNode(m, "a2")
        assertFalse(MessageTree.hasNode(m, "a2"))
        assertEquals("a", MessageTree.getNode(m, "u")!!.child)
    }

    @Test
    fun deleteNode_removesDescendants() {
        var m = linear()
        // Delete user "u" -> assistant "a" (its descendant) also removed.
        m = MessageTree.deleteNode(m, "u")
        assertFalse(MessageTree.hasNode(m, "u"))
        assertFalse(MessageTree.hasNode(m, "a"))
        assertNull(MessageTree.getNode(m, "s")!!.child)
    }

    @Test
    fun makeRoot_detachesAndRewritesRoot() {
        var m = linear()
        m = MessageTree.makeRoot(m, "u")
        val u = MessageTree.getNode(m, "u")!!
        assertNull(u.parent)
        assertEquals("u", u.root)
        // Descendant "a" inherits the new root.
        assertEquals("u", MessageTree.getNode(m, "a")!!.root)
        // Old parent no longer points at it.
        assertNull(MessageTree.getNode(m, "s")!!.child)
    }

    @Test
    fun getRootMapping_returnsConnectedSubtreeOnly() {
        var m = linear()
        // A second, unrelated root.
        m = MessageTree.addNode(m, "s2", "system", "other")
        val sub = MessageTree.getRootMapping(m, "s")
        assertTrue(sub.containsKey("s"))
        assertTrue(sub.containsKey("u"))
        assertTrue(sub.containsKey("a"))
        assertFalse(sub.containsKey("s2"))
    }
}
