package com.aura.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: Memory): Long

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 5): List<Memory>

    // `query` is an FTS4 MATCH expression, built by FtsQuery
    @Query("""
        SELECT memories.* FROM memories
        JOIN memories_fts ON memories.rowid = memories_fts.rowid
        WHERE memories_fts MATCH :query
        ORDER BY memories.timestamp DESC LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 5): List<Memory>

    @Query("""
        SELECT memories.* FROM memories
        JOIN memories_fts ON memories.rowid = memories_fts.rowid
        WHERE memories_fts MATCH :query
        ORDER BY memories.timestamp DESC
    """)
    fun observeSearch(query: String): Flow<List<Memory>>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
