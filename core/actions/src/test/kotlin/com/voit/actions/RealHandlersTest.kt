package com.voit.actions

import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.CommandDefinition
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.FakeNoteRepository
import com.voit.domain.FakeTaskRepository
import com.voit.domain.FixedClock
import com.voit.domain.ParamValue
import com.voit.domain.SequentialIdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the Phase-5 REAL (repository-backed) handlers and the end-to-end command dispatch:
 * a dispatched command now actually persists notes/tasks through the repositories, while
 * the registry authorization policy and workflow bounds are unchanged. Driven with the
 * in-memory fakes + a FixedClock + a SequentialIdGenerator. No Android, network, or model.
 */
class RealHandlersTest {

    private fun registryWith(
        notes: FakeNoteRepository,
        tasks: FakeTaskRepository,
    ): ActionRegistry = ActionRegistry(
        DefaultHandlers.withRepositories(
            notes = notes,
            tasks = tasks,
            clock = FixedClock(1_000L),
            ids = SequentialIdGenerator("gen-"),
        ),
    )

    @Test
    fun `CREATE_NOTE handler persists a note through the repository`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val registry = registryWith(notes, tasks)

        val request = ActionRequest(
            ActionType.CREATE_NOTE,
            mapOf(
                "title" to ParamValue.Text("Groceries"),
                "body" to ParamValue.Text("buy milk"),
            ),
        )
        val result = registry.execute(request)

        // Output carries only the created id (no content).
        val noteId = (result.output["noteId"] as ParamValue.Text).value
        val stored = assertNotNull(notes.getNote(noteId))
        assertEquals("Groceries", stored.title)
        assertTrue(stored.blocks.isNotEmpty())
    }

    @Test
    fun `CREATE_TASK handler persists a task through the repository`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val registry = registryWith(notes, tasks)

        val request = ActionRequest(
            ActionType.CREATE_TASK,
            mapOf("title" to ParamValue.Text("call dentist")),
        )
        val result = registry.execute(request)

        val taskId = (result.output["taskId"] as ParamValue.Text).value
        assertEquals("call dentist", assertNotNull(tasks.getTask(taskId)).title)
    }

    @Test
    fun `COMPLETE_TASK handler marks an existing task complete`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val registry = registryWith(notes, tasks)

        // Create then complete.
        val created = registry.execute(
            ActionRequest(ActionType.CREATE_TASK, mapOf("title" to ParamValue.Text("x"))),
        )
        val taskId = (created.output["taskId"] as ParamValue.Text).value

        registry.execute(
            ActionRequest(ActionType.COMPLETE_TASK, mapOf("taskId" to ParamValue.Text(taskId))),
        )
        assertTrue(assertNotNull(tasks.getTask(taskId)).completed)
    }

    @Test
    fun `dispatching a command runs real handlers end to end and persists`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val registry = registryWith(notes, tasks)
        val dispatcher = CommandDispatcher(registry, WorkflowRunner(registry))

        val command = CommandDefinition(
            id = "c-morning",
            name = "morning setup",
            triggerPhrases = listOf("good morning"),
            intent = "morning",
            confirmationPolicy = ConfirmationPolicy.NONE,
            actions = listOf(
                ActionRequest(ActionType.CREATE_NOTE, mapOf("title" to ParamValue.Text("Journal"))),
                ActionRequest(ActionType.CREATE_TASK, mapOf("title" to ParamValue.Text("stretch"))),
            ),
            createdAt = 0L,
            updatedAt = 0L,
        )

        val result = dispatcher.dispatch(listOf(command), transcript = "good morning!")
        val dispatched = result as CommandDispatchResult.Dispatched
        assertTrue(dispatched.outcome is WorkflowOutcome.Completed)

        // Both effects actually landed in the repositories.
        assertEquals(1, notes.observeNotes().first().size)
        assertEquals(1, tasks.observeTasks().first().size)
    }

    @Test
    fun `non-persisting actions remain echo placeholders`() = runTest {
        val registry = registryWith(FakeNoteRepository(), FakeTaskRepository())
        // SPEAK_RESPONSE has no repository-backed handler; it still echoes its args.
        val result = registry.execute(
            ActionRequest(ActionType.SPEAK_RESPONSE, mapOf("text" to ParamValue.Text("hello"))),
        )
        assertEquals("hello", (result.output["text"] as ParamValue.Text).value)
    }

    @Test
    fun `withRepositories keeps exactly one handler per allow-listed type`() {
        val registry = registryWith(FakeNoteRepository(), FakeTaskRepository())
        // Every ActionType is still registered exactly once (real handlers replaced, not added).
        assertEquals(ActionType.entries.toSet(), registry.registeredTypes)
    }
}
