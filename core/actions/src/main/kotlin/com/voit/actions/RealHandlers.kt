package com.voit.actions

import com.voit.domain.ActionPermission
import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.Clock
import com.voit.domain.IdGenerator
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.NoteRepository
import com.voit.domain.NoteSource
import com.voit.domain.ParamType
import com.voit.domain.ParamValue
import com.voit.domain.Priority
import com.voit.domain.RiskLevel
import com.voit.domain.TaskItem
import com.voit.domain.TaskRepository

/**
 * Phase-5 REAL, repository-backed action handlers. These replace the [EchoActionHandler]
 * placeholders for the persistence-capable note/task actions so a dispatched command
 * actually mutates the user's data through the existing repositories.
 *
 * Each handler keeps the EXACT same contract (type / schema / permissions / risk /
 * confirmationMandatory) as its echo counterpart in [DefaultHandlers] - only `execute` is
 * real now - so the [ActionRegistry] authorization policy and the [WorkflowRunner] bounds
 * are unchanged. Validation (presence + types) still runs via the default
 * [ActionHandler.validate] before `execute`, so a handler can trust its required args.
 *
 * Actions that need platform runtime not yet built (timers, overlay, listening, open-app,
 * speak, export) remain echo placeholders in [DefaultHandlers]; this file wires only the
 * note/task CRUD that the local repositories already support. Pure JVM (no Android): the
 * repositories are the injected boundary.
 */
object RealHandlers {

    /**
     * The real note/task handlers. Pass these (plus the still-echo handlers for everything
     * else) to [ActionRegistry]. See [DefaultHandlers.withRepositories].
     */
    fun noteAndTask(
        notes: NoteRepository,
        tasks: TaskRepository,
        clock: Clock,
        ids: IdGenerator,
    ): List<ActionHandler> = listOf(
        CreateNoteHandler(notes, clock, ids),
        AppendToNoteHandler(notes, clock),
        ArchiveNoteHandler(notes),
        CreateTaskHandler(tasks, clock, ids),
        CompleteTaskHandler(tasks),
        RescheduleTaskHandler(tasks, clock),
    )
}

private fun text(request: ActionRequest, name: String): String? =
    (request.args[name] as? ParamValue.Text)?.value

private fun tags(request: ActionRequest, name: String): List<String> =
    (request.args[name] as? ParamValue.Tags)?.values ?: emptyList()

private fun timestamp(request: ActionRequest, name: String): Long? =
    (request.args[name] as? ParamValue.Timestamp)?.epochMillis

/** CREATE_NOTE: persist a new note built from the title/body/tags args. */
class CreateNoteHandler(
    private val notes: NoteRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) : ActionHandler {
    override val type = ActionType.CREATE_NOTE
    override val schema = listOf(
        ParamSpec("title", ParamType.TEXT, required = false),
        ParamSpec("body", ParamType.TEXT, required = false),
        ParamSpec("tags", ParamType.TAGS, required = false),
    )
    override val permissions = setOf(ActionPermission.NOTES_WRITE)
    override val risk = RiskLevel.LOW
    override val timeoutMillis = 2_000L
    override val confirmationMandatory = false

    override suspend fun execute(request: ActionRequest): ActionResult {
        val now = clock.nowEpochMillis()
        val body = text(request, "body")
        val note = Note(
            id = ids.newId(),
            title = text(request, "title"),
            blocks = if (body.isNullOrBlank()) emptyList() else listOf(NoteBlock.Paragraph(body)),
            tags = tags(request, "tags"),
            createdAt = now,
            updatedAt = now,
            source = NoteSource.VOICE,
        )
        notes.create(note)
        // Output carries only the created id (no content) for traceability.
        return ActionResult(type, output = mapOf("noteId" to ParamValue.Text(note.id)))
    }
}

