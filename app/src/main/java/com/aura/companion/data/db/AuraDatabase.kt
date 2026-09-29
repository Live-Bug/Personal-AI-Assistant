package com.aura.companion.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Memory::class, Task::class, ConversationSegment::class],
    version = 2,
    exportSchema = false
)
abstract class AuraDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao
    abstract fun conversationSegmentDao(): ConversationSegmentDao

    companion object {
        @Volatile
        private var INSTANCE: AuraDatabase? = null

        fun getDatabase(context: Context): AuraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
