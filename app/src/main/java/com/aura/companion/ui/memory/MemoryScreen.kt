package com.aura.companion.ui.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.companion.data.db.Memory
import com.aura.companion.data.db.MemorySource
import com.aura.companion.ui.components.EmptyState
import com.aura.companion.ui.components.ScreenHeader
import com.aura.companion.ui.components.formatTime
import com.aura.companion.viewmodel.AuraViewModel

@Composable
fun MemoryScreen(viewModel: AuraViewModel) {
    val memories by viewModel.memories.collectAsState(initial = emptyList())
    val query by viewModel.memoryQuery.collectAsState()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Memories", subtitle = "Facts Aura keeps, stored only on this phone") {
            if (memories.isNotEmpty() && query.isBlank()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Delete all memories")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setMemoryQuery,
            placeholder = { Text("Search memories") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setMemoryQuery("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (memories.isEmpty()) {
            if (query.isBlank()) {
                EmptyState(
                    icon = Icons.Outlined.Psychology,
                    title = "No memories yet",
                    body = "Aura picks out facts worth keeping from your conversations. Tap the mic and say " +
                        "\"remember my passport expires in March\" to add one, or ask a question to search."
                )
            } else {
                EmptyState(icon = Icons.Outlined.Search, title = "No matches", body = "Try a different word.")
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(memories, key = { it.id }) { memory ->
                MemoryRow(memory, onDelete = { viewModel.deleteMemory(memory.id) })
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all memories?") },
            text = { Text("This permanently removes every memory from this phone. Conversations and tasks are kept.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAllMemory(); confirmClear = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MemoryRow(memory: Memory, onDelete: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                val source = when (memory.source) {
                    MemorySource.CONVERSATION -> "From a conversation"
                    MemorySource.USER -> "You asked me to remember"
                }
                Text(
                    "${formatTime(memory.timestamp)} · $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Close, contentDescription = "Delete memory", tint = MaterialTheme.colorScheme.outline)
            }
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}
