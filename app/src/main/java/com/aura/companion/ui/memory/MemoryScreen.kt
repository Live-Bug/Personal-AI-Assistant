package com.aura.companion.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.companion.data.db.Memory
import com.aura.companion.ui.theme.*
import com.aura.companion.viewmodel.AuraViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MemoryScreen(viewModel: AuraViewModel) {
    val memories by viewModel.memories.collectAsState(initial = emptyList())
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Memories", style = MaterialTheme.typography.headlineMedium, color = Purple80)
                Text("Your second brain", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Purple30.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("${memories.size} stored",
                        style = MaterialTheme.typography.labelSmall, color = Purple80,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Filled.DeleteSweep, "Clear all", tint = MutedRed.copy(alpha = 0.7f))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Privacy banner
        Surface(
            color = ListeningGreen.copy(alpha = 0.08f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null,
                    tint = ListeningGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("All memories stored locally on your device. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (memories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Psychology, contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No memories yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    Text("Start talking and Aura will remember everything.",
                        style = MaterialTheme.typography.bodyMedium, color = TextSecondary.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(memories, key = { it.id }) { memory ->
                    MemoryCard(memory = memory, onDelete = { viewModel.deleteMemory(memory.id) })
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = DarkSurface,
            title = { Text("Clear All Memories?", color = TextPrimary) },
            text = { Text("This permanently deletes all conversation history from your device.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAllMemory(); showClearDialog = false }) {
                    Text("Clear", color = MutedRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}

@Composable
fun MemoryCard(memory: Memory, onDelete: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val date = dateFormat.format(Date(memory.timestamp))

    val categoryColor = when (memory.category) {
        "summary" -> Cyan80
        "task" -> ListeningGreen
        "weather" -> OnlineBlue
        "query" -> Purple80
        else -> TextSecondary
    }

    Surface(color = DarkSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = categoryColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                        Text(memory.category.uppercase(),
                            style = MaterialTheme.typography.labelSmall, color = categoryColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(date, style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.6f))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete",
                        tint = TextSecondary.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // User input (what was said)
            Text(memory.userInput, style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary.copy(alpha = 0.7f), maxLines = 3)

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = DarkSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // AI summary/response
            Row {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                    tint = Purple80, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(memory.aiResponse, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
        }
    }
}
