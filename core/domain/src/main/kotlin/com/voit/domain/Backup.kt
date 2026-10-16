package com.voit.domain

/**
 * A complete local backup snapshot: every note, task, command and the current settings.
 * This is the unit of the full export/restore round-trip.
 */
data class Backup(
    val version: Int = CURRENT_VERSION,
    val notes: List<Note> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val commands: List<CommandDefinition> = emptyList(),
    val settings: AppSettings = AppSettings.DEFAULT,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Encryption boundary for backup payloads.
 *
 * Phase 1 ships [NoOpExportEncryptor] (an explicit, documented pass-through) so the
 * export/restore content round-trip works end-to-end without bundling a key/password
 * flow yet. There are NO hard-coded secrets. A real implementation (user-supplied
 * password -> KDF -> AEAD) lands in a later phase; see docs/privacy.md.
 */
interface ExportEncryptor {
    /** Transform plaintext backup bytes into the on-disk representation. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Reverse [encrypt]. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Pass-through encryptor. Explicitly performs no encryption; the backup is stored as
 * plaintext JSON. Documented default for Phase 1 only.
 */
object NoOpExportEncryptor : ExportEncryptor {
    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
    override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
}

/**
 * Serializes a [Backup] to/from bytes via the shared [Json] codec, optionally passing the
 * payload through an [ExportEncryptor]. The content round-trip (serialize -> encryptor ->
 * deserialize) preserves all notes/tasks/commands/settings exactly.
 */
object BackupSerializer {

    fun serialize(backup: Backup, encryptor: ExportEncryptor = NoOpExportEncryptor): ByteArray {
        val json = Json.write(toMap(backup))
        return encryptor.encrypt(json.encodeToByteArray())
    }

    fun deserialize(bytes: ByteArray, encryptor: ExportEncryptor = NoOpExportEncryptor): Backup {
        val json = encryptor.decrypt(bytes).decodeToString()
        return fromMap(Json.parseObject(json))
    }

    // ---- Mapping ----------------------------------------------------------------

    private fun toMap(backup: Backup): Map<String, Any?> = mapOf(
        "version" to backup.version,
        "notes" to backup.notes.map { noteToMap(it) },
        "tasks" to backup.tasks.map { taskToMap(it) },
        "commands" to backup.commands.map { commandToMap(it) },
        "settings" to settingsToMap(backup.settings),
    )

    private fun fromMap(map: Map<String, Any?>): Backup = Backup(
        version = (map["version"] as? Double)?.toInt() ?: Backup.CURRENT_VERSION,
        notes = (map["notes"] as? List<*>).orEmptyMaps().map { noteFromMap(it) },
        tasks = (map["tasks"] as? List<*>).orEmptyMaps().map { taskFromMap(it) },
        commands = (map["commands"] as? List<*>).orEmptyMaps().map { commandFromMap(it) },
        settings = (map["settings"] as? Map<*, *>)?.let { settingsFromMap(it.asStringMap()) }
            ?: AppSettings.DEFAULT,
    )

    private fun noteToMap(note: Note): Map<String, Any?> = mapOf(
        "id" to note.id,
        "title" to note.title,
        "blocks" to NoteBlockCodec.encode(note.blocks),
        "tags" to note.tags,
        "createdAt" to note.createdAt,
        "updatedAt" to note.updatedAt,
        "source" to note.source.name,
        "transcriptLanguage" to note.transcriptLanguage,
        "reviewRequired" to note.reviewRequired,
    )

    private fun noteFromMap(raw: Any?): Note {
        val m = raw.asStringMap()
        return Note(
            id = m["id"] as String,
            title = m["title"] as? String,
            blocks = NoteBlockCodec.decode(m["blocks"] as? String ?: "[]"),
            tags = (m["tags"] as? List<*>).orEmpty().map { it as String },
            createdAt = (m["createdAt"] as Double).toLong(),
            updatedAt = (m["updatedAt"] as Double).toLong(),
            source = NoteSource.valueOf(m["source"] as? String ?: NoteSource.IMPORT.name),
            transcriptLanguage = m["transcriptLanguage"] as? String,
            reviewRequired = (m["reviewRequired"] as? Boolean) ?: false,
        )
    }

    private fun taskToMap(task: TaskItem): Map<String, Any?> = mapOf(
        "id" to task.id,
        "title" to task.title,
        "body" to task.body,
        "due" to task.due,
        "remindAt" to task.reminderConfig?.remindAtEpochMillis,
        "leadMinutes" to task.reminderConfig?.leadMinutes,
        "priority" to task.priority.name,
        "completed" to task.completed,
        "linkedNoteId" to task.linkedNoteId,
        "createdAt" to task.createdAt,
        "updatedAt" to task.updatedAt,
    )

    private fun taskFromMap(raw: Any?): TaskItem {
        val m = raw.asStringMap()
        val remindAt = (m["remindAt"] as? Double)?.toLong()
        val leadMinutes = (m["leadMinutes"] as? Double)?.toInt()
        val reminder = if (remindAt != null || leadMinutes != null) {
            ReminderConfig(remindAt, leadMinutes)
        } else {
            null
        }
        return TaskItem(
            id = m["id"] as String,
            title = m["title"] as String,
            body = m["body"] as? String,
            due = (m["due"] as? Double)?.toLong(),
            reminderConfig = reminder,
            priority = Priority.valueOf(m["priority"] as? String ?: Priority.NORMAL.name),
            completed = (m["completed"] as? Boolean) ?: false,
            linkedNoteId = m["linkedNoteId"] as? String,
            createdAt = (m["createdAt"] as Double).toLong(),
            updatedAt = (m["updatedAt"] as Double).toLong(),
        )
    }

    private fun commandToMap(cmd: CommandDefinition): Map<String, Any?> = mapOf(
        "id" to cmd.id,
        "name" to cmd.name,
        "enabled" to cmd.enabled,
        "triggerPhrases" to cmd.triggerPhrases,
        "intent" to cmd.intent,
        "argumentSchema" to CommandCodec.encodeArgumentSchema(cmd.argumentSchema),
        "confirmationPolicy" to cmd.confirmationPolicy.name,
        "actions" to CommandCodec.encodeActions(cmd.actions),
        "createdAt" to cmd.createdAt,
        "updatedAt" to cmd.updatedAt,
    )

    private fun commandFromMap(raw: Any?): CommandDefinition {
        val m = raw.asStringMap()
        return CommandDefinition(
            id = m["id"] as String,
            name = m["name"] as String,
            enabled = (m["enabled"] as? Boolean) ?: true,
            triggerPhrases = (m["triggerPhrases"] as? List<*>).orEmpty().map { it as String },
            intent = m["intent"] as String,
            argumentSchema = CommandCodec.decodeArgumentSchema(m["argumentSchema"] as? String ?: "[]"),
            confirmationPolicy = ConfirmationPolicy.valueOf(
                m["confirmationPolicy"] as? String ?: ConfirmationPolicy.IF_AMBIGUOUS.name,
            ),
            actions = CommandCodec.decodeActions(m["actions"] as? String ?: "[]"),
            createdAt = (m["createdAt"] as Double).toLong(),
            updatedAt = (m["updatedAt"] as Double).toLong(),
        )
    }

    private fun settingsToMap(s: AppSettings): Map<String, Any?> = mapOf(
        "listeningEnabled" to s.listeningEnabled,
        "wakePhrase" to s.wakePhrase,
        "recognitionLanguage" to s.recognitionLanguage,
        "replyVoice" to s.replyVoice,
        "asrModelId" to s.asrModelId,
        "ttsModelId" to s.ttsModelId,
        "wakeWordModelId" to s.wakeWordModelId,
        "cloudProcessingEnabled" to s.cloudProcessingEnabled,
        "speakerVerificationEnabled" to s.speakerVerificationEnabled,
        "diagnosticsEnabled" to s.diagnosticsEnabled,
        "reviewModeEnabled" to s.reviewModeEnabled,
    )

    private fun settingsFromMap(m: Map<String, Any?>): AppSettings = AppSettings(
        listeningEnabled = (m["listeningEnabled"] as? Boolean) ?: false,
        wakePhrase = m["wakePhrase"] as? String ?: AppSettings.DEFAULT_WAKE_PHRASE,
        recognitionLanguage = m["recognitionLanguage"] as? String
            ?: AppSettings.DEFAULT_RECOGNITION_LANGUAGE,
        replyVoice = m["replyVoice"] as? String ?: AppSettings.DEFAULT_REPLY_VOICE,
        asrModelId = m["asrModelId"] as? String,
        ttsModelId = m["ttsModelId"] as? String,
        wakeWordModelId = m["wakeWordModelId"] as? String,
        cloudProcessingEnabled = (m["cloudProcessingEnabled"] as? Boolean) ?: false,
        speakerVerificationEnabled = (m["speakerVerificationEnabled"] as? Boolean) ?: false,
        diagnosticsEnabled = (m["diagnosticsEnabled"] as? Boolean) ?: false,
        reviewModeEnabled = (m["reviewModeEnabled"] as? Boolean) ?: true,
    )

    // ---- helpers ----------------------------------------------------------------

    private fun List<*>?.orEmptyMaps(): List<Any?> = this ?: emptyList<Any?>()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asStringMap(): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
            ?: error("Expected a JSON object")
}
