package com.aura.companion.ui.privacy

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
import androidx.compose.ui.unit.dp
import com.aura.companion.ai.NetworkCallLog
import com.aura.companion.ui.theme.*
import com.aura.companion.viewmodel.AuraViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PrivacyScreen(viewModel: AuraViewModel) {
    val logs = viewModel.networkLogs
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(16.dp)
    ) {
        Text("Privacy Shield", style = MaterialTheme.typography.headlineMedium, color = Purple80)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Every online call is logged here. Nothing else leaves your device.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy guarantees 2x2 grid
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuaranteeCard(Modifier.weight(1f), Icons.Filled.MicOff, "No Audio Upload", "Audio processed locally, never sent")
                GuaranteeCard(Modifier.weight(1f), Icons.Filled.Memory, "Local AI", "Gemma 4 runs on-device only")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuaranteeCard(Modifier.weight(1f), Icons.Filled.Lock, "Local Memory", "SQLite on-device, zero cloud sync")
                GuaranteeCard(Modifier.weight(1f), Icons.Filled.Visibility, "Transparent Net", "Every call logged in real time")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Network Calls (${logs.size})", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            TextButton(onClick = { viewModel.clearNetworkLogs() }) { Text("Clear", color = TextSecondary) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Surface(color = ListeningGreen.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = ListeningGreen)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("All clear!", style = MaterialTheme.typography.titleMedium, color = ListeningGreen)
                        Text("No network calls. All reasoning is 100% local.",
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs.reversed()) { log ->
                    Surface(
                        color = if (log.success) OnlineBlue.copy(alpha = 0.08f) else MutedRed.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                if (log.success) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = if (log.success) OnlineBlue else MutedRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(log.type, style = MaterialTheme.typography.labelSmall,
                                        color = if (log.success) OnlineBlue else MutedRed)
                                    Text(timeFormat.format(Date(log.timestamp)),
                                        style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                                Text("Query: ${log.query}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text(log.dataReturned, style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuaranteeCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Surface(color = DarkSurface, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = ListeningGreen, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
