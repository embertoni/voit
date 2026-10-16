package com.voit.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [NoteRepository] for ViewModel tests and the text-command simulator (before
 * the DB is wired in). Mirrors the Room implementation's contract: atomic create/update,
 * duplicate prevention, soft archive/delete, and search over title/tags/blocks.
 *
 * Not thread-safe; intended for single-threaded tests and simple previews.
 */
class FakeNoteRepository : NoteRepository {
    private val notes = MutableStateFlow<Map<String, StoredNote>>(emptyMap())

    private data class StoredNote(val note: Note, val archived: Boolean, val deleted: Boolean)

    override fun observeNotes(includeArchived: Boolean): Flow<List<Note>> =
        notes.map { map ->
            map.values
                .filter { !it.deleted && (includeArchived || !it.archived) }
                .map { it.note }
                .sortedByDescending { it.updatedAt }
        }

    override suspend fun getNote(id: String): Note? =
        notes.value[id]?.takeIf { !it.deleted }?.note

    override suspend fun create(note: Note) {
        if (notes.value.containsKey(note.id)) {
            throw RepositoryConflictException("Note ${note.id} already exists")
        }
        notes.value = notes.value + (note.id to StoredNote(note, archived = false, deleted = false))
    }

    override suspend fun update(note: Note) {
        val existing = notes.value[note.id]
            ?: throw RepositoryConflictException("Note ${note.id} does not exist")
        notes.value = notes.value + (note.id to existing.copy(note = note))
    }

    override suspend fun archive(id: String) = setArchived(id, true)
    override suspend fun unarchive(id: String) = setArchived(id, false)

    private fun setArchived(id: String, archived: Boolean) {
        val existing = notes.value[id] ?: return
        notes.value = notes.value + (id to existing.copy(archived = archived))
    }

    override suspend fun delete(id: String) {
        val existing = notes.value[id] ?: return
        notes.value = notes.value + (id to existing.copy(deleted = true))
    }

    override suspend fun search(query: String): List<Note> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return notes.value.values
            .filter { !it.deleted }
            .map { it.note }
            .filter { note -> note.matches(q) }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun exportAll(): List<Note> =
        notes.value.values.filter { !it.deleted }.map { it.note }

    private fun Note.matches(q: String): Boolean {
        if (title?.lowercase()?.contains(q) == true) return true
        if (tags.any { it.lowercase().contains(q) }) return true
        return blocks.any { block ->
            when (block) {
                is NoteBlock.Paragraph -> block.text
                is NoteBlock.Heading -> block.text
                is NoteBlock.Bullet -> block.text
                is NoteBlock.Task -> block.text
                is NoteBlock.Code -> block.text
                is NoteBlock.Quote -> block.text
            }.lowercase().contains(q)
        }
    }
}

/**
 * In-memory [TaskRepository] mirroring the Room implementation's contract.
 */
class FakeTaskRepository : TaskRepository {
    private val tasks = MutableStateFlow<Map<String, StoredTask>>(emptyMap())

    private data class StoredTask(val task: TaskItem, val deleted: Boolean)

    override fun observeTasks(includeCompleted: Boolean): Flow<List<TaskItem>> =
        tasks.map { map ->
            map.values
                .filter { !it.deleted && (includeCompleted || !it.task.completed) }
                .map { it.task }
                .sortedByDescending { it.updatedAt }
        }

    override suspend fun getTask(id: String): TaskItem? =
        tasks.value[id]?.takeIf { !it.deleted }?.task

    override suspend fun create(task: TaskItem) {
        if (tasks.value.containsKey(task.id)) {
            throw RepositoryConflictException("Task ${task.id} already exists")
        }
        tasks.value = tasks.value + (task.id to StoredTask(task, deleted = false))
    }

    override suspend fun update(task: TaskItem) {
        tasks.value[task.id] ?: throw RepositoryConflictException("Task ${task.id} does not exist")
        tasks.value = tasks.value + (task.id to StoredTask(task, deleted = false))
    }

    override suspend fun setCompleted(id: String, completed: Boolean) {
        val existing = tasks.value[id] ?: return
        tasks.value = tasks.value + (id to existing.copy(task = existing.task.copy(completed = completed)))
    }

    override suspend fun delete(id: String) {
        val existing = tasks.value[id] ?: return
        tasks.value = tasks.value + (id to existing.copy(deleted = true))
    }

    override suspend fun search(query: String): List<TaskItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return tasks.value.values
            .filter { !it.deleted }
            .map { it.task }
            .filter { it.title.lowercase().contains(q) || it.body?.lowercase()?.contains(q) == true }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun exportAll(): List<TaskItem> =
        tasks.value.values.filter { !it.deleted }.map { it.task }
}

/**
 * In-memory [CommandRepository] for tests and backup round-trips.
 */
class FakeCommandRepository : CommandRepository {
    private val commands = MutableStateFlow<Map<String, CommandDefinition>>(emptyMap())

    override fun observeCommands(): Flow<List<CommandDefinition>> =
        commands.map { it.values.sortedBy { c -> c.name } }

    override suspend fun getCommand(id: String): CommandDefinition? = commands.value[id]

    override suspend fun upsert(command: CommandDefinition) {
        commands.value = commands.value + (command.id to command)
    }

    override suspend fun delete(id: String) {
        commands.value = commands.value - id
    }

    override suspend fun exportAll(): List<CommandDefinition> = commands.value.values.toList()
}

/**
 * In-memory [SettingsRepository] for tests and the simulator.
 */
class FakeSettingsRepository(initial: AppSettings = AppSettings.DEFAULT) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }

    override suspend fun setListeningEnabled(enabled: Boolean) =
        update { it.copy(listeningEnabled = enabled) }

    override suspend fun setRecognitionLanguage(language: String) =
        update { it.copy(recognitionLanguage = language) }

    override suspend fun setCloudProcessingEnabled(enabled: Boolean) =
        update { it.copy(cloudProcessingEnabled = enabled) }

    override suspend fun setThemeMode(themeMode: String) =
        update { it.copy(themeMode = themeMode) }
}
