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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class GemmaState {
    NOT_LOADED, // weights not in memory; loaded on the next request
    LOADING,
    READY,      // loaded and idle
    INFERRING,
    ERROR       // no model file, or it failed to load
}

/** What Gemma pulled out of one conversation (or one chunk of a long one). */
data class ConversationInsights(
    val title: String,
    val summary: String,
    val actionItems: List<Pair<String, String>>, // task title to deadline ("" if none)
    val memories: List<String>
) {
    val isEmpty get() = summary.isBlank() && actionItems.isEmpty() && memories.isEmpty()
}

/**
 * On-device Gemma 4 E2B running through LiteRT-LM.
 *
 * The model (~2.3 GB) is loaded on demand and released after [IDLE_UNLOAD_MS] without requests,
 * so an always-on app doesn't hold it in memory all day. Every call is a stateless one-shot in a
 * short-lived Conversation; LiteRT-LM applies the Gemma chat template, so prompts are plain text.
 * One instance is shared app-wide (see AuraApplication).
 */
class GemmaManager(private val context: Context, private val scope: CoroutineScope) {

    private val inferenceMutex = Mutex()
    private var engine: Engine? = null // guarded by inferenceMutex
    private var unloadJob: Job? = null

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(GemmaState.NOT_LOADED)
    val state: StateFlow<GemmaState> = _state

    private val _activeModelPath = MutableStateFlow<String?>(null)
    val activeModelPath: StateFlow<String?> = _activeModelPath

    // "GPU" or "CPU" once loaded, so the UI can show what's actually running
    private val _activeBackend = MutableStateFlow<String?>(null)
    val activeBackend: StateFlow<String?> = _activeBackend

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

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

        // Largest transcript sent in one call: ~2,000 tokens, about 10 minutes of speech, leaving
        // room in MAX_NUM_TOKENS for the instructions and the JSON answer. Longer conversations
        // are split into chunks of this size by ConversationProcessor.
        const val MAX_TRANSCRIPT_CHARS = 8000

        // Release the weights after this long without a request
        private const val IDLE_UNLOAD_MS = 5 * 60_000L

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

