package com.hatsyrei.maidnative.data.db

import com.hatsyrei.maidnative.data.store.MessageStore
import com.hatsyrei.maidnative.domain.tree.Mappings
import com.hatsyrei.maidnative.domain.tree.MessageNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Room-backed persistence for the conversation tree with **incremental diff
 * writes**: on save, only nodes that were added or changed are upserted and
 * only ids that vanished are deleted — the whole table is never rewritten.
 *
 * A snapshot of the last-persisted state ([lastPersisted]) is diffed against
 * each incoming save. If nothing changed the DB is not touched at all, so
 * writes happen only when strictly necessary.
 *
 * Callers must serialize `save` invocations (e.g. via a conflated channel with
 * a single consumer). This class keeps mutable diff state and is not designed
 * for concurrent writers.
 */
class MessageRepository(private val dao: MessageDao) {

    /** The exact set of nodes currently on disk, keyed by id. */
    private var lastPersisted: Map<String, MessageNode> = emptyMap()

    /**
     * Load the persisted mappings, one-time migrating a legacy
     * `messages.json` snapshot ([legacyFile]) into Room if the DB is empty.
     */
    suspend fun load(legacyFile: File? = null): Mappings = withContext(Dispatchers.IO) {
        var rows = dao.getAll()
        if (rows.isEmpty() && legacyFile != null && legacyFile.exists()) {
            migrateLegacy(legacyFile)
            rows = dao.getAll()
        }
        val map = LinkedHashMap<String, MessageNode>(rows.size)
        for (row in rows) map[row.id] = row.toNode()
        lastPersisted = map
        map
    }

    /**
     * Persist [current] by writing only the delta against the last save.
     * Returns true if the DB was touched, false if it was a no-op.
     */
    suspend fun save(current: Mappings): Boolean = withContext(Dispatchers.IO) {
        val upserts = ArrayList<MessageEntity>()
        for ((id, node) in current) {
            val prev = lastPersisted[id]
            if (prev == null || prev != node) upserts.add(MessageEntity.from(node))
        }
        val deletes = ArrayList<String>()
        for (id in lastPersisted.keys) if (!current.containsKey(id)) deletes.add(id)

        if (upserts.isEmpty() && deletes.isEmpty()) return@withContext false

        dao.applyDiff(upserts, deletes)
        // Snapshot the persisted state. Nodes are immutable value types, so
        // holding references is safe.
        lastPersisted = LinkedHashMap(current)
        true
    }

    /** Seed Room from the interim JSON snapshot, then retire the file. */
    private suspend fun migrateLegacy(legacyFile: File) {
        runCatching {
            val text = legacyFile.readText()
            if (text.isBlank()) return
            val nodes = MessageStore.decodeNodes(org.json.JSONArray(text))
            if (nodes.isNotEmpty()) {
                dao.upsert(nodes.map { MessageEntity.from(it) })
            }
        }
        // Best-effort cleanup: remove the legacy file so migration runs once.
        runCatching { legacyFile.delete() }
    }
}