/** APPEND_TO_NOTE: append a paragraph to an existing note (get + rebuild + update). */
class AppendToNoteHandler(
    private val notes: NoteRepository,
    private val clock: Clock,
) : ActionHandler {
    override val type = ActionType.APPEND_TO_NOTE
    override val schema = listOf(
        ParamSpec("noteId", ParamType.TEXT, required = true),
        ParamSpec("body", ParamType.TEXT, required = true),
    )
    override val permissions = setOf(ActionPermission.NOTES_WRITE)
    override val risk = RiskLevel.MEDIUM
    override val timeoutMillis = 2_000L
    override val confirmationMandatory = false

    override suspend fun execute(request: ActionRequest): ActionResult {
        val noteId = text(request, "noteId")!!
        val body = text(request, "body")!!
        val existing = notes.getNote(noteId)
            ?: error("note not found") // generic message; redacted to a code upstream
        val updated = existing.copy(
            blocks = existing.blocks + NoteBlock.Paragraph(body),
            updatedAt = clock.nowEpochMillis(),
        )
        notes.update(updated)
        return ActionResult(type, output = mapOf("noteId" to ParamValue.Text(noteId)))
    }
}

/** ARCHIVE_NOTE: soft-archive an existing note. */
class ArchiveNoteHandler(
    private val notes: NoteRepository,
) : ActionHandler {
    override val type = ActionType.ARCHIVE_NOTE
    override val schema = listOf(ParamSpec("noteId", ParamType.TEXT, required = true))
    override val permissions = setOf(ActionPermission.NOTES_WRITE)
    override val risk = RiskLevel.MEDIUM
    override val timeoutMillis = 2_000L
    override val confirmationMandatory = false

    override suspend fun execute(request: ActionRequest): ActionResult {
        val noteId = text(request, "noteId")!!
        notes.archive(noteId)
        return ActionResult(type, output = mapOf("noteId" to ParamValue.Text(noteId)))
    }
}

/** CREATE_TASK: persist a new task built from the title/due args. */
class CreateTaskHandler(
    private val tasks: TaskRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) : ActionHandler {
    override val type = ActionType.CREATE_TASK
    override val schema = listOf(
        ParamSpec("title", ParamType.TEXT, required = true),
        ParamSpec("due", ParamType.TIMESTAMP, required = false),
    )
    override val permissions = setOf(ActionPermission.TASKS_WRITE)
    override val risk = RiskLevel.LOW
    override val timeoutMillis = 2_000L
    override val confirmationMandatory = false

    override suspend fun execute(request: ActionRequest): ActionResult {
        val now = clock.nowEpochMillis()
        val task = TaskItem(
            id = ids.newId(),
            title = text(request, "title")!!,
            due = timestamp(request, "due"),
            priority = Priority.NORMAL,
            createdAt = now,
            updatedAt = now,
        )
        tasks.create(task)
        return ActionResult(type, output = mapOf("taskId" to ParamValue.Text(task.id)))
    }
}

/** COMPLETE_TASK: mark an existing task complete. */
class CompleteTaskHandler(
    private val tasks: TaskRepository,
) : ActionHandler {
    override val type = ActionType.COMPLETE_TASK
    override val schema = listOf(ParamSpec("taskId", ParamType.TEXT, required = true))
    override val permissions = setOf(ActionPermission.TASKS_WRITE)
    override val risk = RiskLevel.MEDIUM
    override val timeoutMillis = 2_000L
    override val confirmationMandatory = false

    override suspend fun execute(request: ActionRequest): ActionResult {
        val taskId = text(request, "taskId")!!
        tasks.setCompleted(taskId, completed = true)
        return ActionResult(type, output = mapOf("taskId" to ParamValue.Text(taskId)))
    }
}

/** RESCHEDULE_TASK: set a new due date on an existing task (get + update). */
class RescheduleTaskHandler(
    private val tasks: TaskRepository,
    private val clock: Clock,
) : ActionHandler {
    override val type = ActionType.RESCHEDULE_TASK
    override val schema = listOf(
        ParamSpec("taskId", ParamType.TEXT, required = true),
        ParamSpec("due", ParamType.TIMESTAMP, required = true),
    )
    override val permissions = setOf(ActionPermission.TASKS_WRITE)
    override val risk = RiskLevel.MEDIUM
    override val timeoutMillis = 2_000L
    override val confirmationMandatory = false

    override suspend fun execute(request: ActionRequest): ActionResult {
        val taskId = text(request, "taskId")!!
        val due = timestamp(request, "due")!!
        val existing = tasks.getTask(taskId) ?: error("task not found")
        tasks.update(existing.copy(due = due, updatedAt = clock.nowEpochMillis()))
        return ActionResult(type, output = mapOf("taskId" to ParamValue.Text(taskId)))
    }
}
