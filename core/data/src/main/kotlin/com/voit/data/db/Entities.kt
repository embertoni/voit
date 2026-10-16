package com.voit.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for Voit's local store.
 *
 * Design notes tied to the privacy requirements:
 * - Note block content is serialized to a single [NoteEntity.blocks] JSON string column
 *   (per the spec's block model); tags are a serialized JSON string too.
 * - [ActionRunEntity] records only redacted codes: NO transcript, note/task text, or raw
 *   audio is ever stored for diagnostics.
 * - [VoiceProfileMetadataEntity] holds metadata only (engine/model version, enrollment
 *   date, threshold). No raw audio or biometric templates; it is deletable by the user.
 */

@Entity(
    tableName = "notes",
    indices = [Index("updatedAt"), Index("archived"), Index("deleted")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String?,
    /** Serialized List<NoteBlock> JSON (see NoteBlockCodec). */
    val blocks: String,
    /** Serialized List<String> JSON (see TagListCodec). */
    val tags: String,
    val source: String,
    val transcriptLanguage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
    val deleted: Boolean,
    val reviewRequired: Boolean,
)

@Entity(
    tableName = "tasks",
    indices = [Index("updatedAt"), Index("completed"), Index("linkedNoteId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String?,
    val due: Long?,
    val remindAtEpochMillis: Long?,
    val leadMinutes: Int?,
    val priority: String,
    val completed: Boolean,
    val linkedNoteId: String?,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "command_definitions",
    indices = [Index("name"), Index("enabled")],
)
data class CommandDefinitionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    /** Serialized List<String> JSON of trigger phrases. */
    val triggerPhrases: String,
    val intent: String,
    /** Serialized ArgumentSchema JSON (see CommandCodec). */
    val argumentSchema: String,
    /** Serialized List<ActionRequest> JSON (see CommandCodec). */
    val actions: String,
    val confirmationPolicy: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Audit record of a single action/workflow run. Contains ONLY redacted codes: no
 * transcript, note/task content, or raw audio. [errorCode] is a stable diagnostic code.
 */
@Entity(
    tableName = "action_runs",
    indices = [Index("commandId"), Index("startedAt")],
)
data class ActionRunEntity(
    @PrimaryKey val id: String,
    val commandId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    /** Terminal status code (e.g. SUCCESS, PARTIAL_FAILURE, CANCELLED). */
    val status: String,
    /** Redacted result code; never free text. */
    val resultCode: String?,
    /** Redacted error code; never a message with user content. */
    val errorCode: String?,
)

/**
 * Privacy-sensitive but metadata-only voice profile record. Deletable by the user.
 * Stores NO raw audio and NO biometric templates.
 */
@Entity(tableName = "voice_profile_metadata")
data class VoiceProfileMetadataEntity(
    @PrimaryKey val id: String,
    val engine: String,
    val modelVersion: String,
    val enrollmentDate: Long,
    val verificationThreshold: Double,
    /** Added in schema v2 (additive migration). Nullable; null until first verification. */
    val lastVerifiedAt: Long? = null,
)
