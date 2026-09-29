package com.aura.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationSegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: ConversationSegment): Long

    @Query("SELECT * FROM conversation_segments ORDER BY timestamp DESC")
    fun getAllSegments(): Flow<List<ConversationSegment>>

    @Query("SELECT * FROM conversation_segments WHERE isSummarized = 0 ORDER BY timestamp ASC")
    suspend fun getUnsummarizedSegments(): List<ConversationSegment>

    @Query("""
        SELECT * FROM conversation_segments 
        WHERE timestamp >= :startOfDay 
        ORDER BY timestamp ASC
    """)
    suspend fun getTodaySegments(startOfDay: Long): List<ConversationSegment>

    @Query("SELECT * FROM conversation_segments ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSegments(limit: Int = 20): List<ConversationSegment>

    @Query("UPDATE conversation_segments SET isSummarized = 1 WHERE id IN (:ids)")
    suspend fun markAsSummarized(ids: List<Long>)

    @Query("DELETE FROM conversation_segments WHERE isSummarized = 1 AND timestamp < :before")
    suspend fun deleteOldSummarized(before: Long)

    @Query("DELETE FROM conversation_segments")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversation_segments WHERE timestamp >= :startOfDay")
    suspend fun getTodayCount(startOfDay: Long): Int
}
