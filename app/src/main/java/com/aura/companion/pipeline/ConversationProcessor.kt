package com.aura.companion.pipeline

import android.util.Log
import com.aura.companion.ai.ConversationInsights
import com.aura.companion.ai.GemmaManager
import com.aura.companion.data.db.AuraDatabase
import com.aura.companion.data.db.Conversation
import com.aura.companion.data.db.ConversationStatus
import com.aura.companion.data.db.Memory
import com.aura.companion.data.db.MemorySource
import com.aura.companion.data.db.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Batch step of the pipeline: turns each closed conversation into a title, summary, tasks and
 * memories with one Gemma call per chunk. Runs one conversation at a time; Gemma loads on the
 * first request and unloads itself once the queue has been idle for a while.
 */
class ConversationProcessor(
    db: AuraDatabase,
    private val gemma: GemmaManager,
    scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConversationProcessor"
        // Fewer words than this is noise or a passing remark; not worth a model call
        private const val MIN_WORDS = 8
    }

    private val conversationDao = db.conversationDao()
    private val segmentDao = db.conversationSegmentDao()
    private val taskDao = db.taskDao()
    private val memoryDao = db.memoryDao()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wakeUp) drainQueue()
        }
    }

    /** Asks the processor to look for pending conversations. */
    fun trigger() {
        wakeUp.trySend(Unit)
    }

    suspend fun retry(conversationId: Long) {
        conversationDao.setStatus(conversationId, ConversationStatus.PENDING)
        trigger()
    }

    private suspend fun drainQueue() {
        while (true) {
            val next = conversationDao.nextPending() ?: return
            if (!gemma.hasModelFile()) {
                Log.w(TAG, "No model file; leaving conversations queued")
                return
            }
            _busy.value = true
            try {
                process(next)
            } catch (e: Exception) {
                Log.e(TAG, "Processing conversation ${next.id} failed", e)
                conversationDao.setStatus(next.id, ConversationStatus.FAILED)
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun process(conversation: Conversation) {
        conversationDao.setStatus(conversation.id, ConversationStatus.PROCESSING)
        val segments = segmentDao.getForConversation(conversation.id)
        val wordCount = segments.sumOf { segment -> segment.text.split(Regex("\\s+")).count { it.isNotBlank() } }
        if (wordCount < MIN_WORDS) {
            Log.i(TAG, "Conversation ${conversation.id}: only $wordCount words, discarding")
            conversationDao.saveResult(conversation.id, ConversationStatus.DISCARDED, "", "", 0, 0)
            return
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = segments.map { "[${timeFormat.format(Date(it.timestamp))}] ${it.text}" }
        val chunks = chunk(lines, GemmaManager.MAX_TRANSCRIPT_CHARS)
        Log.i(TAG, "Conversation ${conversation.id}: $wordCount words in ${chunks.size} chunk(s)")

        val results = mutableListOf<ConversationInsights>()
        for ((index, text) in chunks.withIndex()) {
            val part = if (chunks.size > 1) "${index + 1} of ${chunks.size}" else null
            val insights = gemma.analyzeConversation(text, part)
            if (insights == null) {
                conversationDao.setStatus(conversation.id, ConversationStatus.FAILED)
                return
            }
            results.add(insights)
        }

        val useful = results.filter { !it.isEmpty }
        if (useful.isEmpty()) {
            Log.i(TAG, "Conversation ${conversation.id}: nothing worth keeping")
            conversationDao.saveResult(conversation.id, ConversationStatus.DISCARDED, "", "", 0, 0)
            return
        }

        val (title, summary) = if (useful.size == 1) {
            Pair(useful[0].title, useful[0].summary)
        } else {
            gemma.mergeSummaries(useful)
                ?: Pair(useful[0].title, useful.joinToString(" ") { it.summary })
        }

        val tasks = useful.flatMap { it.actionItems }.distinctBy { it.first.lowercase() }
        for ((taskTitle, due) in tasks) {
            taskDao.insert(Task(title = taskTitle, dueTime = due, conversationId = conversation.id))
        }
        val memories = useful.flatMap { it.memories }.distinctBy { it.lowercase() }
        for (content in memories) {
            memoryDao.insert(
                Memory(content = content, source = MemorySource.CONVERSATION, conversationId = conversation.id)
            )
        }

        conversationDao.saveResult(
            conversation.id, ConversationStatus.DONE,
            title.ifBlank { "Conversation" }, summary, tasks.size, memories.size
        )
        Log.i(TAG, "Conversation ${conversation.id}: \"$title\", ${tasks.size} tasks, ${memories.size} memories")
    }

    // Splits on line boundaries so no utterance is cut in half
    private fun chunk(lines: List<String>, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + line.length + 1 > maxChars) {
                chunks.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line.take(maxChars))
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}
