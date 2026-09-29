package com.aura.companion.ai

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class GemmaState {
    NOT_LOADED, LOADING, READY, INFERRING, ERROR
}

class GemmaManager(private val context: Context) {

    private val inferenceMutex = Mutex()
    private var llmInference: LlmInference? = null

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(GemmaState.NOT_LOADED)
    val state: StateFlow<GemmaState> = _state

    private val _activeModelPath = MutableStateFlow<String?>(null)
    val activeModelPath: StateFlow<String?> = _activeModelPath

    // Model paths & constants
    companion object {
        const val PREF_FILE = "aura_prefs"
        const val PREF_CUSTOM_MODEL_PATH = "custom_model_path"

        val DEFAULT_SEARCH_PATHS = listOf(
            "/data/local/tmp/llm/gemma-2b-it-cpu-int4.bin",
            "/storage/emulated/0/Download/gemma-2b-it-cpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-cpu-int4.bin",
            "/data/local/tmp/llm/gemma-2b-it-gpu-int4.bin",
            "/storage/emulated/0/Download/gemma-2b-it-gpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-gpu-int4.bin"
        )

        // System prompt that defines Aura's personality
        private const val SYSTEM_PROMPT = """You are Aura, a helpful and private personal AI companion running entirely on-device. 
You help with memory recall and productivity tasks. You are concise, warm, and honest.

KEY RULES:
- Keep responses under 3 sentences unless asked for detail
- If you don't know something or are unsure, say "I'm not certain about that, but here's what I can share..."
- Never pretend to have real-time information unless it's provided in the context
- For tasks, extract actionable items clearly
- For memory queries, be specific about what you recall from the conversation history provided

"""
    }

    suspend fun initialize(customPath: String? = null) = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            try {
                _state.value = GemmaState.LOADING

                // Close any previous instance cleanly before re-initializing
                try {
                    llmInference?.close()
                } catch (e: Exception) {
                    Log.w("GemmaManager", "Error closing previous instance: ${e.message}")
                }
                llmInference = null

                // Resolve model path: custom argument -> SharedPreferences -> default candidates
                val resolvedPath: String? = when {
                    !customPath.isNullOrBlank() && File(customPath).exists() -> customPath
                    else -> {
                        val savedPath = prefs.getString(PREF_CUSTOM_MODEL_PATH, null)
                        if (!savedPath.isNullOrBlank() && File(savedPath).exists()) {
                            savedPath
                        } else {
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val dynamicPaths = listOf(
                                File(downloadDir, "gemma-2b-it-cpu-int4.bin").absolutePath,
                                File(downloadDir, "gemma-2b-it-gpu-int4.bin").absolutePath
                            )
                            (DEFAULT_SEARCH_PATHS + dynamicPaths).firstOrNull { File(it).exists() }
                        }
                    }
                }

                if (resolvedPath == null) {
                    _activeModelPath.value = null
                    _state.value = GemmaState.ERROR
                    throw IllegalStateException("Gemma model (.bin) not found. Please tap 'Select Model' to pick your model file.")
                }

                Log.i("GemmaManager", "Initializing Gemma 2B from: $resolvedPath")

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(resolvedPath)
                    .setMaxTokens(512)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)

