package com.aura.companion.pipeline

import android.util.Log
import com.aura.companion.capture.CaptureController
import com.aura.companion.capture.Utterance
import com.aura.companion.data.db.AuraDatabase
import com.aura.companion.data.db.Conversation
import com.aura.companion.data.db.ConversationSegment
import com.aura.companion.data.db.ConversationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Groups always-on utterances into conversations. A conversation closes after [GAP_MS] of
 * silence, when capture is paused, or after [MAX_DURATION_MS]; closed conversations are handed
 * to the [ConversationProcessor] via [onClosed].
 */
class ConversationTracker(
    db: AuraDatabase,
    capture: CaptureController,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit
) {
    companion object {
        private const val TAG = "ConversationTracker"
        const val GAP_MS = 2 * 60_000L
        // Caps a conversation that never pauses (e.g. a TV left on) so it still gets processed
        private const val MAX_DURATION_MS = 60 * 60_000L
    }

    private val conversationDao = db.conversationDao()
    private val segmentDao = db.conversationSegmentDao()
    private val mutex = Mutex()
    private var gapJob: Job? = null

    init {
        scope.launch {
            // Anything left open or mid-processing by a previous process gets (re)processed
            mutex.withLock { conversationDao.requeueInterrupted() }
            onClosed()
        }
        scope.launch {
            capture.utterances.collect { utterance ->
                try {
                    add(utterance)
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't store utterance", e)
                }
            }
        }
        scope.launch { capture.sessionEnded.collect { closeOpen("capture paused") } }
    }

    private suspend fun add(utterance: Utterance) = mutex.withLock {
        var open = conversationDao.getOpen()
        // The gap timer normally closes these; this catches it if the timer was delayed
        if (open != null && utterance.startedAt - open.lastSpeechAt > GAP_MS) {
            close(open, "silence")
            open = null
        }
        if (open != null && utterance.endedAt - open.startedAt > MAX_DURATION_MS) {
            close(open, "max duration")
            open = null
        }
        val conversationId = open?.id ?: conversationDao.insert(
            Conversation(startedAt = utterance.startedAt, lastSpeechAt = utterance.endedAt)
        )
        segmentDao.insert(
            ConversationSegment(
                conversationId = conversationId,
                text = utterance.text,
                timestamp = utterance.startedAt
            )
        )
        conversationDao.touch(conversationId, utterance.endedAt)

        gapJob?.cancel()
        gapJob = scope.launch {
            delay(GAP_MS)
            closeOpen("silence")
        }
    }

    private suspend fun closeOpen(reason: String) = mutex.withLock {
        conversationDao.getOpen()?.let { close(it, reason) }
    }

    // Caller must hold mutex
    private suspend fun close(conversation: Conversation, reason: String) {
        Log.i(TAG, "Closing conversation ${conversation.id} ($reason)")
        conversationDao.setStatus(conversation.id, ConversationStatus.PENDING)
        onClosed()
    }
}
