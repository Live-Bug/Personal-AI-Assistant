package com.aura.companion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userInput: String,
    val aiResponse: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "general", // general, task, weather, news
    val tags: String = ""             // comma-separated tags for search
)
