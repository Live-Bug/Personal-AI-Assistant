package com.aura.companion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a raw transcription segment captured during always-listening mode.
 * Multiple segments get summarized into a Memory periodically.
 */
@Entity(tableName = "conversation_segments")
data class ConversationSegment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSummarized: Boolean = false  // true after it's been rolled into a Memory
)
