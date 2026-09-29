package com.aura.companion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.companion.capture.CaptureState
import com.aura.companion.data.db.Conversation
import com.aura.companion.data.db.ConversationSegment
import com.aura.companion.data.db.ConversationStatus
import com.aura.companion.ui.components.formatClock
import com.aura.companion.ui.components.formatTime
import com.aura.companion.ui.theme.RecordingRed
import com.aura.companion.viewmodel.AuraViewModel
import com.aura.companion.viewmodel.ChatMessage

private sealed interface FeedItem {
    val time: Long
    val key: String

    data class ConversationItem(val conversation: Conversation) : FeedItem {
        override val time get() = conversation.startedAt
        override val key get() = "conversation-${conversation.id}"
    }

    data class ChatItem(val message: ChatMessage) : FeedItem {
        override val time get() = message.timestamp
        override val key get() = "chat-${message.id}"
    }
}

private val SUGGESTIONS = listOf("Summarize my day", "What are my tasks?", "What did we talk about today?")

@Composable
fun HomeScreen(viewModel: AuraViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val hearingSpeech by viewModel.hearingSpeech.collectAsState()
    val captureError by viewModel.captureError.collectAsState()
    val onlineActive by viewModel.onlineActive.collectAsState()
    val modelFile by viewModel.modelFileName.collectAsState()
    val backlog by viewModel.backlog.collectAsState()
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    val openConversation by viewModel.openConversation.collectAsState()
    val openTranscript by viewModel.openTranscript.collectAsState(initial = emptyList())

    // Chronological feed of summarized conversations and chat, newest at the bottom
    val feed = remember(conversations, uiState.chat) {
        (conversations.map { FeedItem.ConversationItem(it) } + uiState.chat.map { FeedItem.ChatItem(it) })
            .sortedBy { it.time }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(feed.size, uiState.thinking, openTranscript.size) {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Aura", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (onlineActive) {
                AssistChip(
                    onClick = {},
                    label = { Text("Online lookup") },
                    leadingIcon = { Icon(Icons.Outlined.CloudSync, contentDescription = null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                )
            }
        }

        if (modelFile == null) {
            Banner("Gemma model not found. Choose the .litertlm file in Settings. Conversations are kept and summarized once it's available.")
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (feed.isEmpty() && openConversation == null) {
                item(key = "intro") { Intro() }
            }
            items(feed, key = { it.key }) { item ->
                when (item) {
                    is FeedItem.ConversationItem -> ConversationCard(item.conversation, viewModel)
                    is FeedItem.ChatItem -> ChatBubble(item.message)
                }
            }
            if (uiState.thinking) {
                item(key = "thinking") { ThinkingBubble() }
            }
            if (backlog > 0) {
                item(key = "processing") {
                    StatusLine(
                        if (backlog == 1) "Summarizing a conversation on this phone…"
                        else "Summarizing $backlog conversations on this phone…",
                        progress = true
                    )
                }
            }
            openConversation?.let { open ->
                item(key = "live") { LiveConversationCard(open, openTranscript, hearingSpeech) }
            }
            if (openConversation == null) {
                item(key = "listening") {
                    StatusLine(
                        when (captureState) {
                            CaptureState.LISTENING -> "Listening. New conversations show up here."
                            CaptureState.STARTING -> "Starting to listen…"
                            CaptureState.ERROR -> captureError ?: "Microphone error"
                            CaptureState.STOPPED -> "Listening is off. Tap the mic to start."
                        }
                    )
                }
            }
        }

        if (uiState.chat.isEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SUGGESTIONS) { suggestion ->
                    SuggestionChip(
                        onClick = {
                            if (suggestion == SUGGESTIONS[0]) viewModel.requestDaySummary()
                            else viewModel.sendMessage(suggestion)
                        },
                        label = { Text(suggestion) }
                    )
                }
            }
        }
        AskField(enabled = !uiState.thinking, onSend = viewModel::sendMessage)
    }
}

@Composable
private fun Intro() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 8.dp)) {
        Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Aura listens in the background", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Speech is transcribed on this phone. After 2 minutes of quiet, the conversation is summarized " +
                "on-device into tasks and memories. Nothing is uploaded.\n\n" +
                "Tap the mic below to start listening, and type here to ask Aura anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusLine(text: String, progress: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ConversationCard(conversation: Conversation, viewModel: AuraViewModel) {
    var expanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val time = formatTime(conversation.startedAt) + "–" + formatClock(conversation.lastSpeechAt)

    if (conversation.status == ConversationStatus.DISCARDED) {
        Text(
            "$time · Short or casual talk, nothing saved",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        return
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            when (conversation.status) {
                ConversationStatus.DONE -> {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                    if (conversation.summary.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(conversation.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                    val counts = listOfNotNull(
                        conversation.taskCount.takeIf { it > 0 }?.let { if (it == 1) "1 task" else "$it tasks" },
                        conversation.memoryCount.takeIf { it > 0 }?.let { if (it == 1) "1 memory" else "$it memories" }
                    )
                    if (counts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            counts.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                ConversationStatus.FAILED -> {
                    Text("Couldn't summarize this conversation", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { viewModel.retryConversation(conversation.id) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Try again")
                    }
                }
                else -> Text(
                    "Waiting to be summarized…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Hide transcript" else "Show transcript")
            }
            if (expanded) {
                val segments by viewModel.transcript(conversation.id).collectAsState(initial = emptyList())
                Transcript(segments)
            }
        }
    }
}

@Composable
private fun LiveConversationCard(conversation: Conversation, segments: List<ConversationSegment>, hearingSpeech: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(RecordingRed, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Conversation in progress · since ${formatClock(conversation.startedAt)}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(8.dp))
            Transcript(segments.takeLast(4))
            if (hearingSpeech) {
                Spacer(Modifier.height(4.dp))
                Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Transcript(segments: List<ConversationSegment>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            Row {
                Text(
                    formatClock(segment.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(44.dp).padding(top = 2.dp)
                )
                Text(segment.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (message.fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (message.fromUser) 18.dp else 4.dp,
                bottomEnd = if (message.fromUser) 4.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Thinking on-device…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AskField(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank() && enabled) {
            onSend(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Ask Aura…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(onClick = { send() }, enabled = enabled && text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    )
}
