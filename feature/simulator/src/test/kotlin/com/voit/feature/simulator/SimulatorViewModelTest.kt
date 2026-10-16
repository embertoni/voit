package com.voit.feature.simulator

import com.voit.actions.ActionRegistry
import com.voit.actions.TranscriptProcessor
import com.voit.domain.ActionType
import com.voit.domain.FakeNoteRepository
import com.voit.domain.FakeTaskRepository
import com.voit.domain.FixedClock
import com.voit.domain.NoteBlock
import com.voit.domain.SequentialIdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end Phase-1 proof at the ViewModel layer: a typed transcript runs through the
 * REAL FormattingParser + IntentParser + ActionRegistry and the resulting structured
 * note/task is persisted to the in-memory fakes. High-risk actions are gated behind an
 * explicit confirmation and are not auto-executed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorViewModelTest {

    private fun newVm(
        notes: FakeNoteRepository,
        tasks: FakeTaskRepository,
        scope: TestScope,
    ): SimulatorViewModel {
        val processor = TranscriptProcessor(
            registry = ActionRegistry(),
            clock = FixedClock(1_000L),
            ids = SequentialIdGenerator(),
        )
        return SimulatorViewModel(processor, notes, tasks, scope)
    }

    @Test
    fun `typed transcript creates a structured note persisted to the repository`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = newVm(notes, tasks, scope)

        vm.onTranscriptChange("title: Groceries bullet point milk bullet point eggs tag shopping")
        vm.run()
        scope.advanceUntilIdle()

        val persisted = notes.observeNotes().first()
        assertEquals(1, persisted.size)
        val note = persisted.single()
        assertEquals("groceries", note.title)
        assertEquals(2, note.blocks.count { it is NoteBlock.Bullet })
        assertTrue(note.tags.contains("shopping"))

        // The UI state reflects the created note.
        assertNotNull(vm.uiState.value.lastNote)
    }

    @Test
    fun `transcript with a create-task intent persists both a note and a task`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = newVm(notes, tasks, scope)

        vm.onTranscriptChange("create a task titled Pay rent for the month")
        vm.run()
        scope.advanceUntilIdle()

        assertEquals(1, notes.observeNotes().first().size)
        val persistedTasks = tasks.observeTasks().first()
        assertEquals(1, persistedTasks.size)
        assertEquals("Pay rent", persistedTasks.single().title)
        // Low-risk create -> no confirmation gate.
        assertNull(vm.uiState.value.pendingConfirmation)
    }

    @Test
    fun `high-risk export is gated behind confirmation and not auto-run`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = newVm(notes, tasks, scope)

        vm.onTranscriptChange("export note now")
        vm.run()
        scope.advanceUntilIdle()

        val pending = assertNotNull(vm.uiState.value.pendingConfirmation)
        assertEquals(ActionType.EXPORT_NOTE, pending.request.type)

        // Confirming clears the gate (registry re-authorizes with confirmed = true).
        vm.confirmPending()
        scope.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingConfirmation)
    }

    @Test
    fun `empty transcript does nothing but reports a message`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = newVm(notes, tasks, scope)

        vm.run()
        scope.advanceUntilIdle()

        assertEquals(0, notes.observeNotes().first().size)
        assertNotNull(vm.uiState.value.message)
    }
}
