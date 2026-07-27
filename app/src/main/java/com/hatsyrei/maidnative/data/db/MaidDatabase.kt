package com.hatsyrei.maidnative.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class MaidDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: MaidDatabase? = null

        fun get(context: Context): MaidDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MaidDatabase::class.java,
                "maid.db",
            )
                // WAL: fewer fsyncs per write, better battery under frequent
                // small incremental updates.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
