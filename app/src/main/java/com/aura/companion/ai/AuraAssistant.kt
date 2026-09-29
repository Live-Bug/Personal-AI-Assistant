package com.aura.companion.ai

import com.aura.companion.data.db.AuraDatabase
import com.aura.companion.data.db.FtsQuery
import com.aura.companion.data.db.Memory
import com.aura.companion.data.db.MemorySource
import com.aura.companion.data.db.Task
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Answers what the user types on Home. Context comes from local memories, conversation
 * summaries and tasks; only weather/news go online.
 */
class AuraAssistant(
    db: AuraDatabase,
    private val gemma: GemmaManager,
    private val online: OnlineLookupManager
) {
    private val conversationDao = db.conversationDao()
    private val segmentDao = db.conversationSegmentDao()
    private val memoryDao = db.memoryDao()
    private val taskDao = db.taskDao()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** A question, a task ("remind me to…") or something to remember ("remember that…"). */
    suspend fun ask(text: String): String {
        if (isTaskCommand(text)) return describeAdded(addTasks(text))
        rememberPayload(text)?.let { return remember(it) }
        return answer(text)
    }

    suspend fun daySummary(): String {
        val startOfDay = startOfToday()
        val conversations = conversationDao.getSummarizedSince(startOfDay)
        val tasks = pendingTasksText()
        if (conversations.isEmpty() && tasks.isBlank()) {
            return "Nothing recorded today yet, and no pending tasks."
        }
        val dayText = conversations.joinToString("\n") {
            "[${timeFormat.format(Date(it.startedAt))}] ${it.title}: ${it.summary}"
        }.ifBlank { "No conversations summarized today." }
        return gemma.generateResponse(
            userInput = "Give me a short briefing of my day: the main things discussed, then outstanding tasks, " +
                "then one useful reminder. Use short bullet points.",
            memoryContext = "TODAY'S CONVERSATIONS:\n$dayText",
            taskContext = tasks
        )
    }

    private suspend fun answer(question: String): String {
        val onlineData = when (online.needsOnlineData(question)) {
            OnlineQueryType.WEATHER -> online.fetchWeather(online.extractCity(question))
            OnlineQueryType.NEWS -> online.fetchNews()
            else -> ""
        }

        val context = StringBuilder()
        val match = FtsQuery.from(question)
        val memories = (if (match != null) memoryDao.search(match, 6) else emptyList())
            .ifEmpty { memoryDao.getRecent(3) }
        if (memories.isNotEmpty()) {
            context.append("MEMORIES:\n")
            memories.forEach { context.append("- ${it.content} (${formatDay(it.timestamp)})\n") }
            context.append('\n')
        }

        val related = if (match != null) conversationDao.search(match, 3) else emptyList()
        val today = conversationDao.getSummarizedSince(startOfToday()).takeLast(5)
        val conversations = (related + today).distinctBy { it.id }
        if (conversations.isNotEmpty()) {
            context.append("CONVERSATION SUMMARIES:\n")
            conversations.forEach {
                context.append("- ${formatDay(it.startedAt)} ${timeFormat.format(Date(it.startedAt))}, ${it.title}: ${it.summary}\n")
            }
            context.append('\n')
        }

        // The conversation happening right now hasn't been summarized yet
        conversationDao.getOpen()?.let { open ->
            val lines = segmentDao.getForConversation(open.id).takeLast(15)
            if (lines.isNotEmpty()) {
                context.append("CURRENT CONVERSATION (live transcript):\n")
                lines.forEach { context.append("[${timeFormat.format(Date(it.timestamp))}] ${it.text}\n") }
            }
        }

        return gemma.generateResponse(
            userInput = question,
            memoryContext = context.toString(),
            taskContext = pendingTasksText(),
            onlineData = onlineData
        )
    }

    private suspend fun remember(content: String): String {
        val clean = content.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        if (clean.length < 3) return "I didn't catch anything to remember."
        memoryDao.insert(Memory(content = "$clean.", source = MemorySource.USER))
        return "Saved: $clean."
    }

    private suspend fun addTasks(text: String): List<Task> {
        return gemma.extractAllTasks(text).map { (title, due) ->
            val task = Task(title = title, dueTime = due)
            task.copy(id = taskDao.insert(task))
        }
    }

    private fun describeAdded(tasks: List<Task>): String = when (tasks.size) {
        0 -> "I couldn't find a task in that. Try \"remind me to…\"."
        1 -> "Added task: ${tasks[0].title}" + tasks[0].dueTime.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        else -> "Added ${tasks.size} tasks: " + tasks.joinToString("; ") {
            it.title + it.dueTime.takeIf { d -> d.isNotBlank() }?.let { d -> " ($d)" }.orEmpty()
        }
    }

    private suspend fun pendingTasksText(): String = taskDao.getPendingTasks().take(8).joinToString("\n") {
        "- ${it.title}" + it.dueTime.takeIf { d -> d.isNotBlank() }?.let { d -> " (due $d)" }.orEmpty()
    }

    // "remember that my passport expires in March" -> "my passport expires in March"
    private fun rememberPayload(text: String): String? {
        val match = Regex(
            """^\s*(?:(?:hey|ok|okay)\s+)?(?:aura[,\s]+)?(?:please\s+)?(?:remember|note|keep in mind|don'?t forget)\s+(?:that\s+)?(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        val payload = match.groupValues[1]
        // "remember to X" is a task, handled by isTaskCommand
        return payload.takeIf { !it.startsWith("to ", ignoreCase = true) }
    }

    private fun isTaskCommand(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "action item", "remind me", "reminder to", "remind us", "remember to", "don't forget to",
            "dont forget to", "do not forget to", "add task", "add a task", "create task",
            "create a task", "new task", "todo", "to-do", "to do:"
        ).any { lower.contains(it) }
    }

    private fun formatDay(time: Long): String {
        val start = startOfToday()
        return when {
            time >= start -> "today"
            time >= start - 86_400_000L -> "yesterday"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
