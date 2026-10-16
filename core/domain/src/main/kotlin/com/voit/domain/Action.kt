package com.voit.domain

/**
 * The MVP allow-list of action types Voit can perform.
 *
 * This enum is the single source of truth for "what is executable". Anything outside it
 * is rejected by the action registry: a model may propose, but it can never invent an
 * action type that is not listed here.
 */
enum class ActionType {
    // Notes
    CREATE_NOTE,
    APPEND_TO_NOTE,
    UPDATE_NOTE,
    SEARCH_NOTES,
    ARCHIVE_NOTE,
    EXPORT_NOTE,

    // Tasks
    CREATE_TASK,
    LIST_TASKS,
    COMPLETE_TASK,
    RESCHEDULE_TASK,

    // Reminders / timers
    SET_REMINDER,
    START_TIMER,
    CANCEL_TIMER,

    // System / UI
    OPEN_APP,
    SHOW_OVERLAY,
    HIDE_OVERLAY,
    PAUSE_LISTENING,
    RESUME_LISTENING,
    SPEAK_RESPONSE,
    SHOW_CONFIRMATION,
}

/** Coarse risk classification driving the confirmation policy. */
enum class RiskLevel {
    /** Reversible, no side effects worth confirming. */
    LOW,

    /** Mutates existing data (append/update/archive); reviewable/undoable. */
    MEDIUM,

    /** Destructive, bulk, export/share, permission or privacy sensitive. */
    HIGH,
}

/** Permissions an action may require at runtime (checked by later platform phases). */
enum class ActionPermission {
    NOTES_READ,
    NOTES_WRITE,
    TASKS_READ,
    TASKS_WRITE,
    EXPORT,
    ALARMS,
    MICROPHONE,
    OVERLAY,
    LAUNCH_APP,
}

/**
 * A single typed value supplied as an action argument or extracted as an intent slot.
 *
 * Keeping parameters typed (rather than free strings) means validation can be strict and
 * free text is never executed verbatim.
 */
sealed interface ParamValue {
    data class Text(val value: String) : ParamValue
    data class Number(val value: Double) : ParamValue
    data class Bool(val value: Boolean) : ParamValue

    /** A duration expressed in whole seconds (e.g. "5 minutes" -> 300). */
    data class Duration(val seconds: Long) : ParamValue

    /** An absolute instant in epoch millis (e.g. resolved from a relative date). */
    data class Timestamp(val epochMillis: Long) : ParamValue

    /** A list of tag strings. */
    data class Tags(val values: List<String>) : ParamValue
}

/** A request to perform one action with typed arguments, as produced by the interpreter. */
data class ActionRequest(
    val type: ActionType,
    val args: Map<String, ParamValue> = emptyMap(),
)
