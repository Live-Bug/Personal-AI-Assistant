package com.aura.companion.ai

import android.content.Context
import android.os.Environment
import android.util.Log
import com.aura.companion.BuildConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
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

/**
 * On-device Gemma 4 E2B running through LiteRT-LM.
 *
 * One [Engine] holds the model weights for the app's lifetime. Every call below is a
 * stateless one-shot, so each opens a short-lived Conversation and closes it afterwards;
 * LiteRT-LM applies the Gemma chat template itself, so prompts are plain text.
 */
class GemmaManager(private val context: Context) {

    private val inferenceMutex = Mutex()
    private var engine: Engine? = null

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(GemmaState.NOT_LOADED)
    val state: StateFlow<GemmaState> = _state

    private val _activeModelPath = MutableStateFlow<String?>(null)
    val activeModelPath: StateFlow<String?> = _activeModelPath

    // "GPU" or "CPU" once loaded, so the UI can show what's actually running
    private val _activeBackend = MutableStateFlow<String?>(null)
    val activeBackend: StateFlow<String?> = _activeBackend

    companion object {
        const val PREF_FILE = "aura_prefs"
        const val PREF_CUSTOM_MODEL_PATH = "custom_model_path"

        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_EXTENSION = ".litertlm"

        // KV-cache size (input + output tokens). Gemma 4 E2B supports up to 32K, but 4K keeps
        // GPU memory comfortable on 8 GB devices and is plenty for these prompts.
        private const val MAX_NUM_TOKENS = 4096

        // Character budgets for context injected into prompts (~4 chars per token)
        private const val MAX_MEMORY_CONTEXT_CHARS = 6000
        private const val MAX_EVALUATION_CHARS = 4000

        val DEFAULT_SEARCH_PATHS = listOf(
            "/data/local/tmp/llm/$MODEL_FILE_NAME",
            "/storage/emulated/0/Download/$MODEL_FILE_NAME",
            "/sdcard/Download/$MODEL_FILE_NAME"
        )

        private const val SYSTEM_PROMPT = """You are Aura, a helpful and private personal AI companion running entirely on-device.
You help with memory recall and productivity tasks. You are concise, warm, and honest.

KEY RULES:
- Keep responses under 3 sentences unless asked for detail
- If you don't know something or are unsure, say "I'm not certain about that, but here's what I can share..."
- Never pretend to have real-time information unless it's provided in the context
- For tasks, extract actionable items clearly
- For memory queries, be specific about what you recall from the conversation history provided"""

        // Conversational answers get some variety; extraction/correction uses greedy decoding (topK = 1)
        private val CHAT_SAMPLER = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        private val PRECISE_SAMPLER = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0)
    }

    suspend fun initialize(customPath: String? = null) = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            try {
                _state.value = GemmaState.LOADING
                closeEngine()

                val resolvedPath = resolveModelPath(customPath)
                if (resolvedPath == null) {
                    _activeModelPath.value = null
                    _state.value = GemmaState.ERROR
                    throw IllegalStateException(
                        "Gemma 4 model ($MODEL_FILE_NAME) not found. Please tap 'Select Model' to pick your model file."
                    )
                }

                Log.i("GemmaManager", "Initializing Gemma 4 E2B from: $resolvedPath")
                val (loadedEngine, backendName) = createEngine(resolvedPath)
                engine = loadedEngine

                prefs.edit().putString(PREF_CUSTOM_MODEL_PATH, resolvedPath).apply()
                _activeModelPath.value = resolvedPath
                _activeBackend.value = backendName
                _state.value = GemmaState.READY
                Log.i("GemmaManager", "Gemma 4 E2B ready on $backendName")
            } catch (e: Exception) {
                Log.e("GemmaManager", "Initialization failed: ${e.message}")
                _state.value = GemmaState.ERROR
                throw e
            }
        }
    }

    // Custom argument -> SharedPreferences -> default candidates. Only .litertlm files qualify,
    // so a stale MediaPipe .bin path saved by an older build is ignored.
    private fun resolveModelPath(customPath: String?): String? {
        fun usable(path: String?) =
            !path.isNullOrBlank() && path.endsWith(MODEL_EXTENSION, ignoreCase = true) && File(path).exists()

        if (usable(customPath)) return customPath
        val savedPath = prefs.getString(PREF_CUSTOM_MODEL_PATH, null)
        if (usable(savedPath)) return savedPath

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dynamicPath = File(downloadDir, MODEL_FILE_NAME).absolutePath
        return (DEFAULT_SEARCH_PATHS + dynamicPath).firstOrNull { usable(it) }
    }

    // GPU first; fall back to CPU on devices without a usable OpenCL driver
    private fun createEngine(modelPath: String): Pair<Engine, String> {
        try {
            return Pair(buildEngineWithSpeculativeDecoding(modelPath, Backend.GPU()), "GPU")
        } catch (e: Exception) {
            Log.w("GemmaManager", "GPU backend unavailable, falling back to CPU: ${e.message}")
        }
        return Pair(buildEngineWithSpeculativeDecoding(modelPath, Backend.CPU()), "CPU")
    }

    // Speculative decoding uses the model's bundled Multi-Token Prediction drafter (~1.3-1.8x faster
    // decode). Model files downloaded before May 2026 lack the drafter, so retry without it on failure.
    @OptIn(ExperimentalApi::class)
    private fun buildEngineWithSpeculativeDecoding(modelPath: String, backend: Backend): Engine {
        ExperimentalFlags.enableBenchmark = BuildConfig.DEBUG // per-call tokens/sec in logcat
        ExperimentalFlags.enableSpeculativeDecoding = true
        try {
            return buildEngine(modelPath, backend)
        } catch (e: Exception) {
            Log.w("GemmaManager", "Speculative decoding unavailable, retrying without it: ${e.message}")
        }
        ExperimentalFlags.enableSpeculativeDecoding = false
        return buildEngine(modelPath, backend)
    }

    private fun buildEngine(modelPath: String, backend: Backend): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = MAX_NUM_TOKENS,
            cacheDir = context.cacheDir.path
        )
        val newEngine = Engine(config)
        try {
            newEngine.initialize() // Slow (several seconds); always called on Dispatchers.IO
        } catch (e: Exception) {
            newEngine.close()
            throw e
        }
        return newEngine
    }

    private fun closeEngine() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w("GemmaManager", "Error closing previous engine: ${e.message}")
        }
        engine = null
        _activeBackend.value = null
    }

    // Runs a single-turn prompt in a fresh conversation. Caller must hold inferenceMutex.
    private fun runInference(
        prompt: String,
        sampler: SamplerConfig,
        systemInstruction: String? = null
    ): String {
        val activeEngine = engine ?: throw IllegalStateException("Model not loaded")
        val config = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(it) },
            samplerConfig = sampler
        )
        return activeEngine.createConversation(config).use { conversation ->
            val text = conversation.sendMessage(prompt).contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
                .trim()
            if (BuildConfig.DEBUG) logBenchmark(conversation)
            text
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun logBenchmark(conversation: Conversation) {
        try {
            val info = conversation.getBenchmarkInfo()
            Log.d(
                "GemmaManager",
                "Inference: prefill ${info.lastPrefillTokenCount} tok @ %.1f tok/s, decode ${info.lastDecodeTokenCount} tok @ %.1f tok/s, TTFT %.2fs"
                    .format(info.lastPrefillTokensPerSecond, info.lastDecodeTokensPerSecond, info.timeToFirstTokenInSecond)
            )
        } catch (e: Exception) {
            Log.d("GemmaManager", "Benchmark info unavailable: ${e.message}")
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
                // Safeguard: clamp context length so the prompt fits the KV-cache
                val safeMemory = memoryContext.takeLast(MAX_MEMORY_CONTEXT_CHARS)
                val prompt = buildPrompt(userInput, safeMemory, taskContext, onlineData)
                val response = runInference(prompt, CHAT_SAMPLER, SYSTEM_PROMPT)
                _state.value = GemmaState.READY
                response.ifBlank { "I couldn't process that." }
            } catch (e: Exception) {
                Log.e("GemmaManager", "Response inference error: ${e.message}")
                _state.value = GemmaState.READY
                "I'm having trouble processing that right now. Please try again."
            }
        }
    }

    /**
     * Filters recorded speech through Gemma:
     * - Returns null if the text is small talk, filler, or gibberish.
     * - Returns a 1-sentence summary if it contains important facts, decisions, or commitments.
     */
    suspend fun evaluateAndExtract(conversationText: String): String? = withContext(Dispatchers.IO) {
        if (_state.value != GemmaState.READY || conversationText.isBlank()) return@withContext null

        inferenceMutex.withLock {
            _state.value = GemmaState.INFERRING
            try {
                val safeText = conversationText.takeLast(MAX_EVALUATION_CHARS)
                val prompt = """You are an intelligent memory filter for a personal AI companion.
Analyze the following recorded speech:
""${'"'}
$safeText
""${'"'}

Determine if this speech contains meaningful information, facts, decisions, commitments, or tasks worth remembering.
- If it is meaningless filler, small talk, gibberish, or casual noise (e.g., "yeah", "ok", "no", "haha"), reply with ONLY: DISCARD
- If it contains valuable information or action items, summarize the key takeaway in 1 clear, concise sentence."""
                val response = runInference(prompt, PRECISE_SAMPLER).ifBlank { "DISCARD" }
                _state.value = GemmaState.READY
                if (response.startsWith("DISCARD", ignoreCase = true) || response.length < 5) {
                    null
                } else {
                    response
                }
            } catch (e: Exception) {
                Log.e("GemmaManager", "Memory evaluation error: ${e.message}")
                _state.value = GemmaState.READY
                null
            }
        }
    }

    /**
     * Extracts all actionable tasks and deadlines from spoken text into clean titles and deadlines.
     * Uses on-device Gemma when ready, with high-precision pattern extraction as fallback.
     */
    suspend fun extractAllTasks(rawText: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val fallbackTasks = fallbackMultiTaskExtraction(rawText)

        if (_state.value != GemmaState.READY || rawText.isBlank()) {
            return@withContext fallbackTasks
        }

        inferenceMutex.withLock {
            _state.value = GemmaState.INFERRING
            try {
                val prompt = """Extract all actionable tasks and commitments from this spoken text into concise task titles (max 6 words, starting with an imperative verb) and any mentioned deadline.
Speech: "$rawText"

If tasks are found, output each task on a new line EXACTLY like this:
TASK: [short action item] | TIME: [deadline if mentioned, otherwise None]

If no actionable tasks are found, output: NONE"""
                val response = runInference(prompt, PRECISE_SAMPLER)
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
                val prompt = """You are an intelligent ambient speech corrector. Spoken English was recorded by a phone microphone across the room and transcribed by an offline speech recognizer. It may contain phonetic errors, homophones, or misheard technical/domain words (e.g., "date and friendship" -> "data pipeline", "iceberk" -> "iceberg", "caugh car" -> "kafka").

Tasks:
1. Fix obvious speech recognition mistakes into sensible, coherent natural English based on context.
2. Keep the exact meaning and original words where they make sense. Do NOT add new facts or summarize.
3. If the transcription already makes good sense, keep it as is.
4. Output ONLY the cleaned transcript with no preamble or explanation.

Raw speech: "$rawText"
Cleaned text:"""
                val response = runInference(prompt, PRECISE_SAMPLER)
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
        sb.append("Answer directly based on the context above.")
        return sb.toString()
    }

    fun isReady() = _state.value == GemmaState.READY

    fun destroy() {
        closeEngine()
        _activeModelPath.value = null
        _state.value = GemmaState.NOT_LOADED
    }
}