    /** Loads the model now, e.g. after the user picked a file. Throws if it can't be loaded. */
    suspend fun load(customPath: String? = null) = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            loadLocked(customPath)
            scheduleUnload()
        }
    }

    /** True if a model file can be found without loading it. */
    fun hasModelFile(): Boolean = resolveModelPath(null) != null

    fun modelFileName(): String? = resolveModelPath(null)?.substringAfterLast('/')

    // Caller must hold inferenceMutex
    private fun loadLocked(customPath: String?): Engine {
        try {
            _state.value = GemmaState.LOADING
            closeEngine()

            val resolvedPath = resolveModelPath(customPath)
                ?: throw IllegalStateException(
                    "Gemma 4 model ($MODEL_FILE_NAME) not found. Choose the model file in Settings."
                )

            Log.i("GemmaManager", "Loading Gemma 4 E2B from: $resolvedPath")
            val (loadedEngine, backendName) = createEngine(resolvedPath)
            engine = loadedEngine

            prefs.edit().putString(PREF_CUSTOM_MODEL_PATH, resolvedPath).apply()
            _activeModelPath.value = resolvedPath
            _activeBackend.value = backendName
            _lastError.value = null
            _state.value = GemmaState.READY
            Log.i("GemmaManager", "Gemma 4 E2B ready on $backendName")
            return loadedEngine
        } catch (e: Exception) {
            Log.e("GemmaManager", "Loading failed: ${e.message}")
            _lastError.value = e.message
            _state.value = GemmaState.ERROR
            throw e
        }
    }

    /**
     * Runs [block] with the engine loaded, loading it first if needed, then restarts the idle
     * unload timer. Calls are serialized.
     */
    private suspend fun <T> withEngine(block: (Engine) -> T): T = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            unloadJob?.cancel()
            val activeEngine = engine ?: loadLocked(null)
            _state.value = GemmaState.INFERRING
            try {
                block(activeEngine)
            } finally {
                _state.value = GemmaState.READY
                scheduleUnload()
            }
        }
    }

    // Caller must hold inferenceMutex
    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            inferenceMutex.withLock {
                if (engine != null) {
                    Log.i("GemmaManager", "Idle for ${IDLE_UNLOAD_MS / 60_000} min, releasing model")
                    closeEngine()
                    _state.value = GemmaState.NOT_LOADED
                }
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
    }

    // Runs a single-turn prompt in a fresh conversation (called inside withEngine)
    private fun runInference(
        activeEngine: Engine,
        prompt: String,
        sampler: SamplerConfig,
        systemInstruction: String? = null
    ): String {
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
    ): String {
        return try {
            withEngine { activeEngine ->
                // Safeguard: clamp context length so the prompt fits the KV-cache
                val safeMemory = memoryContext.takeLast(MAX_MEMORY_CONTEXT_CHARS)
                val prompt = buildPrompt(userInput, safeMemory, taskContext, onlineData)
                runInference(activeEngine, prompt, CHAT_SAMPLER, SYSTEM_PROMPT)
                    .ifBlank { "I couldn't process that." }
            }
        } catch (e: Exception) {
            Log.e("GemmaManager", "Response inference error: ${e.message}")
            if (engine == null) "I can't load my language model right now. ${e.message ?: ""}".trim()
            else "I'm having trouble processing that right now. Please try again."
        }
    }

    /**
     * Summarizes a recorded transcript in one call: title, summary, action items and memories.
     * Returns null if the model failed (the caller keeps the transcript and can retry).
     * [part] is "k of n" when the transcript is one chunk of a longer conversation.
     */
    suspend fun analyzeConversation(transcript: String, part: String? = null): ConversationInsights? {
        val partNote = if (part != null) "\nThis is part $part of a longer conversation.\n" else ""
        // Tuned against sample transcripts (planning, work, small talk, TV, TV mixed with talk):
        // the explicit "keep" decision and media examples stop Gemma saving chatter and ads
        val prompt = """You turn transcripts from an always-on microphone into notes for the person wearing it. The microphone also picks up background chatter, TV, radio, ads and speech recognition errors. Times are local.
$partNote
TRANSCRIPT:
""${'"'}
${transcript.take(MAX_TRANSCRIPT_CHARS)}
""${'"'}

First decide which lines are people in the room talking to each other. Ignore lines that sound like TV, radio, podcasts, ads or a presenter speaking to an audience (for example "welcome back to the show", "stay tuned", recipe steps, offers).

Reply with ONLY a JSON object with these keys, in this order:
"keep": true only if people in the room discussed plans, decisions, commitments or facts worth knowing later. false for greetings, small talk (weather, offering coffee or food, "how are you"), filler or media
"title": 3 to 7 word title of what the people discussed ("" if keep is false)
"summary": 1 to 3 sentences with the main points, decisions and plans ("" if keep is false)
"action_items": things a person in the room committed to or was asked to do, as {"task": "...", "due": "..."}. "task" starts with a verb, max 8 words. "due" is the deadline as said, or ""
"memories": up to 5 facts that will still matter next week and are not already action items: names, relationships, dates, numbers, places, preferences, decisions. Each is a standalone sentence. Never describe the conversation itself.

Use [] for empty lists. Only use information stated in the transcript; copy numbers exactly."""

        return try {
            val response = withEngine { runInference(it, prompt, PRECISE_SAMPLER) }
            parseInsights(response)
        } catch (e: Exception) {
            Log.e("GemmaManager", "Conversation analysis error: ${e.message}")
            null
        }
    }

    /** Combines the per-chunk summaries of a long conversation into one title and summary. */
    suspend fun mergeSummaries(parts: List<ConversationInsights>): Pair<String, String>? {
        val listing = parts.filter { !it.isEmpty }.mapIndexed { i, p ->
            "Part ${i + 1}: ${p.title}. ${p.summary}"
        }.joinToString("\n")
        if (listing.isBlank()) return null
        val prompt = """These are summaries of consecutive parts of one long conversation:
$listing

Reply with ONLY a JSON object: {"title": "3 to 7 word title for the whole conversation", "summary": "2 to 4 sentences covering the whole conversation"}"""
        return try {
            val response = withEngine { runInference(it, prompt, PRECISE_SAMPLER) }
            val json = extractJson(response) ?: return null
            Pair(json.optString("title").trim(), json.optString("summary").trim())
        } catch (e: Exception) {
            Log.e("GemmaManager", "Summary merge error: ${e.message}")
            null
        }
    }

    private fun parseInsights(response: String): ConversationInsights? {
        val json = extractJson(response) ?: return null
        // Gemma still fills in the other fields for chatter; the keep decision is more reliable
        if (!json.optBoolean("keep", true)) return ConversationInsights("", "", emptyList(), emptyList())
        val actions = mutableListOf<Pair<String, String>>()
        json.optJSONArray("action_items")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)
                val task = (item?.optString("task") ?: items.optString(i)).trim()
                val due = item?.optString("due")?.trim().orEmpty()
                if (task.isNotBlank() && task.length <= 100) {
                    actions.add(Pair(task, if (due.equals("none", ignoreCase = true)) "" else due))
                }
            }
        }
        val memories = mutableListOf<String>()
        json.optJSONArray("memories")?.let { items ->
            for (i in 0 until items.length()) {
                val memory = items.optString(i).trim()
                if (memory.length >= 5) memories.add(memory)
            }
        }
        return ConversationInsights(
            title = json.optString("title").trim(),
            summary = json.optString("summary").trim(),
            actionItems = actions,
            memories = memories.take(5)
        )
    }

    // Gemma sometimes wraps JSON in ```json fences or adds a sentence around it
    private fun extractJson(response: String): JSONObject? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Log.w("GemmaManager", "No JSON in model output: ${response.take(200)}")
            return null
        }
        return try {
            JSONObject(response.substring(start, end + 1))
        } catch (e: Exception) {
            Log.w("GemmaManager", "Unparseable model output: ${response.take(200)}")
            null
        }
    }

    /**
     * Extracts all actionable tasks and deadlines from spoken text into clean titles and deadlines.
     * Uses on-device Gemma when ready, with high-precision pattern extraction as fallback.
     */
    suspend fun extractAllTasks(rawText: String): List<Pair<String, String>> {
        val fallbackTasks = fallbackMultiTaskExtraction(rawText)
        if (rawText.isBlank()) return fallbackTasks

        return try {
            withEngine { activeEngine ->
                val prompt = """Extract all actionable tasks and commitments from this spoken text into concise task titles (max 6 words, starting with an imperative verb) and any mentioned deadline.
Speech: "$rawText"

If tasks are found, output each task on a new line EXACTLY like this:
TASK: [short action item] | TIME: [deadline if mentioned, otherwise None]

If no actionable tasks are found, output: NONE"""
                val response = runInference(activeEngine, prompt, PRECISE_SAMPLER)

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
            }
        } catch (e: Exception) {
            Log.e("GemmaManager", "Task extraction inference error: ${e.message}")
            fallbackTasks
        }
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

    private fun buildPrompt(
        userInput: String,
        memoryContext: String,
        taskContext: String,
        onlineData: String
    ): String {
        val sb = StringBuilder()

        if (memoryContext.isNotBlank()) {
            sb.append("CONTEXT FROM THE USER'S RECORDINGS:\n$memoryContext\n\n")
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

}
