package com.aura.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    @Query("SELECT * FROM conversations WHERE status = 'OPEN' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpen(): Conversation?

    @Query("SELECT * FROM conversations WHERE status = 'OPEN' ORDER BY startedAt DESC LIMIT 1")
    fun observeOpen(): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE status = 'PENDING' ORDER BY startedAt ASC LIMIT 1")
    suspend fun nextPending(): Conversation?

    @Query("SELECT COUNT(*) FROM conversations WHERE status IN ('PENDING', 'PROCESSING')")
    fun observeBacklog(): Flow<Int>

    @Query("SELECT * FROM conversations WHERE status != 'OPEN' ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE status = 'DONE' AND startedAt >= :since ORDER BY startedAt ASC")
    suspend fun getSummarizedSince(since: Long): List<Conversation>

    @Query("""
        SELECT conversations.* FROM conversations
        JOIN conversations_fts ON conversations.rowid = conversations_fts.rowid
        WHERE conversations_fts MATCH :query AND conversations.status = 'DONE'
        ORDER BY conversations.startedAt DESC LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 3): List<Conversation>

    @Query("UPDATE conversations SET lastSpeechAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE conversations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ConversationStatus)

    @Query("UPDATE conversations SET status = 'PENDING' WHERE status IN ('OPEN', 'PROCESSING')")
    suspend fun requeueInterrupted()

    @Query("""
        UPDATE conversations SET status = :status, title = :title, summary = :summary,
        taskCount = :taskCount, memoryCount = :memoryCount WHERE id = :id
    """)
    suspend fun saveResult(
        id: Long, status: ConversationStatus, title: String, summary: String,
        taskCount: Int, memoryCount: Int
    )

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
