package com.aura.companion.ui.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aura.companion.capture.CaptureState
import com.aura.companion.ui.home.HomeScreen
import com.aura.companion.ui.memory.MemoryScreen
import com.aura.companion.ui.settings.SettingsScreen
import com.aura.companion.ui.tasks.TasksScreen
import com.aura.companion.ui.theme.RecordingRed
import com.aura.companion.viewmodel.AuraViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    object Tasks : Screen("tasks", "Tasks", Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt)
    object Memory : Screen("memory", "Memories", Icons.Outlined.Psychology, Icons.Filled.Psychology)
    object Settings : Screen("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

private val screens = listOf(Screen.Home, Screen.Tasks, Screen.Memory, Screen.Settings)

@Composable
fun AuraNavHost() {
    val navController = rememberNavController()
    val viewModel: AuraViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val hearingSpeech by viewModel.hearingSpeech.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val current = screens.firstOrNull { screen ->
        currentRoute?.hierarchy?.any { it.route == screen.route } == true
    } ?: Screen.Home

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val toggleListening = rememberListeningToggle(captureState, viewModel)

    fun navigate(screen: Screen) {
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                screens.forEachIndexed { index, screen ->
                    // The mic sits in the middle of the bar
                    if (index == 2) {
                        MicButton(captureState, hearingSpeech, onClick = toggleListening, modifier = Modifier.weight(1f))
                    }
                    val selected = screen == current
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigate(screen) },
                        icon = { Icon(if (selected) screen.selectedIcon else screen.icon, contentDescription = null) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(viewModel) }
            composable(Screen.Tasks.route) { TasksScreen(viewModel) }
            composable(Screen.Memory.route) { MemoryScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}

/**
 * Returns the mic button's click handler. Turning listening on first asks for the microphone
 * and notification permissions, then (once per install) the battery optimization exemption that
 * keeps listening alive with the screen off, and only then starts the foreground service.
 */
@Composable
private fun rememberListeningToggle(captureState: CaptureState, viewModel: AuraViewModel): () -> Unit {
    val context = LocalContext.current

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.startListening() // whatever the user chose; Settings shows the result
    }

    fun startAfterBatteryCheck() {
        val prefs = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName) || prefs.getBoolean(PREF_ASKED_BATTERY, false)) {
            viewModel.startListening()
            return
        }
        prefs.edit().putBoolean(PREF_ASKED_BATTERY, true).apply()
        try {
            batteryLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            )
        } catch (e: Exception) {
            viewModel.startListening() // some OEM builds don't offer this screen
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) startAfterBatteryCheck()
        else viewModel.showMessage("Aura needs microphone access to listen. You can allow it in the app's system settings.")
    }

    return {
        if (captureState != CaptureState.STOPPED) {
            viewModel.stopListening()
        } else {
            val missing = buildList {
                if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (missing.isEmpty()) startAfterBatteryCheck() else permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

private const val PREF_ASKED_BATTERY = "asked_battery_exemption"

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Off, starting, listening, and hearing speech right now: the one listening control in the app. */
@Composable
private fun MicButton(state: CaptureState, hearingSpeech: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hearing = state == CaptureState.LISTENING && hearingSpeech
    val scale by animateFloatAsState(if (hearing) 1.1f else 1f, label = "mic-scale")
    val colors = when (state) {
        CaptureState.LISTENING -> IconButtonDefaults.filledIconButtonColors(
            containerColor = RecordingRed, contentColor = Color.White
        )
        CaptureState.ERROR -> IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
        else -> IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    val label = when {
        hearing -> "Hearing"
        state == CaptureState.LISTENING -> "Listening"
        state == CaptureState.STARTING -> "Starting…"
        state == CaptureState.ERROR -> "Mic error"
        else -> "Listen"
    }

    Column(modifier = modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(52.dp).scale(scale), colors = colors) {
            when (state) {
                CaptureState.STARTING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                CaptureState.LISTENING -> Icon(
                    if (hearing) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                    contentDescription = "Stop listening"
                )
                CaptureState.ERROR -> Icon(Icons.Outlined.MicOff, contentDescription = "Stop listening")
                CaptureState.STOPPED -> Icon(Icons.Outlined.MicOff, contentDescription = "Start listening")
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
