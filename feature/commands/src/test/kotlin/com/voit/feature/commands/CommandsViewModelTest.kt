package com.voit.feature.commands

import com.voit.actions.ActionRegistry
import com.voit.actions.ActionRunRecorder
import com.voit.actions.CommandDispatcher
import com.voit.actions.DefaultHandlers
import com.voit.actions.WorkflowRunner
import com.voit.domain.ActionRun
import com.voit.domain.ActionRunRepository
import com.voit.domain.ActionRunStatus
import com.voit.domain.ActionType
import com.voit.domain.CommandDefinition
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.FakeCommandRepository
import com.voit.domain.FakeNoteRepository
import com.voit.domain.FakeTaskRepository
import com.voit.domain.FixedClock
import com.voit.domain.ParamValue
import com.voit.domain.SequentialIdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandsViewModelTest {

    private fun vm(repo: FakeCommandRepository, scope: TestScope) =
        CommandsViewModel(
            repository = repo,
            registry = ActionRegistry(),
            clock = FixedClock(1_000L),
            ids = SequentialIdGenerator(),
            scope = scope,
        )

    @Test
    fun `available actions are exactly the registry allow-list`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        assertEquals(
            ActionRegistry().registeredTypes.sortedBy { it.name },
            vm.uiState.value.availableActions,
        )
    }

    @Test
    fun `a command is built only from allow-listed actions and persisted`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        vm.setName("Quick note")
        vm.setIntent("create_note")
        vm.setTriggerPhrases("quick note, jot this")
        vm.setConfirmationPolicy(ConfirmationPolicy.NONE)
        vm.addAction(ActionType.CREATE_NOTE)
        assertTrue(vm.saveDraft())
        scope.advanceUntilIdle()

        val commands = repo.observeCommands().first()
        assertEquals(1, commands.size)
        val cmd = commands.single()
        assertEquals("Quick note", cmd.name)
        assertEquals(listOf(ActionType.CREATE_NOTE), cmd.actions.map { it.type })
        assertEquals(ConfirmationPolicy.NONE, cmd.confirmationPolicy)
    }

    @Test
    fun `the action list is bounded at the max`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        repeat(CommandsViewModel.MAX_ACTIONS + 2) { vm.addAction(ActionType.CREATE_NOTE) }

        assertEquals(CommandsViewModel.MAX_ACTIONS, vm.uiState.value.draft.actions.size)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `save fails without a name intent or any action`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        assertTrue(!vm.saveDraft())
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `per-action arguments are captured parsed and persisted`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        vm.setName("Make a task")
        vm.setIntent("make_task")
        vm.setConfirmationPolicy(ConfirmationPolicy.NONE)
        vm.addAction(ActionType.CREATE_TASK)
        // CREATE_TASK's required 'title' arg, supplied as raw text, parses to ParamValue.Text.
        vm.setActionArg(0, "title", "buy milk")
        assertTrue(vm.saveDraft())
        scope.advanceUntilIdle()

        val cmd = repo.observeCommands().first().single()
        val action = cmd.actions.single()
        assertEquals(ActionType.CREATE_TASK, action.type)
        assertEquals(ParamValue.Text("buy milk"), action.args["title"])
    }

    @Test
    fun `save aborts when a required argument is missing`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        vm.setName("No title task")
        vm.setIntent("make_task")
        vm.addAction(ActionType.CREATE_TASK) // 'title' required, left blank
        assertFalse(vm.saveDraft())
        assertNotNull(vm.uiState.value.error)
        assertTrue(repo.observeCommands().first().isEmpty())
    }

    @Test
    fun `enable toggle persists on the saved command`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        vm.setName("Disabled cmd")
        vm.setIntent("x")
        vm.addAction(ActionType.LIST_TASKS)
        vm.setEnabled(false)
        assertTrue(vm.saveDraft())
        scope.advanceUntilIdle()

        assertFalse(repo.observeCommands().first().single().enabled)
    }

    @Test
    fun `editing an existing command round-trips its args and enabled flag`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        val stored = CommandDefinition(
            id = "c1",
            name = "Append",
            enabled = false,
            triggerPhrases = listOf("append"),
            intent = "append",
            confirmationPolicy = ConfirmationPolicy.NONE,
            actions = listOf(
                com.voit.domain.ActionRequest(
                    ActionType.APPEND_TO_NOTE,
                    mapOf(
                        "noteId" to ParamValue.Text("n1"),
                        "body" to ParamValue.Text("more text"),
                    ),
                ),
            ),
            createdAt = 1L,
            updatedAt = 1L,
        )
        vm.editExisting(stored)

        val draft = vm.uiState.value.draft
        assertFalse(draft.enabled)
        val action = draft.actions.single()
        assertEquals("n1", action.rawArgs["noteId"])
        assertEquals("more text", action.rawArgs["body"])
    }

    @Test
    fun `runCommand dispatches a stored command and records a redacted audit entry`() = runTest {
        val commandRepo = FakeCommandRepository()
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val auditRepo = RecordingAuditRepo()
        val registry = ActionRegistry(
            DefaultHandlers.withRepositories(notes, tasks, FixedClock(1_000L), SequentialIdGenerator("h-")),
        )
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = CommandsViewModel(
            repository = commandRepo,
            registry = registry,
            clock = FixedClock(1_000L),
            ids = SequentialIdGenerator(),
            dispatcher = CommandDispatcher(registry, WorkflowRunner(registry)),
            recorder = ActionRunRecorder(auditRepo, FixedClock(2_000L), SequentialIdGenerator("run-")),
            scope = scope,
        )
        scope.advanceUntilIdle()

        val command = CommandDefinition(
            id = "c-run",
            name = "add stretch",
            enabled = true,
            triggerPhrases = listOf("stretch"),
            intent = "stretch",
            confirmationPolicy = ConfirmationPolicy.NONE,
            actions = listOf(
                com.voit.domain.ActionRequest(
                    ActionType.CREATE_TASK,
                    mapOf("title" to ParamValue.Text("stretch")),
                ),
            ),
            createdAt = 0L,
            updatedAt = 0L,
        )

        vm.runCommand(command)
        scope.advanceUntilIdle()

        // The task effect landed, and exactly one SUCCESS audit record was written.
        assertEquals(1, tasks.observeTasks().first().size)
        assertEquals(1, auditRepo.recorded.size)
        assertEquals(ActionRunStatus.SUCCESS, auditRepo.recorded.single().status)
        assertNotNull(vm.uiState.value.runMessage)
    }

    private class RecordingAuditRepo : ActionRunRepository {
        val recorded = mutableListOf<ActionRun>()
        override suspend fun record(run: ActionRun) { recorded.add(run) }
        override suspend fun history(): List<ActionRun> = recorded.reversed()
        override suspend fun clear() { recorded.clear() }
    }
}
