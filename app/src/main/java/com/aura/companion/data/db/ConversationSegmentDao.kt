package com.aura.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationSegmentDao {

    @Insert
    suspend fun insert(segment: ConversationSegment): Long

    @Query("SELECT * FROM conversation_segments WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getForConversation(conversationId: Long): List<ConversationSegment>

    @Query("SELECT * FROM conversation_segments WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: Long): Flow<List<ConversationSegment>>

}
