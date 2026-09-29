package com.aura.companion.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

enum class MemorySource {
    CONVERSATION, // Gemma picked it out of a recorded conversation
    USER          // the user explicitly asked Aura to remember it
}

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val source: MemorySource,
    val conversationId: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// Full-text index kept in sync with `memories` by Room-generated triggers
@Fts4(contentEntity = Memory::class)
@Entity(tableName = "memories_fts")
data class MemoryFts(
    val content: String
)
