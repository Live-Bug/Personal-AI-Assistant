package com.aura.companion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.companion.AuraApplication
import com.aura.companion.ai.GemmaManager
import com.aura.companion.ai.GemmaState
import com.aura.companion.ai.NetworkCallLog
import com.aura.companion.ai.OnlineLookupManager
import com.aura.companion.ai.OnlineQueryType
import com.aura.companion.audio.SpeechManager
import com.aura.companion.audio.SpeechState
import com.aura.companion.audio.TTSManager
import com.aura.companion.data.db.ConversationSegment
import com.aura.companion.data.db.Memory
import com.aura.companion.data.db.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a transcript entry shown in the live feed.
 */
data class TranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: TranscriptType = TranscriptType.USER_SPEECH,
    val summary: String? = null
)


enum class TranscriptType {
    USER_SPEECH,      // Raw transcription from always-listening
    AI_RESPONSE,      // Aura's response to a query
    AI_SUMMARY,       // Periodic summary of conversation
    SYSTEM_MESSAGE    // Status updates
}

data class AuraUiState(
    val isListening: Boolean = false,
    val isMuted: Boolean = false,
    val isProcessing: Boolean = false,
    val isSpeaking: Boolean = false,
    val isModelLoading: Boolean = true,
    val isModelReady: Boolean = false,
    val modelError: String = "",
    val activeModelPath: String = "",
    val partialSpeech: String = "",
    val statusText: String = "Initializing Aura...",
    val onlineCallActive: Boolean = false,
    val isQueryMode: Boolean = false,           // true when user is asking a question
    val transcriptEntries: List<TranscriptEntry> = emptyList(),
    val todaySegmentCount: Int = 0
)

