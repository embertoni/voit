package com.voit.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for [Note]s.
 *
 * Declared in the domain so UI/ViewModels depend on this abstraction, never on Room.
 * Implementations: a Room-backed one in `core:data` and an in-memory fake for tests and
 * the text-command simulator.
 *
 * Create/update are atomic: an implementation must persist a note and its structured
 * block content in a single transaction so a note is never stored half-written.
 */
interface NoteRepository {
    /** Observe all non-deleted notes, most-recently-updated first. */
    fun observeNotes(includeArchived: Boolean = false): Flow<List<Note>>

    suspend fun getNote(id: String): Note?

    /** Persist a new note atomically. Fails if a note with the same id already exists. */
    suspend fun create(note: Note)

    /** Update an existing note atomically. Fails if no note with that id exists. */
    suspend fun update(note: Note)

    /** Soft-archive (reversible). */
    suspend fun archive(id: String)

    suspend fun unarchive(id: String)

    /** Soft-delete (reversible via restore/import; excluded from normal queries). */
    suspend fun delete(id: String)

    /** Full-text-ish search over title, tags, and block content. */
    suspend fun search(query: String): List<Note>

    /** Snapshot of every note including archived (but excluding soft-deleted) for backup. */
    suspend fun exportAll(): List<Note>
}

/**
 * Persistence boundary for standalone [TaskItem]s. See [NoteRepository] for conventions.
 */
interface TaskRepository {
    fun observeTasks(includeCompleted: Boolean = true): Flow<List<TaskItem>>

    suspend fun getTask(id: String): TaskItem?

    suspend fun create(task: TaskItem)

    suspend fun update(task: TaskItem)

    suspend fun setCompleted(id: String, completed: Boolean)

    suspend fun delete(id: String)

    suspend fun search(query: String): List<TaskItem>

    suspend fun exportAll(): List<TaskItem>
}

/**
 * Persistence boundary for [CommandDefinition]s (needed for backup/restore round-trips).
 */
interface CommandRepository {
    fun observeCommands(): Flow<List<CommandDefinition>>

    suspend fun getCommand(id: String): CommandDefinition?

    suspend fun upsert(command: CommandDefinition)

    suspend fun delete(id: String)

    suspend fun exportAll(): List<CommandDefinition>
}

/**
 * Thrown when a create collides with an existing id, or an update targets a missing id.
 * Keeps the repository contract explicit about duplicate prevention.
 */
class RepositoryConflictException(message: String) : RuntimeException(message)
