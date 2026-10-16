package com.voit

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.voit.feature.commands.CommandEditorScreen
import com.voit.feature.commands.CommandsScreen
import com.voit.feature.commands.CommandsViewModel
import com.voit.feature.notes.NoteEditorScreen
import com.voit.feature.notes.NoteEditorViewModel
import com.voit.feature.notes.NotesScreen
import com.voit.feature.notes.NotesViewModel
import com.voit.feature.settings.SettingsScreen
import com.voit.feature.settings.SettingsViewModel
import com.voit.feature.simulator.SimulatorScreen
import com.voit.feature.simulator.SimulatorViewModel
import com.voit.feature.tasks.TasksScreen
import com.voit.feature.tasks.TasksViewModel

/** Top-level navigation destinations (the bottom bar entries). */
private enum class VoitDestination(val route: String, val label: String, val icon: ImageVector) {
    Simulator("simulator", "Simulator", Icons.Filled.Build),
    Notes("notes", "Notes", Icons.Filled.Edit),
    Tasks("tasks", "Tasks", Icons.Filled.CheckCircle),
    Commands("commands", "Commands", Icons.AutoMirrored.Filled.List),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

private const val NOTE_EDITOR_ROUTE = "note_editor"
private const val COMMAND_EDITOR_ROUTE = "command_editor"

/**
 * Root Voit composable: a bottom-nav scaffold hosting the Phase-1 feature screens, with the
 * Simulator as the home destination (it is the demonstrable Phase-1 exit path). ViewModels
 * are built by [factory] from the manual DI container.
 */
@Composable
fun VoitApp(factory: VoitViewModelFactory) {
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
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = VoitDestination.Simulator.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(VoitDestination.Simulator.route) {
                val vm: SimulatorViewModel = viewModel(factory = factory)
                SimulatorScreen(viewModel = vm)
            }
            composable(VoitDestination.Notes.route) {
                val vm: NotesViewModel = viewModel(factory = factory)
                NotesScreen(
                    viewModel = vm,
                    onOpenNote = { id -> navController.navigate("$NOTE_EDITOR_ROUTE/$id") },
                )
            }
            composable("$NOTE_EDITOR_ROUTE/{noteId}") { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                val vm: NoteEditorViewModel = viewModel(factory = factory.noteEditor(noteId))
                NoteEditorScreen(viewModel = vm, onNavigateBack = { navController.popBackStack() })
            }
            composable(VoitDestination.Tasks.route) {
                val vm: TasksViewModel = viewModel(factory = factory)
                TasksScreen(viewModel = vm)
            }
            composable(VoitDestination.Commands.route) {
                val vm: CommandsViewModel = viewModel(factory = factory)
                CommandsScreen(
                    viewModel = vm,
                    onNewCommand = { navController.navigate(COMMAND_EDITOR_ROUTE) },
                    onEditCommand = { navController.navigate(COMMAND_EDITOR_ROUTE) },
                )
            }
            composable(COMMAND_EDITOR_ROUTE) {
                // Share the Commands VM instance scoped to the Commands back-stack entry so
                // the editor sees the draft prepared by the list screen.
                val parentEntry = remember(navController) {
                    navController.getBackStackEntry(VoitDestination.Commands.route)
                }
                val vm: CommandsViewModel = viewModel(viewModelStoreOwner = parentEntry, factory = factory)
                CommandEditorScreen(viewModel = vm, onDone = { navController.popBackStack() })
            }
            composable(VoitDestination.Settings.route) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(viewModel = vm)
            }
        }
    }
}