                // Persist the verified path
                prefs.edit().putString(PREF_CUSTOM_MODEL_PATH, resolvedPath).apply()
                _activeModelPath.value = resolvedPath
                _state.value = GemmaState.READY
                Log.i("GemmaManager", "Gemma successfully initialized and ready")
            } catch (e: Exception) {
                Log.e("GemmaManager", "Initialization failed: ${e.message}")
                _state.value = GemmaState.ERROR
                throw e
            }
        }
    }

    suspend fun generateResponse(
        userInput: String,
        memoryContext: String = "",
        taskContext: String = "",
        onlineData: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (_state.value != GemmaState.READY) {
            return@withContext "Aura is still loading. Please wait a moment."
        }

        inferenceMutex.withLock {
            _state.value = GemmaState.INFERRING

            try {
                // Safeguard: clamp context length so prompt never overflows the model's token capacity
                val safeMemory = if (memoryContext.length > 1200) memoryContext.takeLast(1200) else memoryContext
                val prompt = buildPrompt(userInput, safeMemory, taskContext, onlineData)
                val response = llmInference?.generateResponse(prompt) ?: "I couldn't process that."
                _state.value = GemmaState.READY
                response.trim()
            } catch (e: Exception) {
                _state.value = GemmaState.READY
                "I'm having trouble processing that right now. Please try again."
            }
        }
    }

    /**
     * Filters recorded speech through Gemma 2B:
     * - Returns null if the text is small talk, filler, or gibberish.
     * - Returns a 1-sentence summary if it contains important facts, decisions, or commitments.
     */
    suspend fun evaluateAndExtract(conversationText: String): String? = withContext(Dispatchers.IO) {
        if (_state.value != GemmaState.READY || conversationText.isBlank()) return@withContext null

        inferenceMutex.withLock {
            _state.value = GemmaState.INFERRING
            try {
                // Safeguard: clamp text length to prevent native buffer overflow
                val safeText = if (conversationText.length > 800) conversationText.takeLast(800) else conversationText
                val prompt = """<start_of_turn>user
You are an intelligent memory filter for a personal AI companion.
Analyze the following recorded speech:
\"\"\"
$safeText
\"\"\"

Determine if this speech contains meaningful information, facts, decisions, commitments, or tasks worth remembering.
- If it is meaningless filler, small talk, gibberish, or casual noise (e.g., "yeah", "ok", "no", "haha"), reply with ONLY: DISCARD
- If it contains valuable information or action items, summarize the key takeaway in 1 clear, concise sentence.
<end_of_turn>
<start_of_turn>model
"""
                val response = llmInference?.generateResponse(prompt)?.trim() ?: "DISCARD"
                _state.value = GemmaState.READY
                if (response.startsWith("DISCARD", ignoreCase = true) || response.length < 5) {
                    null
                } else {
                    response
                }
            } catch (e: Exception) {
                _state.value = GemmaState.READY
                null
            }
        }
    }

    /**
     * Extracts all actionable tasks and deadlines from spoken text into clean titles and deadlines.
     * Uses on-device Gemma 2B when ready, with high-precision pattern extraction as fallback.
     */
     suspend fun extractAllTasks(rawText: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val fallbackTasks = fallbackMultiTaskExtraction(rawText)

        if (_state.value != GemmaState.READY || rawText.isBlank()) {
            return@withContext fallbackTasks
        }

        inferenceMutex.withLock {
            _state.value = GemmaState.INFERRING
            try {
                val prompt = """<start_of_turn>user
Extract all actionable tasks and commitments from this spoken text into concise task titles (max 6 words, starting with an imperative verb) and any mentioned deadline.
Speech: "$rawText"

If tasks are found, output each task on a new line EXACTLY like this:
TASK: [short action item] | TIME: [deadline if mentioned, otherwise None]

If no actionable tasks are found, output: NONE
<end_of_turn>
<start_of_turn>model
"""
                val response = llmInference?.generateResponse(prompt)?.trim() ?: ""
                _state.value = GemmaState.READY

                val gemmaTasks = mutableListOf<Pair<String, String>>()
                val lines = response.lines()
                val lineRegex = Regex("(?i)(?:-\\s*)?TASK:\\s*(.+?)(?:\\s*\\|\\s*TIME:\\s*(.+))?$")

                for (line in lines) {
                    val match = lineRegex.find(line.trim())
                    if (match != null) {
                        val titleGroup = match.groupValues.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                        val timeGroup = match.groupValues.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                        if (titleGroup.isNotBlank() && !titleGroup.equals("None", ignoreCase = true) && !titleGroup.equals("NONE", ignoreCase = true) && titleGroup.length < 80) {
                            val cleanTime = if (timeGroup.equals("None", ignoreCase = true) || timeGroup.isBlank()) "" else timeGroup
                            gemmaTasks.add(Pair(titleGroup, cleanTime))
                        }
                    }
                }

                if (gemmaTasks.isNotEmpty() && gemmaTasks.size >= fallbackTasks.size) {
                    gemmaTasks
                } else if (fallbackTasks.isNotEmpty()) {
                    fallbackTasks
                } else {
                    gemmaTasks
                }
            } catch (e: Exception) {
                Log.e("GemmaManager", "Task extraction inference error: ${e.message}")
                _state.value = GemmaState.READY
                fallbackTasks
            }
        }
    }

    suspend fun extractCleanTask(rawText: String): Pair<String, String> {
        return extractAllTasks(rawText).firstOrNull() ?: Pair("New Task", "")
    }

    fun fallbackMultiTaskExtraction(text: String): List<Pair<String, String>> {
        val triggerRegex = Regex(
            """(?i)\b(?:please\s+)?(?:remind me (?:to|that)?|reminder to|remember to|don'?t forget (?:to)?|do not forget (?:to)?|make sure (?:to|that)?|action item(?:\s+is)?(?:\s+to)?(?::)?|add (?:a\s+)?task(?:\s+to)?(?::)?|create (?:a\s+)?task(?:\s+to)?(?::)?|todo(?::)?|to do(?::)?|to-do(?::)?|and also(?:\s+to)?)\b"""
        )
        val timeRegex = Regex(
            """(?i)\b(?:by|at|before|around)?\s*(\d{1,2}(?::\d{2})?\s*(?:am|pm)|\b(?:tomorrow|tonight|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\s+(?:at|by)?\s*\d{1,2}(?::\d{2})?\s*(?:am|pm)?)?)\b"""
        )
        val trailingConnectors = Regex(
            """(?i)(?:,\s*)?(?:\b(?:and\s+also\s+to|and\s+also|and\s+to|also\s+to|and|also)\b\s*)+$"""
        )
        val leadingConnectors = Regex(
            """(?i)^(?:\b(?:and\s+also\s+to|and\s+also|and\s+to|also\s+to|also|and|to|is\s+to|is)\b\s*)+"""
        )


        val matches = triggerRegex.findAll(text).toList()
        if (matches.isEmpty()) {
            return emptyList()
        }

        val tasks = mutableListOf<Pair<String, String>>()
        for (i in matches.indices) {
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            if (start >= end) continue

            var chunk = text.substring(start, end).trim()

            // If there's a period followed by capital letter (sentence break), cut off
            val periodMatch = Regex("\\.\\s+[A-Z]").find(chunk)
            if (periodMatch != null) {
                chunk = chunk.substring(0, periodMatch.range.first).trim()
            }

            // Extract time/deadline
            val timeMatch = timeRegex.find(chunk)
            var dueTime = ""
            if (timeMatch != null) {
                dueTime = timeMatch.value.trim()
                chunk = (chunk.substring(0, timeMatch.range.first) + " " + chunk.substring(timeMatch.range.last + 1)).trim()
            }

            // Clean up chunk
            chunk = chunk.replace(Regex("\\s+"), " ").trim()
            chunk = chunk.replace(leadingConnectors, "").trim()
            chunk = chunk.replace(trailingConnectors, "").trim()
            chunk = chunk.trim { it <= ' ' || it == ',' || it == '.' || it == ';' }

            if (chunk.isBlank() || chunk.lowercase() in listOf("and", "also", "to", "and also", "then")) {
                continue
            }

            val title = chunk.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            tasks.add(Pair(title.take(70), dueTime))
        }

        return tasks
    }

    /**
     * Contextually corrects speech-to-text transcription errors (e.g. phonetic errors,
     * distant microphone acoustic mishearings, domain terms like 'date and friendship' -> 'data pipeline').
     * If text already makes good sense, returns original or lightly cleaned text.
     */
    suspend fun correctAndInterpretSpeech(rawText: String): String = withContext(Dispatchers.IO) {
        if (_state.value != GemmaState.READY || rawText.isBlank() || rawText.length < 5) {
            return@withContext rawText
        }

        inferenceMutex.withLock {
            _state.value = GemmaState.INFERRING
            try {
                val prompt = """<start_of_turn>user
You are an intelligent ambient speech corrector. Spoken English was recorded by a phone microphone across the room and transcribed by an offline speech recognizer. It may contain phonetic errors, homophones, or misheard technical/domain words (e.g., "date and friendship" -> "data pipeline", "iceberk" -> "iceberg", "caugh car" -> "kafka").

Tasks:
1. Fix obvious speech recognition mistakes into sensible, coherent natural English based on context.
2. Keep the exact meaning and original words where they make sense. Do NOT add new facts or summarize.
3. If the transcription already makes good sense, keep it as is.
4. Output ONLY the cleaned transcript with no preamble or explanation.

Raw speech: "$rawText"
Cleaned text:<end_of_turn>
<start_of_turn>model
"""
                val response = llmInference?.generateResponse(prompt)?.trim() ?: ""
                _state.value = GemmaState.READY

                if (response.isNotBlank() &&
                    !response.contains("sorry", ignoreCase = true) &&
                    !response.startsWith("I cannot", ignoreCase = true) &&
                    response.length in (rawText.length / 2)..(rawText.length * 2 + 50)
                ) {
                    val cleaned = response
                        .replace(Regex("^(?i)(Cleaned text:?|Here is the cleaned text:?|Corrected:?)\\s*"), "")
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                        .trim()
                    if (cleaned.isNotBlank()) cleaned else rawText
                } else {
                    rawText
                }
            } catch (e: Exception) {
                Log.e("GemmaManager", "Speech correction error: ${e.message}")
                _state.value = GemmaState.READY
                rawText
            }
        }
    }



    private fun buildPrompt(
        userInput: String,
        memoryContext: String,
        taskContext: String,
        onlineData: String
    ): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        sb.append("You are Aura, an on-device personal AI companion. Keep answers concise, factual, and direct.\n\n")

        if (memoryContext.isNotBlank()) {
            sb.append("RECORDED CONVERSATION HISTORY:\n$memoryContext\n\n")
        }

        if (taskContext.isNotBlank()) {
            sb.append("PENDING TASKS:\n$taskContext\n\n")
        }

        if (onlineData.isNotBlank()) {
            sb.append("REAL-TIME LOOKUP DATA:\n$onlineData\n\n")
        }

        sb.append("USER REQUEST: $userInput\n")
        sb.append("Answer directly based on the context above.<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    fun isReady() = _state.value == GemmaState.READY

    fun destroy() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            // Ignore
        }
        llmInference = null
        _activeModelPath.value = null
        _state.value = GemmaState.NOT_LOADED
    }
}
