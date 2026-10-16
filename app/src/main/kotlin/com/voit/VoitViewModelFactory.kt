package com.voit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.voit.feature.commands.CommandsViewModel
import com.voit.feature.notes.NoteEditorViewModel
import com.voit.feature.notes.NotesViewModel
import com.voit.feature.settings.SettingsViewModel
import com.voit.feature.simulator.SimulatorViewModel
import com.voit.feature.tasks.TasksViewModel

/**
 * A single [ViewModelProvider.Factory] that builds every Phase-1 ViewModel from the manual
 * [VoitContainer]. The note editor needs a per-note id, so it has a dedicated factory
 * builder ([noteEditor]) rather than being created by [create].
 */
class VoitViewModelFactory(private val container: VoitContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(SimulatorViewModel::class.java) ->
            SimulatorViewModel(
                processor = container.transcriptProcessor,
                noteRepository = container.noteRepository,
                taskRepository = container.taskRepository,
            ) as T

        modelClass.isAssignableFrom(NotesViewModel::class.java) ->
            NotesViewModel(repository = container.noteRepository) as T

        modelClass.isAssignableFrom(TasksViewModel::class.java) ->
            TasksViewModel(repository = container.taskRepository) as T

        modelClass.isAssignableFrom(CommandsViewModel::class.java) ->
            CommandsViewModel(
                repository = container.commandRepository,
                registry = container.actionRegistry,
            ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(
                settingsRepository = container.settingsRepository,
                noteRepository = container.noteRepository,
                taskRepository = container.taskRepository,
                commandRepository = container.commandRepository,
            ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }

    /** Build a factory for the note editor bound to a specific [noteId]. */
    fun noteEditor(noteId: String): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteEditorViewModel(
                    repository = container.noteRepository,
                    noteId = noteId,
                ) as T
        }
}
