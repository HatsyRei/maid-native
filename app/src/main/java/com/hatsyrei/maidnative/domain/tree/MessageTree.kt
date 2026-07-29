package com.hatsyrei.maidnative.domain.tree

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

    /**
     * Visit [start] and every descendant reachable via parent -> child links
     * (branch-aware), in DFS order. Backs sibling ordering with insertion order.
     */
    private inline fun forEachDescendant(
        mappings: Map<String, MessageNode>,
        start: String,
        action: (String) -> Unit,
    ) {
        val childrenByParent = LinkedHashMap<String, MutableList<String>>()
        for (node in mappings.values) {
            val parent = node.parent ?: continue
            if (!mappings.containsKey(parent)) continue
            childrenByParent.getOrPut(parent) { mutableListOf() }.add(node.id)
        }
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            action(id)
            childrenByParent[id]?.forEach { stack.addLast(it) }
        }
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

    /**
     * Returns a new map containing only [root] and all descendants connected to
     * it via parent -> child links (branch-aware), excluding unrelated roots.
     */
    fun getRootMapping(mappings: Mappings, root: String): Mappings {
        mappings[root] ?: return emptyMap()
        val out = LinkedHashMap<String, MessageNode>()
        forEachDescendant(mappings, root) { id ->
            mappings[id]?.let { out[id] = it }
        }
        return out
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
            val seen = mutableSetOf<String>()
            val parentId = draft[id]?.parent
            if (parentId != null && draft[parentId]?.child == id) {
                val replacement = draft.values.firstOrNull { it.parent == parentId && it.id != id }?.id
                draft[parentId] = draft.getValue(parentId).copy(child = replacement)
            }
            deleteNodeInternal(draft, id, seen)
        }
    }

    private fun deleteNodeInternal(
        draft: MutableMap<String, MessageNode>,
        id: String,
        seen: MutableSet<String>,
    ) {
        val node = draft[id] ?: return
        if (seen.contains(id)) return
        seen.add(id)
        val childIds = draft.values.filter { it.parent == id }.map { it.id }
        for (childId in childIds) {
            deleteNodeInternal(draft, childId, seen)
        }
        val parentId = node.parent
        if (parentId != null) {
            val parent = draft[parentId]
            if (parent?.child == id) {
                draft[parentId] = parent.copy(child = null)
            }
        }
        val activeChildId = node.child
        if (activeChildId != null) {
            val activeChild = draft[activeChildId]
            if (activeChild?.parent == id) {
                draft[activeChildId] = activeChild.copy(parent = null)
            }
        }
        draft.remove(id)
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
    ): Mappings {
        val node0 = mappings[id] ?: return mappings
        if (hasNode(mappings, sibling)) return mappings
        if (node0.parent != null && !mappings.containsKey(node0.parent)) return mappings
        return addNode(mappings, sibling, node0.role, content, node0.root, node0.parent, null, metadata)
    }

    fun updateContent(
        mappings: Mappings,
        id: String,
        content: (String) -> String,
        metadata: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
    ): Mappings {
        val node0 = mappings[id] ?: return mappings
        val newContent = content(node0.content)
        val newMetadata = metadata?.invoke(node0.metadata)
        val contentUnchanged = node0.content == newContent
        val metadataUnchanged = metadata == null || node0.metadata == newMetadata
        if (contentUnchanged && metadataUnchanged) return mappings
        return updateMap(mappings) { draft ->
            val node = draft[id] ?: return@updateMap
            draft[id] = node.copy(
                content = if (contentUnchanged) node.content else newContent,
                metadata = if (metadataUnchanged) node.metadata else (newMetadata ?: node.metadata),
            )
        }
    }

    /** Convenience overload for a plain string replacement. */
    fun setContent(mappings: Mappings, id: String, content: String): Mappings =
        updateContent(mappings, id, { content })

    fun makeRoot(mappings: Mappings, id: String): Mappings {
        val node0 = mappings[id] ?: return mappings
        return updateMap(mappings) { draft ->
            val node = draft[id] ?: return@updateMap
            if (node.parent != null) {
                val pId = node.parent
                val parent = draft[pId]
                if (parent?.child == id) {
                    draft[pId] = parent.copy(child = null)
                }
                draft[id] = draft.getValue(id).copy(parent = null)
            }
            // Rewrite root on this node + all descendants (branch-aware).
            forEachDescendant(draft, id) { curId ->
                draft[curId]?.let { draft[curId] = it.copy(root = id) }
            }
        }
    }
}
