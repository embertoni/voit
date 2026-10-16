package com.voit.domain

/**
 * Orchestrates a complete local backup/restore across the repositories and settings.
 *
 * Export reads a snapshot of every note/task/command plus settings into a [Backup] and
 * serializes it (optionally encrypted). Import deserializes and writes the content into a
 * target store, upserting so a restore into a fresh store reproduces the content exactly.
 *
 * Lives in the domain (pure Kotlin) so it can be exercised against the in-memory fakes and
 * reused by the Room-backed stores unchanged.
 */
class BackupManager(
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val commands: CommandRepository,
    private val settings: SettingsRepository,
    private val encryptor: ExportEncryptor = NoOpExportEncryptor,
) {

    /** Build a [Backup] snapshot of the current store. */
    suspend fun snapshot(currentSettings: AppSettings): Backup = Backup(
        notes = notes.exportAll(),
        tasks = tasks.exportAll(),
        commands = commands.exportAll(),
        settings = currentSettings,
    )

    /** Serialize a snapshot to bytes (encrypted via [encryptor]). */
    suspend fun exportTo(currentSettings: AppSettings): ByteArray =
        BackupSerializer.serialize(snapshot(currentSettings), encryptor)

    /** Deserialize [bytes] into a [Backup] without writing anything. */
    fun decode(bytes: ByteArray): Backup = BackupSerializer.deserialize(bytes, encryptor)

    /**
     * Restore a [Backup] into the target repositories. Existing entries with matching ids
     * are updated; new ones are created. Settings are overwritten with the backup's values.
     */
    suspend fun restore(backup: Backup) {
        for (note in backup.notes) {
            if (notes.getNote(note.id) == null) notes.create(note) else notes.update(note)
        }
        for (task in backup.tasks) {
            if (tasks.getTask(task.id) == null) tasks.create(task) else tasks.update(task)
        }
        for (command in backup.commands) {
            commands.upsert(command)
        }
        settings.update { backup.settings }
    }

    /** Deserialize [bytes] and restore into the target store in one step. */
    suspend fun importFrom(bytes: ByteArray) {
        restore(decode(bytes))
    }
}
