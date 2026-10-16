package com.voit.domain

/** Task priority. */
enum class Priority {
    LOW,
    NORMAL,
    HIGH,
}

/**
 * Reminder configuration for a [TaskItem].
 *
 * @param remindAtEpochMillis absolute reminder instant, if set.
 * @param leadMinutes optional lead time before the due date (used when no absolute time).
 */
data class ReminderConfig(
    val remindAtEpochMillis: Long? = null,
    val leadMinutes: Int? = null,
) {
    val isSet: Boolean get() = remindAtEpochMillis != null || leadMinutes != null
}

/**
 * A standalone task / to-do item.
 *
 * Named [TaskItem] to avoid colliding with [NoteBlock.Task] (the in-note checkbox block).
 *
 * @param id stable UUID string.
 * @param due optional due date as epoch millis.
 * @param reminderConfig optional reminder.
 * @param linkedNoteId optional id of a [Note] this task was extracted from.
 */
data class TaskItem(
    val id: String,
    val title: String,
    val body: String? = null,
    val due: Long? = null,
    val reminderConfig: ReminderConfig? = null,
    val priority: Priority = Priority.NORMAL,
    val completed: Boolean = false,
    val linkedNoteId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
