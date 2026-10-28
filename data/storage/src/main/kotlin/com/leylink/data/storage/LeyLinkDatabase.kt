package com.leylink.data.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OutboxRow::class, OutboxSentRow::class, SeenRow::class, MessageRow::class],
    version = 1,
    exportSchema = true,
)
abstract class LeyLinkDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    abstract fun seenDao(): SeenDao

    abstract fun messageDao(): MessageDao

    companion object {
        fun create(context: Context): LeyLinkDatabase =
            Room.databaseBuilder(context.applicationContext, LeyLinkDatabase::class.java, "leylink.db")
                // Write-ahead logging: crash-safe commits, readers never block the writer.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
