package com.aura.companion.ui.tasks

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aura.companion.data.db.Task
import com.aura.companion.ui.theme.*
import com.aura.companion.viewmodel.AuraViewModel

@Composable
fun TasksScreen(viewModel: AuraViewModel) {
    val tasks by viewModel.tasks.collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var newTaskText by remember { mutableStateOf("") }
    var newTaskTime by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Tasks", style = MaterialTheme.typography.headlineMedium, color = Purple80)
                val pending = tasks.count { !it.isCompleted }
                Text("$pending pending", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Purple40,
                modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Filled.Add, "Add", tint = TextPrimary) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(color = Cyan40.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = Cyan80, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Say \"Remind me to...\" and tasks are captured automatically.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.TaskAlt, contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No tasks yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    Text("Tell Aura what you need to do!", style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary.copy(alpha = 0.6f))
                }
            }
        } else {
            val pending = tasks.filter { !it.isCompleted }
            val completed = tasks.filter { it.isCompleted }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pending.isNotEmpty()) {
                    item { Text("PENDING", style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary, modifier = Modifier.padding(vertical = 4.dp)) }
                    items(pending, key = { it.id }) { task ->
                        TaskCard(task, { viewModel.toggleTaskComplete(task.id, !task.isCompleted) },
                            { viewModel.deleteTask(task.id) })
                    }
                }
                if (completed.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(8.dp))
                        Text("COMPLETED", style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, modifier = Modifier.padding(vertical = 4.dp)) }
                    items(completed, key = { it.id }) { task ->
                        TaskCard(task, { viewModel.toggleTaskComplete(task.id, !task.isCompleted) },
                            { viewModel.deleteTask(task.id) })
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = DarkSurface,
            title = { Text("Add Task", color = TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(value = newTaskText, onValueChange = { newTaskText = it },
                        label = { Text("Task") }, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = TextSecondary))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newTaskTime, onValueChange = { newTaskTime = it },
                        label = { Text("Time (optional, e.g. 3pm)") }, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = TextSecondary))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTaskText.isNotBlank()) { viewModel.addTaskManually(newTaskText, newTaskTime); newTaskText = ""; newTaskTime = ""; showAddDialog = false }
                }) { Text("Add", color = Purple80) }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = TextSecondary) } }
        )
    }
}

@Composable
fun TaskCard(task: Task, onToggle: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = if (task.isCompleted) DarkSurface.copy(alpha = 0.5f) else DarkSurface,
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = ListeningGreen, uncheckedColor = TextSecondary))
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium,
                    color = if (task.isCompleted) TextSecondary else TextPrimary,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null)
                if (task.dueTime.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = Cyan80, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(task.dueTime, style = MaterialTheme.typography.labelSmall, color = Cyan80)
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Delete",
                    tint = TextSecondary.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
            }
        }
    }
}