class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as AuraApplication).database
    private val memoryDao = db.memoryDao()
    private val taskDao = db.taskDao()
    private val segmentDao = db.conversationSegmentDao()

    private val speechManager = SpeechManager(application)
    private val ttsManager = TTSManager(application)
    private val gemmaManager = GemmaManager(application)
    private val onlineLookup = OnlineLookupManager()

    private val _uiState = MutableStateFlow(AuraUiState())
    val uiState: StateFlow<AuraUiState> = _uiState.asStateFlow()

    val memories = memoryDao.getAllMemories()
    val tasks = taskDao.getAllTasks()
    val segments = segmentDao.getAllSegments()

    val networkLogs: List<NetworkCallLog> get() = onlineLookup.callLogs

    companion object {
        private const val MAX_BLOCK_DURATION_MS = 60_000L // 60s max per block
        private const val SILENCE_TIMEOUT_MS = 30_000L    // 30s silence timeout
        private const val SUMMARY_THRESHOLD = 5
    }

    private var unsummarizedCount = 0

    // Dual-trigger speech block state (60s max cap OR 30s silence)
    private var blockStartTimestamp: Long = 0L
    private var lastSpeechTimestamp: Long = 0L
    private var activeSpeechEntryId: String? = null
    private val currentBlockTextBuilder = java.lang.StringBuilder()
    private var maxBlockDurationJob: Job? = null
    private var silenceWatchdogJob: Job? = null
    private val processedTaskSignatures = mutableSetOf<String>()



    init {
        initializeManagers()
    }

    private fun initializeManagers() {
        speechManager.initialize()
        ttsManager.initialize()

        // Observe speech state
        viewModelScope.launch {
            speechManager.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    isListening = state == SpeechState.LISTENING,
                    isProcessing = state == SpeechState.PROCESSING
                )
            }
        }

        // Observe partial speech
        viewModelScope.launch {
            speechManager.recognizedText.collect { text ->
                _uiState.value = _uiState.value.copy(partialSpeech = text)
            }
        }

        // Observe TTS — pause listening while Aura speaks to avoid hearing herself
        viewModelScope.launch {
            ttsManager.isSpeaking.collect { speaking ->
                _uiState.value = _uiState.value.copy(isSpeaking = speaking)
                if (speaking) {
                    speechManager.pauseListening()
                } else if (!_uiState.value.isMuted) {
                    delay(400)
                    speechManager.resumeListening()
                }
            }
        }

        // Observe active model path
        viewModelScope.launch {
            gemmaManager.activeModelPath.collect { path ->
                _uiState.value = _uiState.value.copy(activeModelPath = path ?: "")
            }
        }

        // Load Gemma model on startup
        loadModel()
    }

    /**
     * Loads the Gemma model either from a user-specified custom path,
     * previously saved path, or default candidate locations.
     */
    fun loadModel(customPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(
                    isModelLoading = true,
                    modelError = "",
                    statusText = if (customPath != null) "Loading selected model..." else "Loading AI model... (may take 30 seconds)"
                )
                gemmaManager.initialize(customPath)
                val fileName = gemmaManager.activeModelPath.value?.substringAfterLast('/') ?: "Gemma 2B"
                _uiState.value = _uiState.value.copy(
                    isModelLoading = false,
                    isModelReady = true,
                    modelError = "",
                    statusText = "Aura is ready ($fileName). Tap 'Listen' to start."
                )
                addTranscriptEntry(TranscriptEntry(
                    text = "Aura is ready with model: $fileName. Tap 'Listen' when you want me to record and summarize your conversation.",
                    type = TranscriptType.SYSTEM_MESSAGE
                ))
            } catch (e: Exception) {
                Log.e("AuraViewModel", "Model load error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isModelLoading = false,
                    isModelReady = false,
                    modelError = e.message ?: "Failed to load AI model",
                    statusText = "Model error. Tap 'Select Model' or listen without AI."
                )
            }
        }
    }

    // ============ LISTENING CONTROLS ============

    fun toggleListening() {
        if (_uiState.value.isListening) {
            speechManager.pauseListening()
            _uiState.value = _uiState.value.copy(
                isListening = false,
                statusText = "Listening paused. Processing notes..."
            )
            viewModelScope.launch {
                finalizeCurrentBlock("Manual pause")
            }
        } else {
            startAlwaysListening()
        }
    }

    fun startAlwaysListening() {
        if (_uiState.value.isMuted) return

        _uiState.value = _uiState.value.copy(
            isListening = true,
            statusText = "Listening to conversation..."
        )

        speechManager.startContinuousListening(
            onSegment = { text -> onSpeechSegmentCaptured(text) },
            onError = { error ->
                Log.w("AuraViewModel", "Speech error: $error")
                _uiState.value = _uiState.value.copy(statusText = error)
            }
        )
    }

    private fun onSpeechSegmentCaptured(text: String) {
        if (text.isBlank()) return

        val now = System.currentTimeMillis()

        viewModelScope.launch {
            // 1. Save raw segment to DB
            segmentDao.insert(ConversationSegment(text = text))
            unsummarizedCount++

            // 2. Update today's count
            val startOfDay = getStartOfToday()
            val count = segmentDao.getTodayCount(startOfDay)
            _uiState.value = _uiState.value.copy(todaySegmentCount = count)

            // 3. Check if max 60s cap reached on existing block before adding new speech
            val timeInBlock = if (blockStartTimestamp > 0L) now - blockStartTimestamp else 0L
            if (timeInBlock >= MAX_BLOCK_DURATION_MS) {
                finalizeCurrentBlock("Max 60s reached")
            }

            // 4. If starting a new block, set start timestamp and schedule 60s max timer
            if (blockStartTimestamp == 0L) {
                blockStartTimestamp = now
                currentBlockTextBuilder.setLength(0)

                maxBlockDurationJob?.cancel()
                maxBlockDurationJob = launch {
                    delay(MAX_BLOCK_DURATION_MS)
                    finalizeCurrentBlock("Max 60s reached")
                }
            }

            // 5. Append text to current block buffer
            if (currentBlockTextBuilder.isNotEmpty()) {
                currentBlockTextBuilder.append(" ")
            }
            currentBlockTextBuilder.append(text)
            lastSpeechTimestamp = now
            val fullBlockText = currentBlockTextBuilder.toString().trim()

            // 6. Update or create the active dialogue card in transcript feed
            val currentEntries = _uiState.value.transcriptEntries.toMutableList()
            val activeIndex = if (activeSpeechEntryId != null) {
                currentEntries.indexOfLast { it.id == activeSpeechEntryId }
            } else -1

            if (activeIndex != -1 && currentEntries[activeIndex].type == TranscriptType.USER_SPEECH) {
                val existing = currentEntries[activeIndex]
                currentEntries[activeIndex] = existing.copy(text = fullBlockText, timestamp = now)
                _uiState.value = _uiState.value.copy(transcriptEntries = currentEntries)
            } else {
                val newEntry = TranscriptEntry(
                    text = fullBlockText,
                    type = TranscriptType.USER_SPEECH
                )
                activeSpeechEntryId = newEntry.id
                addTranscriptEntry(newEntry)
            }

            // 7. Check for tasks immediately for responsive UI
            if (isTaskCommand(text)) {
                extractAndSaveNewTasks(text)
            }

            // 8. Reset the 30-second silence watchdog timer
            silenceWatchdogJob?.cancel()
            silenceWatchdogJob = launch {
                delay(SILENCE_TIMEOUT_MS)
                finalizeCurrentBlock("30s silence reached")
            }
        }
    }

    private suspend fun finalizeCurrentBlock(reason: String) {
        maxBlockDurationJob?.cancel()
        silenceWatchdogJob?.cancel()

        val fullText = currentBlockTextBuilder.toString().trim()
        val entryId = activeSpeechEntryId

        // Reset block tracking for next conversational turn
        blockStartTimestamp = 0L
        lastSpeechTimestamp = 0L
        activeSpeechEntryId = null
        currentBlockTextBuilder.setLength(0)

        if (fullText.isBlank()) return

        Log.d("AuraViewModel", "Finalizing speech block ($reason): ${fullText.take(60)}...")

        // 1. Contextual interpretation with Gemma 2B (fixes phonetic STT acoustic mishearings)
        val correctedText = if (gemmaManager.isReady()) {
            gemmaManager.correctAndInterpretSpeech(fullText)
        } else {
            fullText
        }

        // 2. Update dialogue card with polished, sensible text
        if (correctedText != fullText && entryId != null) {
            val currentEntries = _uiState.value.transcriptEntries.toMutableList()
            val index = currentEntries.indexOfLast { it.id == entryId }
            if (index != -1) {
                currentEntries[index] = currentEntries[index].copy(text = correctedText)
                _uiState.value = _uiState.value.copy(transcriptEntries = currentEntries)
            }
        }

        // 3. Multi-task extraction from the complete finalized text
        if (isTaskCommand(correctedText) || isTaskCommand(fullText)) {
            extractAndSaveNewTasks(correctedText)
        }

        // 4. Memory distillation: Gemma evaluates if this block has important takeaways or small talk
        if (gemmaManager.isReady()) {
            val insight = gemmaManager.evaluateAndExtract(correctedText)
            if (insight != null) {
                memoryDao.insert(Memory(
                    userInput = correctedText.take(500),
                    aiResponse = insight,
                    category = "important",
                    tags = extractTags(correctedText)
                ))
                addTranscriptEntry(TranscriptEntry(
                    text = "💡 Saved Memory: $insight",
                    type = TranscriptType.AI_SUMMARY
                ))
            }
        }

        // 5. Pruning raw segments older than 24h that are distilled into memories
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        segmentDao.deleteOldSummarized(yesterday)
    }

    private suspend fun extractAndSaveNewTasks(text: String) {
        val tasks = saveAsTasks(text)
        for (task in tasks) {
            val signature = "${task.title.lowercase().trim()}_${task.dueTime.lowercase().trim()}"
            if (processedTaskSignatures.add(signature)) {
                val timeStr = if (task.dueTime.isNotBlank()) " (${task.dueTime})" else ""
                addTranscriptEntry(TranscriptEntry(
                    text = "📋 Task captured: ${task.title}$timeStr",
                    type = TranscriptType.SYSTEM_MESSAGE
                ))
            }
        }
        if (processedTaskSignatures.size > 100) {
            processedTaskSignatures.clear()
        }
    }

    private suspend fun summarizeRecentSegments() {

        try {
            val unsummarized = segmentDao.getUnsummarizedSegments()
            if (unsummarized.isEmpty()) return

            val conversationText = unsummarized.joinToString("\n") {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
                "[$time] ${it.text}"
            }

            _uiState.value = _uiState.value.copy(statusText = "Filtering conversation with Gemma...")

            // Gemma evaluates: is this important or gibberish?
            val meaningfulInsight = gemmaManager.evaluateAndExtract(conversationText)

            // Mark segments as summarized so we don't re-process them
            segmentDao.markAsSummarized(unsummarized.map { it.id })
            unsummarizedCount = 0

            if (meaningfulInsight != null) {
                // Meaningful! Save to Memory!
                memoryDao.insert(Memory(
                    userInput = conversationText.take(500),
                    aiResponse = meaningfulInsight,
                    category = "important",
                    tags = extractTags(conversationText)
                ))

                addTranscriptEntry(TranscriptEntry(
                    text = "💡 Saved Memory: $meaningfulInsight",
                    type = TranscriptType.AI_SUMMARY
                ))
            } else {
                Log.d("AuraViewModel", "Conversation deemed small talk or filler. Discarded from memories.")
            }

            // Pruning: Clean up raw segments older than 24h that are already distilled into memories
            val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            segmentDao.deleteOldSummarized(yesterday)

            _uiState.value = _uiState.value.copy(statusText = "Aura is ready.")

        } catch (e: Exception) {
            Log.e("AuraViewModel", "Summary error: ${e.message}")
            _uiState.value = _uiState.value.copy(statusText = "Aura is ready.")
        }
    }

    // ============ QUERY MODE (Ask Aura) ============

    fun enterQueryMode() {
        _uiState.value = _uiState.value.copy(isQueryMode = true)
        speechManager.stopListening()
        ttsManager.speak("What would you like to know?")
    }

    fun askQuestion(query: String) {
        if (query.isBlank()) return

        addTranscriptEntry(TranscriptEntry(
            text = "❓ $query",
            type = TranscriptType.USER_SPEECH
        ))

        if (isTaskCommand(query)) {
            viewModelScope.launch {
                val savedTasks = saveAsTasks(query)
                val response = when {
                    savedTasks.isEmpty() -> "I couldn't identify a specific task. Please try rephrasing."
                    savedTasks.size == 1 -> {
                        val task = savedTasks[0]
                        val timeStr = if (task.dueTime.isNotBlank()) " due ${task.dueTime}" else ""
                        "Got it! Added to your tasks: ${task.title}$timeStr"
                    }
                    else -> {
                        val names = savedTasks.joinToString(", ") { it.title + if (it.dueTime.isNotBlank()) " (${it.dueTime})" else "" }
                        "Got it! Added ${savedTasks.size} tasks: $names"
                    }
                }
                for (task in savedTasks) {
                    val timeStr = if (task.dueTime.isNotBlank()) " (${task.dueTime})" else ""
                    addTranscriptEntry(TranscriptEntry(
                        text = "📋 Task added: ${task.title}$timeStr",
                        type = TranscriptType.SYSTEM_MESSAGE
                    ))
                }
                addTranscriptEntry(TranscriptEntry(
                    text = response,
                    type = TranscriptType.AI_RESPONSE
                ))
                ttsManager.speak(response)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusText = if (_uiState.value.isListening) "Listening to conversation..." else "Aura is ready."
                )
            }
            _uiState.value = _uiState.value.copy(isQueryMode = false)
            return
        }


        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusText = "Thinking..."
            )

            try {
                // Check for online data needs
                val queryType = onlineLookup.needsOnlineData(query)
                var onlineData = ""

                if (queryType != OnlineQueryType.NONE) {
                    _uiState.value = _uiState.value.copy(
                        onlineCallActive = true,
                        statusText = "Fetching live data..."
                    )
                    onlineData = when (queryType) {
                        OnlineQueryType.WEATHER -> {
                            val targetCity = onlineLookup.extractCity(query)
                            onlineLookup.fetchWeather(targetCity)
                        }
                        OnlineQueryType.NEWS -> onlineLookup.fetchNews()
                        else -> ""
                    }
                    _uiState.value = _uiState.value.copy(onlineCallActive = false)
                }

                // Get today's conversation for context
                val todaySegments = segmentDao.getTodaySegments(getStartOfToday())
                val conversationContext = todaySegments.takeLast(20).joinToString("\n") {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
                    "[$time] ${it.text}"
                }

                // Get relevant memories
                val memories = memoryDao.searchMemories(query.take(50))
                    .take(3)
                    .joinToString("\n") { "- ${it.aiResponse}" }

                // Get pending tasks
                val pendingTasks = taskDao.getPendingTasks()
                    .take(5)
                    .joinToString("\n") { "- ${it.title} ${if (it.dueTime.isNotBlank()) "(${it.dueTime})" else ""}" }

                val response = if (gemmaManager.isReady()) {
                    gemmaManager.generateResponse(
                        userInput = query,
                        memoryContext = "TODAY'S CONVERSATION:\n$conversationContext\n\nPAST MEMORIES:\n$memories",
                        taskContext = pendingTasks,
                        onlineData = onlineData
                    )
                } else if (onlineData.isNotBlank()) {
                    onlineData
                } else {
                    getFallbackResponse(query, pendingTasks)
                }

                // Save to memory
                memoryDao.insert(Memory(
                    userInput = query,
                    aiResponse = response,
                    category = if (queryType != OnlineQueryType.NONE) queryType.name.lowercase() else "query",
                    tags = extractTags(query)
                ))

                addTranscriptEntry(TranscriptEntry(
                    text = response,
                    type = TranscriptType.AI_RESPONSE
                ))

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusText = if (_uiState.value.isListening) "Listening to conversation..." else "Aura is ready."
                )

                ttsManager.speak(response)

            } catch (e: Exception) {
                Log.e("AuraViewModel", "Query error: ${e.message}")
                val errMsg = "I had trouble with that. Please try again."
                addTranscriptEntry(TranscriptEntry(
                    text = errMsg,
                    type = TranscriptType.AI_RESPONSE
                ))
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusText = "Aura is ready."
                )
            }
        }

        _uiState.value = _uiState.value.copy(isQueryMode = false)
    }

    fun startQueryListening() {
        speechManager.startOneShotListening(
            onResult = { text -> askQuestion(text) },
            onError = { error ->
                _uiState.value = _uiState.value.copy(
                    isQueryMode = false,
                    statusText = "Aura is ready."
                )
            }
        )
    }

    // ============ DAY SUMMARY ============

    fun requestDaySummary() {
        viewModelScope.launch {
            val startOfDay = getStartOfToday()
            val todayMemories = memoryDao.getTodayMemories(startOfDay)
            val todaySegments = segmentDao.getTodaySegments(startOfDay)
            val pendingTaskList = taskDao.getPendingTasks()

            if (todayMemories.isEmpty() && todaySegments.isEmpty() && pendingTaskList.isEmpty()) {
                addTranscriptEntry(TranscriptEntry(
                    text = "No conversations or tasks recorded today yet. Start speaking or add tasks to generate your briefing.",
                    type = TranscriptType.SYSTEM_MESSAGE
                ))
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusText = "Generating daily briefing..."
            )

            try {
                // Hierarchical Memory: prefer distilled takeaways over raw lines
                val dayText = when {
                    todayMemories.isNotEmpty() -> {
                        "KEY TAKEAWAYS FROM TODAY'S MEETINGS:\n" +
                        todayMemories.takeLast(10).joinToString("\n") { "- ${it.aiResponse}" }
                    }
                    todaySegments.isNotEmpty() -> {
                        "RECENT CONVERSATION:\n" +
                        todaySegments.takeLast(10).joinToString("\n") {
                            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
                            "[$time] ${it.text}"
                        }
                    }
                    else -> "No conversations recorded today."
                }

                val tasksText = if (pendingTaskList.isNotEmpty()) {
                    pendingTaskList.joinToString("\n") { task ->
                        if (task.dueTime.isNotBlank()) "- ${task.title} (Deadline: ${task.dueTime})"
                        else "- ${task.title}"
                    }
                } else {
                    "No pending tasks."
                }

                val summary = if (gemmaManager.isReady()) {
                    gemmaManager.generateResponse(
                        userInput = "Provide a comprehensive Daily Briefing based on today's events and tasks. Structure your response into:\n" +
                                "1. 📌 Discussions: Key topics & decisions from conversations today.\n" +
                                "2. 📋 Action Items: Outstanding tasks and deadlines to complete.\n" +
                                "3. 💡 Aura's Reminder: One proactive reminder, tip, or follow-up recommendation for me based on the above.\n" +
                                "Keep it clear, concise, and structured.",
                        memoryContext = dayText,
                        taskContext = tasksText,
                        onlineData = ""
                    )
                } else {
                    "Daily Summary:\nToday you had ${todaySegments.size} conversation segments.\n\nPending Tasks:\n$tasksText"
                }

                addTranscriptEntry(TranscriptEntry(
                    text = "📊 DAILY BRIEFING:\n$summary",
                    type = TranscriptType.AI_SUMMARY
                ))

                ttsManager.speak(summary)

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusText = if (_uiState.value.isListening) "Listening to conversation..." else "Aura is ready."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusText = "Aura is ready."
                )
            }
        }
    }

    // ============ MUTE/UNMUTE ============

    fun toggleMute() {
        val muted = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(
            isMuted = muted,
            statusText = if (muted) "🔇 Muted. Tap to unmute." else "Always listening..."
        )
        if (muted) {
            speechManager.stopListening()
            ttsManager.stop()
            addTranscriptEntry(TranscriptEntry(
                text = "Listening paused.",
                type = TranscriptType.SYSTEM_MESSAGE
            ))
        } else {
            addTranscriptEntry(TranscriptEntry(
                text = "Listening resumed.",
                type = TranscriptType.SYSTEM_MESSAGE
            ))
            startAlwaysListening()
        }
    }

    // ============ HELPERS ============

    private fun addTranscriptEntry(entry: TranscriptEntry) {
        val current = _uiState.value.transcriptEntries.toMutableList()
        current.add(entry)
        val trimmed = if (current.size > 100) current.takeLast(100) else current
        _uiState.value = _uiState.value.copy(transcriptEntries = trimmed)
    }

    private fun getStartOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun getFallbackResponse(userText: String, tasks: String): String {
        val lower = userText.lowercase()
        return when {
            lower.contains("summary") || lower.contains("day") ->
                "I captured your conversations today. My AI module is still loading for full summary."
            lower.contains("task") || lower.contains("remind") ->
                if (tasks.isNotBlank()) "Here are your tasks:\n$tasks" else "No pending tasks."
            lower.contains("hello") || lower.contains("hi") ->
                "Hi! I'm Aura, always listening and capturing your conversations."
            else -> "My AI model is loading. Please try again in a moment."
        }
    }

    private fun isTaskCommand(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("action item") ||
               lower.contains("remind me") ||
               lower.contains("reminder to") ||
               lower.contains("remind us") ||
               lower.contains("remember to") ||
               lower.contains("don't forget") ||
               lower.contains("dont forget") ||
               lower.contains("do not forget") ||
               lower.contains("make sure to") ||
               lower.contains("make sure that") ||
               lower.contains("add task") ||
               lower.contains("add a task") ||
               lower.contains("create task") ||
               lower.contains("create a task") ||
               lower.contains("new task") ||
               lower.contains("todo") ||
               lower.contains("to-do") ||
               lower.contains("to do:")
    }

    private suspend fun saveAsTasks(text: String): List<Task> {
        val pairs = gemmaManager.extractAllTasks(text)
        val created = mutableListOf<Task>()
        for ((title, dueTime) in pairs) {
            val task = Task(title = title, dueTime = dueTime)
            val id = taskDao.insert(task)
            created.add(task.copy(id = id))
        }
        return created
    }

    private suspend fun saveAsTask(text: String): String {
        val tasks = saveAsTasks(text)
        return tasks.firstOrNull()?.title ?: "New Task"
    }

    private fun extractTags(text: String): String {
        val keywords = listOf("meeting", "work", "project", "call", "email", "buy",
            "deadline", "gym", "doctor", "travel", "study")
        return keywords.filter { text.lowercase().contains(it) }.joinToString(",")
    }

    // Task actions
    fun toggleTaskComplete(taskId: Long, completed: Boolean) {
        viewModelScope.launch { taskDao.setCompleted(taskId, completed) }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch { taskDao.deleteById(taskId) }
    }

    fun addTaskManually(title: String, dueTime: String = "") {
        viewModelScope.launch {
            taskDao.insert(Task(title = title, dueTime = dueTime))
        }
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch { memoryDao.deleteById(memoryId) }
    }

    fun clearAllMemory() {
        viewModelScope.launch { memoryDao.deleteAll() }
    }

    fun clearNetworkLogs() = onlineLookup.clearLogs()

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
        ttsManager.destroy()
        gemmaManager.destroy()
    }
}
