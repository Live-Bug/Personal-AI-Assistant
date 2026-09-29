package com.aura.companion.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aura.companion.ai.GemmaState
import com.aura.companion.pipeline.ConversationTracker
import com.aura.companion.ui.components.ScreenHeader
import com.aura.companion.ui.components.SectionLabel
import com.aura.companion.ui.components.formatTime
import com.aura.companion.util.PathUtils
import com.aura.companion.viewmodel.AuraViewModel

@Composable
fun SettingsScreen(viewModel: AuraViewModel) {
    val context = LocalContext.current
    val gemmaState by viewModel.gemmaState.collectAsState()
    val gemmaError by viewModel.gemmaError.collectAsState()
    val backend by viewModel.activeBackend.collectAsState()
    val modelFile by viewModel.modelFileName.collectAsState()
    val logs by viewModel.networkLogs.collectAsState()

    // Re-checked on resume, since the user changes it in system settings
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryUnrestricted = isBatteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = PathUtils.getPathFromUri(context, uri)
            if (path != null) viewModel.loadModel(path)
            else Toast.makeText(context, "Could not open that file", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader(title = "Settings") }

        // ---- Listening
        item { SectionLabel("Listening") }
        item {
            ListItem(
                headlineContent = { Text("Background battery use") },
                supportingContent = {
                    Text(
                        if (batteryUnrestricted) "Unrestricted, so listening keeps running with the screen off"
                        else "Restricted. Android may stop listening in the background. Tap to allow."
                    )
                },
                leadingContent = {
                    Icon(
                        if (batteryUnrestricted) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert,
                        contentDescription = null
                    )
                },
                modifier = Modifier.clickable(enabled = !batteryUnrestricted) {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            )
        }
        item {
            Note(
                "Turn listening on and off with the mic in the bottom bar. A conversation ends after " +
                    "${ConversationTracker.GAP_MS / 60_000} minutes of silence; Aura then summarizes it on this " +
                    "phone and pulls out tasks and memories."
            )
        }

        // ---- Model
        item { SectionLabel("On-device AI") }
        item {
            val backendName = backend ?: "GPU"
            ListItem(
                headlineContent = { Text("Language model") },
                supportingContent = {
                    Column {
                        Text(modelFile ?: "Gemma 4 E2B (.litertlm) not found")
                        Text(
                            when (gemmaState) {
                                GemmaState.NOT_LOADED -> "Not in memory. Loads when needed, unloads after 5 idle minutes."
                                GemmaState.LOADING -> "Loading…"
                                GemmaState.READY -> "Loaded on $backendName"
                                GemmaState.INFERRING -> "Working on $backendName"
                                GemmaState.ERROR -> gemmaError ?: "Failed to load"
                            },
                            color = if (gemmaState == GemmaState.ERROR) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                leadingContent = { Icon(Icons.Outlined.Memory, contentDescription = null) }
            )
        }
        item {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)
            ) {
                Text(if (modelFile == null) "Choose model file" else "Choose a different file")
            }
        }
        item {
            ListItem(
                headlineContent = { Text("Speech recognition") },
                supportingContent = { Text("Parakeet TDT 110M with Silero voice detection, on this phone") },
                leadingContent = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) }
            )
        }

        // ---- Privacy
        item { SectionLabel("Privacy") }
        item {
            ListItem(
                headlineContent = { Text("What stays on this phone") },
                supportingContent = {
                    Text(
                        "Audio is transcribed and discarded, never saved or uploaded. Transcripts, memories and " +
                            "tasks are in a local database. All reasoning runs on-device. Only weather and news " +
                            "lookups use the internet, and each one is listed below."
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) }
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Network calls (${logs.size})", Modifier.weight(1f))
                if (logs.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearNetworkLogs, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Clear")
                    }
                }
            }
        }
        if (logs.isEmpty()) {
            item { Note("None yet. Nothing has left this phone.") }
        }
        items(logs.reversed()) { log ->
            ListItem(
                headlineContent = { Text("${log.type}: ${log.query}") },
                supportingContent = { Text(log.dataReturned, maxLines = 2) },
                overlineContent = { Text(formatTime(log.timestamp)) },
                leadingContent = {
                    Icon(
                        if (log.success) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = if (log.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp, bottom = 8.dp)
    )
}

private fun isBatteryUnrestricted(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
