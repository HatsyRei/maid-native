package com.hatsyrei.maidnative.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface MessageDao {

    /** All rows in original insertion order (see [MessageEntity]). */
    @Query("SELECT * FROM messages ORDER BY rowid")
    suspend fun getAll(): List<MessageEntity>

    @Upsert
    suspend fun upsert(rows: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Apply an incremental diff in a single transaction: upsert only the
     * changed/new rows and delete only the vanished ids. Unchanged rows are
     * never touched.
     */
    @Transaction
    suspend fun applyDiff(upserts: List<MessageEntity>, deletes: List<String>) {
        if (upserts.isNotEmpty()) upsert(upserts)
        // SQLite before API 30 binds at most 999 variables per statement.
        for (chunk in deletes.chunked(MAX_BIND_ARGS)) deleteByIds(chunk)
    }
}

private const val MAX_BIND_ARGS = 999
