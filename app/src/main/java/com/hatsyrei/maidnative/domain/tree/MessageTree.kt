package com.hatsyrei.maidnative.domain.tree

import com.hatsyrei.maidnative.domain.Attachment

/**
 * Pure tree operations ported from the `message-nodes` npm package (dist/index.js).
 *
 * Semantics preserved from the JS original:
 * - Maps are treated as immutable; every mutator returns a NEW map, or the SAME
 *   instance when there is no change (referential-equality no-op guard).
 * - Iteration order matters (JS `Object.values` = insertion order). We back
 *   every returned map with a [LinkedHashMap] so `getChildren`, sibling
 *   navigation, and delete's "first other child" pick match the JS behavior.
 */
object MessageTree {

    private fun copyOf(mappings: Mappings): LinkedHashMap<String, MessageNode> =
        LinkedHashMap(mappings)

    private inline fun updateMap(
        mappings: Mappings,
        fn: (MutableMap<String, MessageNode>) -> Unit,
    ): Mappings {
        val next = copyOf(mappings)
        fn(next)
        return next
    }

    /** parent id -> child ids in insertion order; nodes with an absent parent are skipped. */
    private fun childIndex(mappings: Map<String, MessageNode>): Map<String, List<String>> {
        val index = LinkedHashMap<String, MutableList<String>>()
        for (node in mappings.values) {
            val parent = node.parent ?: continue
            if (!mappings.containsKey(parent)) continue
            index.getOrPut(parent) { mutableListOf() }.add(node.id)
        }
        return index
    }

