package com.aura.companion.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

enum class ConversationStatus {
    OPEN,        // still being recorded
    PENDING,     // closed, waiting for Gemma
    PROCESSING,  // Gemma is working on it
    DONE,        // summarized
    DISCARDED,   // nothing worth keeping (small talk, noise)
    FAILED       // processing error; transcript is still kept
}

/**
 * One stretch of continuous talk, closed after a long silence. Its raw lines live in
 * [ConversationSegment]; Gemma fills in [title] and [summary] once it is closed.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val lastSpeechAt: Long = System.currentTimeMillis(),
    val status: ConversationStatus = ConversationStatus.OPEN,
    val title: String = "",
    val summary: String = "",
    val taskCount: Int = 0,
    val memoryCount: Int = 0
)

@Fts4(contentEntity = Conversation::class)
@Entity(tableName = "conversations_fts")
data class ConversationFts(
    val title: String,
    val summary: String
)
