package com.voit

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.voit.feature.commands.CommandsScreen
import com.voit.feature.notes.NotesScreen
import com.voit.feature.settings.SettingsScreen
import com.voit.feature.simulator.SimulatorScreen
import com.voit.feature.tasks.TasksScreen

private enum class VoitDestination(val route: String, val label: String) {
    Notes("notes", "Notes"),
    Tasks("tasks", "Tasks"),
    Commands("commands", "Commands"),
    Simulator("simulator", "Simulator"),
    Settings("settings", "Settings"),
}

/**
 * Root Voit composable: a bottom-nav scaffold wiring the Phase 1 feature screens.
 */
@Composable
fun VoitApp() {
    val navController = rememberNavController()
    val destinations = VoitDestination.entries

    Scaffold(
        bottomBar = {
            val backStackEntry = navController.currentBackStackEntryAsState().value
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                destinations.forEach { dest ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = VoitDestination.Notes.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(VoitDestination.Notes.route) { NotesScreen() }
            composable(VoitDestination.Tasks.route) { TasksScreen() }
            composable(VoitDestination.Commands.route) { CommandsScreen() }
            composable(VoitDestination.Simulator.route) { SimulatorScreen() }
            composable(VoitDestination.Settings.route) { SettingsScreen() }
        }
    }
}
