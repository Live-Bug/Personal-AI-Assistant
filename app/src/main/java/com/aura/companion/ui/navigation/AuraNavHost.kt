package com.aura.companion.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aura.companion.ui.home.HomeScreen
import com.aura.companion.ui.memory.MemoryScreen
import com.aura.companion.ui.privacy.PrivacyScreen
import com.aura.companion.ui.tasks.TasksScreen
import com.aura.companion.ui.theme.DarkSurface
import com.aura.companion.viewmodel.AuraViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Aura", Icons.Filled.Home)
    object Memory : Screen("memory", "Memories", Icons.Filled.Psychology)
    object Tasks : Screen("tasks", "Tasks", Icons.Filled.TaskAlt)
    object Privacy : Screen("privacy", "Privacy", Icons.Filled.Shield)
}

val bottomNavItems = listOf(Screen.Home, Screen.Memory, Screen.Tasks, Screen.Privacy)

@Composable
fun AuraNavHost() {
    val navController = rememberNavController()
    val viewModel: AuraViewModel = viewModel()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDest = navBackStackEntry?.destination

                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
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
            composable(Screen.Memory.route) { MemoryScreen(viewModel) }
            composable(Screen.Tasks.route) { TasksScreen(viewModel) }
            composable(Screen.Privacy.route) { PrivacyScreen(viewModel) }
        }
    }
}
