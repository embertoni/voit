package com.voit.data.db

import com.voit.domain.CommandCodec
import com.voit.domain.CommandDefinition
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.Note
import com.voit.domain.NoteBlockCodec
import com.voit.domain.NoteSource
import com.voit.domain.Priority
import com.voit.domain.ReminderConfig
import com.voit.domain.TagListCodec
import com.voit.domain.TaskItem

/**
 * Pure mapping between Room entities and domain models. The structured block list and tag
 * lists are serialized to single string columns via the shared domain codecs, so the
 * on-disk format is identical to the backup format.
 */

internal fun Note.toEntity(archived: Boolean = false, deleted: Boolean = false): NoteEntity =
    NoteEntity(
        id = id,
        title = title,
        blocks = NoteBlockCodec.encode(blocks),
        tags = TagListCodec.encode(tags),
        source = source.name,
        transcriptLanguage = transcriptLanguage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        archived = archived,
        deleted = deleted,
        reviewRequired = reviewRequired,
    )

internal fun NoteEntity.toDomain(): Note =
    Note(
        id = id,
        title = title,
        blocks = NoteBlockCodec.decode(blocks),
        tags = TagListCodec.decode(tags),
        createdAt = createdAt,
        updatedAt = updatedAt,
        source = runCatching { NoteSource.valueOf(source) }.getOrDefault(NoteSource.IMPORT),
        transcriptLanguage = transcriptLanguage,
        reviewRequired = reviewRequired,
    )

internal fun TaskItem.toEntity(deleted: Boolean = false): TaskEntity =
    TaskEntity(
        id = id,
        title = title,
        body = body,
        due = due,
        remindAtEpochMillis = reminderConfig?.remindAtEpochMillis,
        leadMinutes = reminderConfig?.leadMinutes,
        priority = priority.name,
        completed = completed,
        linkedNoteId = linkedNoteId,
        deleted = deleted,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun TaskEntity.toDomain(): TaskItem {
    val reminder = if (remindAtEpochMillis != null || leadMinutes != null) {
        ReminderConfig(remindAtEpochMillis, leadMinutes)
    } else {
        null
    }
    return TaskItem(
        id = id,
        title = title,
        body = body,
        due = due,
        reminderConfig = reminder,
        priority = runCatching { Priority.valueOf(priority) }.getOrDefault(Priority.NORMAL),
        completed = completed,
        linkedNoteId = linkedNoteId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun CommandDefinition.toEntity(): CommandDefinitionEntity =
    CommandDefinitionEntity(
        id = id,
        name = name,
        enabled = enabled,
        triggerPhrases = TagListCodec.encode(triggerPhrases),
        intent = intent,
        argumentSchema = CommandCodec.encodeArgumentSchema(argumentSchema),
        actions = CommandCodec.encodeActions(actions),
        confirmationPolicy = confirmationPolicy.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun CommandDefinitionEntity.toDomain(): CommandDefinition =
    CommandDefinition(
        id = id,
        name = name,
        enabled = enabled,
        triggerPhrases = TagListCodec.decode(triggerPhrases),
        intent = intent,
        argumentSchema = CommandCodec.decodeArgumentSchema(argumentSchema),
        confirmationPolicy = runCatching { ConfirmationPolicy.valueOf(confirmationPolicy) }
            .getOrDefault(ConfirmationPolicy.IF_AMBIGUOUS),
        actions = CommandCodec.decodeActions(actions),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

// --- ActionRun (redacted audit trail) ------------------------------------------------------

internal fun com.voit.domain.ActionRun.toEntity(): ActionRunEntity =
    ActionRunEntity(
        id = id,
        commandId = commandId,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        resultCode = resultCode,
        errorCode = errorCode,
    )

internal fun ActionRunEntity.toDomain(): com.voit.domain.ActionRun =
    com.voit.domain.ActionRun(
        id = id,
        commandId = commandId,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        resultCode = resultCode,
        errorCode = errorCode,
    )