    /**
     * Ids of [start] and every descendant reachable via parent -> child links
     * (branch-aware), in DFS order. Backs sibling ordering with insertion order.
     */
    private fun descendants(mappings: Map<String, MessageNode>, start: String): Set<String> {
        val childrenByParent = childIndex(mappings)
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            childrenByParent[id]?.forEach { stack.addLast(it) }
        }
        return seen
    }

    fun hasNode(mappings: Mappings, id: String): Boolean = mappings.containsKey(id)

    fun getNode(mappings: Mappings, id: String): MessageNode? = mappings[id]

    fun getRoot(mappings: Mappings, id: String): MessageNode? {
        var current = mappings[id] ?: return null
        while (current.parent != null && mappings[current.parent] != null) {
            current = mappings[current.parent]!!
        }
        return current
    }

    fun getRoots(mappings: Mappings): List<MessageNode> =
        mappings.values.filter { it.root == it.id }

    fun getConversation(mappings: Mappings, root: String): List<MessageNode> {
        val rootNode = getNode(mappings, root) ?: return emptyList()
        val conversation = mutableListOf(rootNode)
        val seen = mutableSetOf(rootNode.id)
        var currentId = rootNode.child
        while (currentId != null) {
            val current = getNode(mappings, currentId) ?: break
            if (seen.contains(current.id)) break
            seen.add(current.id)
            conversation.add(current)
            currentId = current.child
        }
        return conversation
    }

    fun getChildren(mappings: Mappings, id: String): List<MessageNode> =
        mappings.values.filter { it.parent == id }

    fun nextChild(mappings: Mappings, parent: String): Mappings =
        shiftChild(mappings, parent, delta = 1)

    fun lastChild(mappings: Mappings, parent: String): Mappings =
        shiftChild(mappings, parent, delta = -1)

    /**
     * Move [parent]'s active-child pointer [delta] places through its children,
     * in insertion order. No-op when the pointer is unset, absent, or the move
     * would run off either end.
     */
    private fun shiftChild(mappings: Mappings, parent: String, delta: Int): Mappings {
        val parentNode = mappings[parent] ?: return mappings
        val children = getChildren(mappings, parent)
        val index = children.indexOfFirst { it.id == parentNode.child }
        if (index == -1) return mappings
        val target = index + delta
        if (target !in children.indices) return mappings
        return setChild(mappings, parent, children[target].id)
    }

    fun setChild(mappings: Mappings, parent: String, child: String?): Mappings {
        val p0 = mappings[parent] ?: return mappings
        if (child != null) {
            val c0 = mappings[child]
            if (c0 == null || c0.parent != parent) return mappings
        }
        if (p0.child == child) return mappings
        return updateMap(mappings) { draft ->
            draft[parent] = draft.getValue(parent).copy(child = child)
        }
    }

    fun deleteNode(mappings: Mappings, id: String): Mappings {
        if (!mappings.containsKey(id)) return mappings
        return updateMap(mappings) { draft ->
            val parentId = draft[id]?.parent
            if (parentId != null && draft[parentId]?.child == id) {
                val replacement = draft.values.firstOrNull { it.parent == parentId && it.id != id }?.id
                draft[parentId] = draft.getValue(parentId).copy(child = replacement)
            }
            // One shared parent -> children index and an explicit stack. The
            // previous shape rescanned every node in the store for each node it
            // removed (quadratic in the conversation) and recursed once per
            // generation, which a long linear thread turns into an equally deep
            // call stack.
            val doomed = descendants(draft, id)
            for (doomedId in doomed) {
                val node = draft.remove(doomedId) ?: continue
                // A surviving node that still names a removed one as its parent
                // can only come from a malformed graph, but leaving the pointer
                // dangling would strand it outside every conversation.
                val activeChildId = node.child ?: continue
                if (activeChildId in doomed) continue
                val activeChild = draft[activeChildId]
                if (activeChild?.parent == doomedId) {
                    draft[activeChildId] = activeChild.copy(parent = null)
                }
            }
        }
    }

    fun addNode(
        mappings: Mappings,
        id: String,
        role: String,
        content: String,
        root: String? = null,
        parent: String? = null,
        child: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        attachments: List<Attachment> = emptyList(),
    ): Mappings {
        if (hasNode(mappings, id)) return mappings

        val parentNode = parent?.let { mappings[it] }
        if (parent != null && parentNode == null) return mappings
        val childNode = child?.let { mappings[it] }
        if (child != null && childNode == null) return mappings

        val resolvedRoot: String = if (parent == null) {
            id
        } else {
            getRoot(mappings, parent)?.id ?: parent
        }
        if (!hasNode(mappings, resolvedRoot) && resolvedRoot != id) return mappings

        return updateMap(mappings) { draft ->
            draft[id] = MessageNode(
                id = id,
                role = role,
                content = content,
                root = resolvedRoot,
                parent = parent,
                child = child,
                metadata = metadata,
                attachments = attachments,
            )
            if (parent != null) {
                draft[parent]?.let { p ->
                    if (draft.getValue(id).parent == parent) {
                        draft[parent] = p.copy(child = id)
                    }
                }
            }
            if (child != null) {
                draft[child]?.let { c ->
                    draft[child] = c.copy(parent = id)
                }
            }
        }
    }

    fun branchNode(
        mappings: Mappings,
        id: String,
        sibling: String,
        content: String,
        metadata: Map<String, Any?> = emptyMap(),
        attachments: List<Attachment> = emptyList(),
    ): Mappings {
        val node0 = mappings[id] ?: return mappings
        if (hasNode(mappings, sibling)) return mappings
        if (node0.parent != null && !mappings.containsKey(node0.parent)) return mappings
        return addNode(
            mappings, sibling, node0.role, content, node0.root, node0.parent, null, metadata, attachments,
        )
    }

    /** A null [metadata] or [attachments] leaves that part of the node as it was. */
    fun updateContent(
        mappings: Mappings,
        id: String,
        content: (String) -> String,
        metadata: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
        attachments: List<Attachment>? = null,
    ): Mappings {
        val node0 = mappings[id] ?: return mappings
        val newContent = content(node0.content)
        val newMetadata = metadata?.invoke(node0.metadata)
        val contentUnchanged = node0.content == newContent
        val metadataUnchanged = metadata == null || node0.metadata == newMetadata
        val attachmentsUnchanged = attachments == null || node0.attachments == attachments
        if (contentUnchanged && metadataUnchanged && attachmentsUnchanged) return mappings
        return updateMap(mappings) { draft ->
            val node = draft[id] ?: return@updateMap
            draft[id] = node.copy(
                content = if (contentUnchanged) node.content else newContent,
                metadata = if (metadataUnchanged) node.metadata else (newMetadata ?: node.metadata),
                attachments = attachments ?: node.attachments,
            )
        }
    }

    /** Convenience overload for a plain string replacement. */
    fun setContent(mappings: Mappings, id: String, content: String): Mappings =
        updateContent(mappings, id, { content })
}
