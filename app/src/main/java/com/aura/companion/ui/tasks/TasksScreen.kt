package com.aura.companion.ui.tasks

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aura.companion.data.db.Task
import com.aura.companion.ui.components.EmptyState
import com.aura.companion.ui.components.ScreenHeader
import com.aura.companion.ui.components.SectionLabel
import com.aura.companion.viewmodel.AuraViewModel

@Composable
fun TasksScreen(viewModel: AuraViewModel) {
    val tasks by viewModel.tasks.collectAsState(initial = emptyList())
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val pending = tasks.filter { !it.isCompleted }
    val completed = tasks.filter { it.isCompleted }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Tasks",
            subtitle = if (pending.isEmpty()) "Nothing pending" else "${pending.size} pending"
        ) {
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add task")
            }
        }

        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.TaskAlt,
                title = "No tasks yet",
                body = "Tasks from your conversations show up here. Tap the mic to add one by voice, e.g. \"call the bank tomorrow at 10\"."
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(pending, key = { it.id }) { task ->
                TaskRow(task, onToggle = { viewModel.toggleTaskComplete(task) }, onDelete = { viewModel.deleteTask(task.id) })
            }
            if (completed.isNotEmpty()) {
                item(key = "completed-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Completed", Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearCompletedTasks() }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Clear")
                        }
                    }
                }
                items(completed, key = { it.id }) { task ->
                    TaskRow(task, onToggle = { viewModel.toggleTaskComplete(task) }, onDelete = { viewModel.deleteTask(task.id) })
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { title, due ->
                viewModel.addTaskManually(title, due)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onDelete: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.isCompleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                )
                val details = listOfNotNull(
                    task.dueTime.takeIf { it.isNotBlank() },
                    "From a conversation".takeIf { task.conversationId != null }
                )
                if (details.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (task.dueTime.isNotBlank()) Icons.Outlined.Schedule else Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Close, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.outline)
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Task") }, singleLine = true)
                OutlinedTextField(value = due, onValueChange = { due = it }, label = { Text("When (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onAdd(title, due) }, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
