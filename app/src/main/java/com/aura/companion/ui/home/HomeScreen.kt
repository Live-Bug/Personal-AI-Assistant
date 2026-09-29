package com.aura.companion.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.aura.companion.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.companion.ui.theme.*
import com.aura.companion.util.PathUtils
import com.aura.companion.viewmodel.AuraViewModel
import com.aura.companion.viewmodel.TranscriptEntry
import com.aura.companion.viewmodel.TranscriptType
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(viewModel: AuraViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showAskAuraDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolvedPath = PathUtils.getPathFromUri(context, uri)
            if (resolvedPath != null) {
                viewModel.loadModel(resolvedPath)
            } else {
                Toast.makeText(context, "Could not resolve file path", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(uiState.transcriptEntries.size) {
        if (uiState.transcriptEntries.isNotEmpty()) {
            listState.animateScrollToItem(uiState.transcriptEntries.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // ===== HEADER =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "Aura Logo",
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AURA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Purple80)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Formatted short label to prevent pushing right badge off screen
                    val modelLabel = when {
                        uiState.isModelLoading -> "Loading..."
                        !uiState.isModelReady -> "Select Model"
                        uiState.activeModelPath.contains("gemma-4-e2b", ignoreCase = true) ->
                            if (uiState.activeBackend.isNotBlank()) "E2B · ${uiState.activeBackend}" else "Gemma 4"
                        uiState.activeModelPath.contains("gemma", ignoreCase = true) -> "Gemma"
                        uiState.activeModelPath.isNotBlank() -> {
                            val name = uiState.activeModelPath.substringAfterLast('/').substringBeforeLast('.')
                            if (name.length > 9) name.take(8) + "…" else name
                        }
                        else -> "Gemma 4"
                    }

                    // Model path / selector badge
                    Surface(
                        color = if (uiState.isModelReady) Purple80.copy(alpha = 0.15f)
                                else if (uiState.isModelLoading) DarkSurfaceVariant
                                else MutedRed.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = "Select Model",
                                tint = if (uiState.isModelReady) Purple80
                                       else if (uiState.isModelLoading) TextSecondary
                                       else MutedRed,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                modelLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isModelReady) Purple80
                                        else if (uiState.isModelLoading) TextSecondary
                                        else MutedRed,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Online / Offline Status badge
                    Surface(
                        color = if (uiState.onlineCallActive) OnlineBlue.copy(alpha = 0.2f)
                                else DarkSurfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (uiState.onlineCallActive) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                                contentDescription = null,
                                tint = if (uiState.onlineCallActive) OnlineBlue else OfflineGray,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (uiState.onlineCallActive) "Online" else "Local",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.onlineCallActive) OnlineBlue else OfflineGray,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== LISTENING INDICATOR =====
            ListeningStatusBar(
                isListening = uiState.isListening,
                isMuted = uiState.isMuted,
                isProcessing = uiState.isProcessing,
                statusText = uiState.statusText,
                todayCount = uiState.todaySegmentCount,
                onToggle = { viewModel.toggleListening() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ===== LIVE TRANSCRIPT FEED =====
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.transcriptEntries.isEmpty() && !uiState.isModelLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Hearing, contentDescription = null,
                                    tint = TextSecondary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Aura is ready",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary.copy(alpha = 0.8f))
                                Text("Tap 'Listen' to capture conversations and extract memories.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                items(uiState.transcriptEntries, key = { it.id }) { entry ->
                    TranscriptCard(entry)
                }


                // Partial speech (ghost card)
                if (uiState.partialSpeech.isNotBlank()) {
                    item {
                        Surface(
                            color = DarkSurface.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().alpha(0.6f)
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                Icon(Icons.Filled.Hearing, contentDescription = null,
                                    tint = ListeningGreen, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    uiState.partialSpeech + "...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Model loading indicator
            if (uiState.isModelLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = Purple80
                )
                Text("Loading Gemma 4 E2B on GPU...",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center)
            }

            if (uiState.modelError.isNotBlank() && !uiState.isModelLoading) {
                Surface(
                    color = MutedRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "⚠️ ${uiState.modelError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedRed,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Purple80,
                                contentColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Select Model File (.litertlm)", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== BOTTOM CONTROLS =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ask Aura button
                FilledIconButton(
                    onClick = { showAskAuraDialog = true },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Purple30
                    )
                ) {
                    Icon(
                        Icons.Filled.QuestionAnswer,
                        contentDescription = "Ask Aura",
                        tint = Purple80,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Main Listen / Pause toggle
                val isListening = uiState.isListening
                FloatingActionButton(
                    onClick = { viewModel.toggleListening() },
                    containerColor = if (isListening) ListeningGreen else DarkSurfaceVariant,
                    contentColor = if (isListening) DarkBackground else TextPrimary,
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isListening) "Pause Listening" else "Start Listening",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Day summary button
                FilledIconButton(
                    onClick = { viewModel.requestDaySummary() },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = DarkSurfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Filled.Summarize,
                        contentDescription = "Day Summary",
                        tint = Cyan80,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Button labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("Ask Aura",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(if (uiState.isListening) "Listening" else "Tap to Listen",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.isListening) ListeningGreen else TextSecondary)
                Text("Summary",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }

        // Ask Aura Dialog
        if (showAskAuraDialog) {
            AskAuraDialog(
                onDismiss = { showAskAuraDialog = false },
                onAsk = { query ->
                    viewModel.askQuestion(query)
                    showAskAuraDialog = false
                },
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun AskAuraDialog(
    onDismiss: () -> Unit,
    onAsk: (String) -> Unit,
    viewModel: AuraViewModel
) {
    var queryText by remember { mutableStateOf("") }
    var isVoiceListening by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Psychology, contentDescription = null, tint = Purple80)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ask Aura", color = TextPrimary)
            }
        },
        text = {
            Column {
                Text(
                    "Query your second brain about today's conversations, tasks, or information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Query input field with voice mic button
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = { Text("Ask anything or add a task...", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                isVoiceListening = !isVoiceListening
                                if (isVoiceListening) {
                                    viewModel.startQueryListening()
                                }
                            }
                        ) {
                            Icon(
                                if (isVoiceListening) Icons.Filled.Mic else Icons.Filled.MicNone,
                                contentDescription = "Voice input",
                                tint = if (isVoiceListening) ListeningGreen else Purple80
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple80,
                        unfocusedBorderColor = DarkSurfaceVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Quick suggestions:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))

                // Suggestion chips
                val suggestions = listOf(
                    "What did I discuss today?",
                    "What are my pending tasks?",
                    "Add task: submit project at 5pm",
                    "What were the key takeaways?"
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.forEach { suggestion ->
                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAsk(suggestion)
                                }
                        ) {
                            Text(
                                text = "💬 $suggestion",
                                style = MaterialTheme.typography.labelSmall,
                                color = Cyan80,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (queryText.isNotBlank()) {
                        onAsk(queryText)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple40)
            ) {
                Text("Ask", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
fun ListeningStatusBar(
    isListening: Boolean,
    isMuted: Boolean,
    isProcessing: Boolean,
    statusText: String,
    todayCount: Int,
    onToggle: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )

    Surface(
        color = when {
            isMuted -> MutedRed.copy(alpha = 0.1f)
            isProcessing -> Cyan40.copy(alpha = 0.1f)
            isListening -> ListeningGreen.copy(alpha = 0.1f)
            else -> DarkSurface
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing dot
            val dotColor = when {
                isMuted -> MutedRed
                isProcessing -> Cyan40
                isListening -> ListeningGreen
                else -> OfflineGray
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(if (isListening && !isMuted) pulseAlpha else 1f)
                    .background(dotColor, CircleShape)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isMuted -> "🔇 Muted"
                        isProcessing -> "🧠 Processing..."
                        isListening -> "👂 Listening (tap to pause)"
                        else -> "⏸️ Standby (tap to listen)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = dotColor
                )
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }

            if (todayCount > 0) {
                Surface(
                    color = Purple30.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "$todayCount captured",
                        style = MaterialTheme.typography.labelSmall,
                        color = Purple80,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptCard(entry: TranscriptEntry) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val time = timeFormat.format(Date(entry.timestamp))

    val bgColor: Color = when (entry.type) {
        TranscriptType.USER_SPEECH -> DarkSurface
        TranscriptType.AI_RESPONSE -> Purple30.copy(alpha = 0.3f)
        TranscriptType.AI_SUMMARY -> Cyan40.copy(alpha = 0.1f)
        TranscriptType.SYSTEM_MESSAGE -> DarkSurfaceVariant
    }
    val label: String = when (entry.type) {
        TranscriptType.USER_SPEECH -> "Heard"
        TranscriptType.AI_RESPONSE -> "Aura"
        TranscriptType.AI_SUMMARY -> "Summary"
        TranscriptType.SYSTEM_MESSAGE -> "System"
    }
    val labelColor: Color = when (entry.type) {
        TranscriptType.USER_SPEECH -> Cyan80
        TranscriptType.AI_RESPONSE -> Purple80
        TranscriptType.AI_SUMMARY -> Cyan80
        TranscriptType.SYSTEM_MESSAGE -> OfflineGray
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (entry.type) {
                        TranscriptType.USER_SPEECH -> Icons.Filled.RecordVoiceOver
                        TranscriptType.AI_RESPONSE -> Icons.Filled.Psychology
                        TranscriptType.AI_SUMMARY -> Icons.Filled.AutoAwesome
                        TranscriptType.SYSTEM_MESSAGE -> Icons.Filled.Info
                    }
                    Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = labelColor)
                }
                Text(time, style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.6f))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}
