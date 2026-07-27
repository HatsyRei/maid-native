package com.hatsyrei.maidnative.domain.tree

import com.hatsyrei.maidnative.domain.ConversationDefaults
import java.util.UUID

/**
 * Port of the RN app's `utilities/mappings.ts#validateMappings`. Cleans an
 * imported conversation map before it is merged into the store so that foreign
 * or malformed files can't corrupt the tree. Runs the same four passes as the
 * original (in order):
 *
 *  1. Drop non-root messages with a blank role/content, relinking the hole.
 *  2. Wrap any non-`system` root in a fresh `system` root.
 *  3. Give empty roots the default system prompt.
 *  4. Drop childless roots (a root with no conversation attached).
 *
 * Returns a new [LinkedHashMap]; the input is not mutated.
 */
fun validateMappings(input: Mappings): LinkedHashMap<String, MessageNode> {
    val map = LinkedHashMap(input)

    // 1. Remove invalid (blank) non-root messages, bridging parent <-> child.
    val invalid = map.values.filter {
        (it.role.isBlank() || it.content.isBlank()) && it.id != it.root
    }
    for (node in invalid) {
        map.remove(node.id)
        node.child?.let { c -> map[c]?.let { map[c] = it.copy(parent = node.parent) } }
        node.parent?.let { p -> map[p]?.let { map[p] = it.copy(child = node.child) } }
    }

    // 2. Wrap non-system roots in a new system root.
    for (root in MessageTree.getRoots(map).filter { it.role != "system" }) {
        val oldId = root.id
        val systemId = UUID.randomUUID().toString()
        val movedId = UUID.randomUUID().toString()
        val system = MessageNode(
            id = systemId,
            role = "system",
            content = ConversationDefaults.SYSTEM_PROMPT,
            root = systemId,
            child = movedId,
            metadata = mapOf("title" to ConversationDefaults.CHAT_TITLE),
        )
        map.remove(oldId)
        map[systemId] = system
        map[movedId] = root.copy(id = movedId, root = systemId, parent = systemId)
        // Re-point the rest of the (former) subtree at the new ids.
        for (k in map.keys.toList()) {
            val n = map[k] ?: continue
            if (n.id == systemId || n.id == movedId) continue
            var updated = n
            if (updated.root == oldId) updated = updated.copy(root = systemId)
            if (updated.parent == oldId) updated = updated.copy(parent = movedId)
            if (updated.child == oldId) updated = updated.copy(child = movedId)
            if (updated !== n) map[k] = updated
        }
    }

    // 3. Empty roots get the default system prompt.
    for (root in MessageTree.getRoots(map).filter { it.content.isBlank() }) {
        map[root.id] = root.copy(content = ConversationDefaults.SYSTEM_PROMPT)
    }

    // 4. Drop childless roots (empty conversations).
    for (root in MessageTree.getRoots(map).filter { it.child == null }) {
        map.remove(root.id)
    }

    return map
}
