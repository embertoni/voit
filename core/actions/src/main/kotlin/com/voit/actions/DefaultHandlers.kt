package com.voit.actions

import com.voit.domain.ActionPermission
import com.voit.domain.ActionType
import com.voit.domain.Clock
import com.voit.domain.IdGenerator
import com.voit.domain.NoteRepository
import com.voit.domain.ParamType
import com.voit.domain.RiskLevel
import com.voit.domain.TaskRepository

/**
 * The MVP set of action handlers. Each one encodes the spec's required contract:
 * schema, permissions, risk level, timeout, and confirmation-mandatory flag.
 *
 * Risk assignment follows the policy: creating/listing/transient-UI actions are LOW,
 * mutations (append/update/complete/reschedule/archive) are MEDIUM, and
 * export/share/privacy-sensitive actions are HIGH (confirmation mandatory).
 */
object DefaultHandlers {

    /**
     * The all-ECHO handler set (no persistence). Kept as the default so the pure
     * `core:actions` module, its tests, and any non-persisting caller work without a
     * repository. Production wires [withRepositories] instead so note/task actions persist.
     */
    fun all(): List<ActionHandler> = listOf(
        createNote(),
        appendToNote(),
        updateNote(),
        searchNotes(),
        archiveNote(),
        exportNote(),
        createTask(),
        listTasks(),
        completeTask(),
        rescheduleTask(),
        setReminder(),
        startTimer(),
        cancelTimer(),
        openApp(),
        showOverlay(),
        hideOverlay(),
        pauseListening(),
        resumeListening(),
        speakResponse(),
        showConfirmation(),
    )

    /**
     * The PRODUCTION handler set: the echo defaults with the persistence-capable note/task
     * handlers replaced by real repository-backed ones (see [RealHandlers]). Actions that
     * still need platform runtime not yet built (timers, overlay, listening, open-app,
     * speak, export) remain echo placeholders. The real handlers override the echo ones by
     * [ActionHandler.type], so the registry still has exactly one handler per allow-listed
     * type and every contract (schema/risk/confirmation) is unchanged.
     */
    fun withRepositories(
        notes: NoteRepository,
        tasks: TaskRepository,
        clock: Clock,
        ids: IdGenerator,
    ): List<ActionHandler> {
        val real = RealHandlers.noteAndTask(notes, tasks, clock, ids)
        val realTypes = real.map { it.type }.toSet()
        return all().filterNot { it.type in realTypes } + real
    }

    private fun createNote() = object : EchoActionHandler(
        type = ActionType.CREATE_NOTE,
        schema = listOf(
            ParamSpec("title", ParamType.TEXT, required = false),
            ParamSpec("body", ParamType.TEXT, required = false),
            ParamSpec("tags", ParamType.TAGS, required = false),
        ),
        permissions = setOf(ActionPermission.NOTES_WRITE),
        risk = RiskLevel.LOW,
    ) {}

