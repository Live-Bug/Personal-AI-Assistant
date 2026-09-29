package com.aura.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: Memory): Long

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int = 10): List<Memory>

    @Query("SELECT * FROM memories WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getTodayMemories(sinceTimestamp: Long): List<Memory>

    @Query("""
        SELECT * FROM memories 
        WHERE userInput LIKE '%' || :query || '%' 
           OR aiResponse LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        LIMIT 5
    """)
    suspend fun searchMemories(query: String): List<Memory>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC LIMIT 5")
    suspend fun getMemoriesByCategory(category: String): List<Memory>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getCount(): Int
}
