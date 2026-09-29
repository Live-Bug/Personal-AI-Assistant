package com.aura.companion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.companion.AuraApplication
import com.aura.companion.data.db.ConversationSegment
import com.aura.companion.data.db.FtsQuery
import com.aura.companion.data.db.Memory
import com.aura.companion.data.db.Task
import com.aura.companion.service.AuraForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class AuraUiState(
    val chat: List<ChatMessage> = emptyList(),
    val thinking: Boolean = false,
    val message: String? = null // one-off snackbar text
)

@OptIn(ExperimentalCoroutinesApi::class)
class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AuraApplication
    private val db = app.database
    private val capture = app.capture
    private val gemma = app.gemma
    private val assistant = app.assistant

    private val _uiState = MutableStateFlow(AuraUiState())
    val uiState: StateFlow<AuraUiState> = _uiState.asStateFlow()

    // Pipeline status
    val captureState = capture.state
    val captureError = capture.error
    val hearingSpeech = capture.hearingSpeech
    val processing = app.processor.busy
    val backlog = db.conversationDao().observeBacklog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Model status
    val gemmaState = gemma.state
    val activeBackend = gemma.activeBackend
    val gemmaError = gemma.lastError
    private val _modelFileName = MutableStateFlow(gemma.modelFileName())
    val modelFileName: StateFlow<String?> = _modelFileName

    // Online boundary
    val onlineActive = app.online.active
    val networkLogs = app.online.callLogs

    // Data
    val conversations = db.conversationDao().observeRecent()
    val openConversation = db.conversationDao().observeOpen()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val openTranscript: Flow<List<ConversationSegment>> = openConversation.flatMapLatest { open ->
        if (open == null) flowOf(emptyList()) else db.conversationSegmentDao().observeForConversation(open.id)
    }
    val tasks = db.taskDao().getAllTasks()

    private val _memoryQuery = MutableStateFlow("")
    val memoryQuery: StateFlow<String> = _memoryQuery
    val memories: Flow<List<Memory>> = _memoryQuery.flatMapLatest { query ->
        if (query.isBlank()) db.memoryDao().getAllMemories()
        else FtsQuery.from(query)?.let { db.memoryDao().observeSearch(it) } ?: flowOf(emptyList())
    }

    private var nextMessageId = 0L

    init {
        app.tracker // make sure leftover conversations get processed
    }

    // ============ LISTENING (bottom-bar mic) ============

    /** Call once microphone permission is granted, while the app is visible. */
    fun startListening() = AuraForegroundService.start(app)

    fun stopListening() = AuraForegroundService.stop(app)

    // ============ CHAT ============

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.thinking) return
        viewModelScope.launch { ask(text.trim()) }
    }

    fun requestDaySummary() {
        if (_uiState.value.thinking) return
        viewModelScope.launch {
            addChat("Summarize my day", fromUser = true)
            _uiState.update { it.copy(thinking = true) }
            val summary = try {
                assistant.daySummary()
            } finally {
                _uiState.update { it.copy(thinking = false) }
            }
            addChat(summary, fromUser = false)
        }
    }

    private suspend fun ask(text: String) {
        addChat(text, fromUser = true)
        _uiState.update { it.copy(thinking = true) }
        val reply = try {
            assistant.ask(text)
        } catch (e: Exception) {
            Log.e("AuraViewModel", "Ask failed: ${e.message}")
            "Something went wrong. Please try again."
        } finally {
            _uiState.update { it.copy(thinking = false) }
        }
        addChat(reply, fromUser = false)
    }

    private fun addChat(text: String, fromUser: Boolean) {
        _uiState.update {
            it.copy(chat = (it.chat + ChatMessage(nextMessageId++, text, fromUser)).takeLast(100))
        }
    }

    fun showMessage(text: String) = _uiState.update { it.copy(message = text) }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    // ============ MODEL ============

    fun loadModel(customPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gemma.load(customPath)
                app.processor.trigger() // anything queued while there was no model
            } catch (e: Exception) {
                showMessage(e.message ?: "Couldn't load the model")
            }
            _modelFileName.value = gemma.modelFileName()
        }
    }

    // ============ CONVERSATIONS ============

    fun transcript(conversationId: Long): Flow<List<ConversationSegment>> =
        db.conversationSegmentDao().observeForConversation(conversationId)

    fun retryConversation(conversationId: Long) {
        viewModelScope.launch { app.processor.retry(conversationId) }
    }

    // ============ TASKS ============

    fun toggleTaskComplete(task: Task) {
        viewModelScope.launch { db.taskDao().setCompleted(task.id, !task.isCompleted) }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch { db.taskDao().deleteById(taskId) }
    }

    fun addTaskManually(title: String, dueTime: String = "") {
        viewModelScope.launch { db.taskDao().insert(Task(title = title.trim(), dueTime = dueTime.trim())) }
    }

    fun clearCompletedTasks() {
        viewModelScope.launch { db.taskDao().deleteCompleted() }
    }

    // ============ MEMORIES ============

    fun setMemoryQuery(query: String) {
        _memoryQuery.value = query
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch { db.memoryDao().deleteById(memoryId) }
    }

    fun clearAllMemory() {
        viewModelScope.launch { db.memoryDao().deleteAll() }
    }

    fun clearNetworkLogs() = app.online.clearLogs()
}