    private fun appendToNote() = object : EchoActionHandler(
        type = ActionType.APPEND_TO_NOTE,
        schema = listOf(
            ParamSpec("noteId", ParamType.TEXT, required = true),
            ParamSpec("body", ParamType.TEXT, required = true),
        ),
        permissions = setOf(ActionPermission.NOTES_WRITE),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun updateNote() = object : EchoActionHandler(
        type = ActionType.UPDATE_NOTE,
        schema = listOf(
            ParamSpec("noteId", ParamType.TEXT, required = true),
            ParamSpec("body", ParamType.TEXT, required = true),
        ),
        permissions = setOf(ActionPermission.NOTES_WRITE),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun searchNotes() = object : EchoActionHandler(
        type = ActionType.SEARCH_NOTES,
        schema = listOf(ParamSpec("query", ParamType.TEXT, required = true)),
        permissions = setOf(ActionPermission.NOTES_READ),
        risk = RiskLevel.LOW,
    ) {}

    private fun archiveNote() = object : EchoActionHandler(
        type = ActionType.ARCHIVE_NOTE,
        schema = listOf(ParamSpec("noteId", ParamType.TEXT, required = true)),
        permissions = setOf(ActionPermission.NOTES_WRITE),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun exportNote() = object : EchoActionHandler(
        type = ActionType.EXPORT_NOTE,
        schema = listOf(
            ParamSpec("noteId", ParamType.TEXT, required = true),
            ParamSpec("destination", ParamType.TEXT, required = false),
        ),
        permissions = setOf(ActionPermission.NOTES_READ, ActionPermission.EXPORT),
        risk = RiskLevel.HIGH,
        confirmationMandatory = true,
    ) {}

    private fun createTask() = object : EchoActionHandler(
        type = ActionType.CREATE_TASK,
        schema = listOf(
            ParamSpec("title", ParamType.TEXT, required = true),
            ParamSpec("due", ParamType.TIMESTAMP, required = false),
        ),
        permissions = setOf(ActionPermission.TASKS_WRITE),
        risk = RiskLevel.LOW,
    ) {}

    private fun listTasks() = object : EchoActionHandler(
        type = ActionType.LIST_TASKS,
        schema = emptyList(),
        permissions = setOf(ActionPermission.TASKS_READ),
        risk = RiskLevel.LOW,
    ) {}

    private fun completeTask() = object : EchoActionHandler(
        type = ActionType.COMPLETE_TASK,
        schema = listOf(ParamSpec("taskId", ParamType.TEXT, required = true)),
        permissions = setOf(ActionPermission.TASKS_WRITE),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun rescheduleTask() = object : EchoActionHandler(
        type = ActionType.RESCHEDULE_TASK,
        schema = listOf(
            ParamSpec("taskId", ParamType.TEXT, required = true),
            ParamSpec("due", ParamType.TIMESTAMP, required = true),
        ),
        permissions = setOf(ActionPermission.TASKS_WRITE),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun setReminder() = object : EchoActionHandler(
        type = ActionType.SET_REMINDER,
        schema = listOf(
            ParamSpec("taskId", ParamType.TEXT, required = false),
            ParamSpec("at", ParamType.TIMESTAMP, required = true),
        ),
        permissions = setOf(ActionPermission.ALARMS),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun startTimer() = object : EchoActionHandler(
        type = ActionType.START_TIMER,
        schema = listOf(ParamSpec("duration", ParamType.DURATION, required = true)),
        permissions = setOf(ActionPermission.ALARMS),
        risk = RiskLevel.LOW,
    ) {}

    private fun cancelTimer() = object : EchoActionHandler(
        type = ActionType.CANCEL_TIMER,
        schema = listOf(ParamSpec("timerId", ParamType.TEXT, required = false)),
        permissions = setOf(ActionPermission.ALARMS),
        risk = RiskLevel.LOW,
    ) {}

    private fun openApp() = object : EchoActionHandler(
        type = ActionType.OPEN_APP,
        schema = listOf(ParamSpec("packageName", ParamType.TEXT, required = true)),
        permissions = setOf(ActionPermission.LAUNCH_APP),
        risk = RiskLevel.MEDIUM,
    ) {}

    private fun showOverlay() = object : EchoActionHandler(
        type = ActionType.SHOW_OVERLAY,
        schema = emptyList(),
        permissions = setOf(ActionPermission.OVERLAY),
        risk = RiskLevel.LOW,
    ) {}

    private fun hideOverlay() = object : EchoActionHandler(
        type = ActionType.HIDE_OVERLAY,
        schema = emptyList(),
        permissions = setOf(ActionPermission.OVERLAY),
        risk = RiskLevel.LOW,
    ) {}

    private fun pauseListening() = object : EchoActionHandler(
        type = ActionType.PAUSE_LISTENING,
        schema = emptyList(),
        permissions = setOf(ActionPermission.MICROPHONE),
        risk = RiskLevel.LOW,
    ) {}

    private fun resumeListening() = object : EchoActionHandler(
        type = ActionType.RESUME_LISTENING,
        schema = emptyList(),
        permissions = setOf(ActionPermission.MICROPHONE),
        risk = RiskLevel.LOW,
    ) {}

    private fun speakResponse() = object : EchoActionHandler(
        type = ActionType.SPEAK_RESPONSE,
        schema = listOf(ParamSpec("text", ParamType.TEXT, required = true)),
        permissions = emptySet(),
        risk = RiskLevel.LOW,
    ) {}

    private fun showConfirmation() = object : EchoActionHandler(
        type = ActionType.SHOW_CONFIRMATION,
        schema = listOf(ParamSpec("text", ParamType.TEXT, required = true)),
        permissions = emptySet(),
        risk = RiskLevel.LOW,
    ) {}
}
