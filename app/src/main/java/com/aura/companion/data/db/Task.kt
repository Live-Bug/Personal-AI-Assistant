package com.aura.companion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val isCompleted: Boolean = false,
    val priority: Int = 1,           // 1=low, 2=medium, 3=high
    val dueTime: String = "",        // e.g. "3pm", "tomorrow"
    val conversationId: Long? = null, // set when extracted from a recorded conversation
    val timestamp: Long = System.currentTimeMillis()
)
