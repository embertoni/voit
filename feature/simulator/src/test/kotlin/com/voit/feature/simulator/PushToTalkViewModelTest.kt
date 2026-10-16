package com.voit.feature.simulator

import com.voit.actions.ActionRegistry
import com.voit.actions.TranscriptProcessor
import com.voit.domain.ActionType
import com.voit.domain.FakeNoteRepository
import com.voit.domain.FakeTaskRepository
import com.voit.domain.FixedClock
import com.voit.domain.NoteBlock
import com.voit.domain.NoteSource
import com.voit.domain.SequentialIdGenerator
import com.voit.voice.FakeAudioSessionController
import com.voit.voice.FakeSpeechRecognizerEngine
import com.voit.voice.RecordingConfig
import com.voit.voice.RecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end Phase-2 proof at the ViewModel layer: a canned (faked) recognition is run
 * through the owned RecordingController, reviewed, accepted, and handed to the REAL
 * [TranscriptProcessor] with `source = VOICE`; the resulting note/task is persisted to the
 * in-memory fakes. The fallback path yields an editable empty transcript that still
 * produces a note once typed. High-risk actions are gated behind confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushToTalkViewModelTest {

    private fun newVm(
        notes: FakeNoteRepository,
        tasks: FakeTaskRepository,
        scope: TestScope,
        recognizer: FakeSpeechRecognizerEngine,
        audio: FakeAudioSessionController = FakeAudioSessionController(),
    ): PushToTalkViewModel {
        val processor = TranscriptProcessor(
            registry = ActionRegistry(),
            clock = FixedClock(1_000L),
            ids = SequentialIdGenerator(),
        )
        return PushToTalkViewModel(
            processor = processor,
            noteRepository = notes,
            taskRepository = tasks,
            audioSessionController = audio,
            speechRecognizer = recognizer,
            recordingConfig = RecordingConfig(maxUtteranceMillis = 15_000L),
            clock = FixedClock(1_000L),
            scope = scope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun `faked recognition accepted creates a structured VOICE note persisted to the repository`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val recognizer = FakeSpeechRecognizerEngine(
            cannedTranscript = "title: Groceries bullet point milk bullet point eggs tag shopping",
        )
        val audio = FakeAudioSessionController()
        val vm = newVm(notes, tasks, scope, recognizer, audio)

        vm.startRecording()
        scope.runCurrent()
        assertTrue(vm.uiState.value.recording is RecordingState.Recording)

        vm.stopRecording()
        scope.advanceUntilIdle()

        // Landed on an editable, non-fallback review with the canned transcript.
        assertTrue(vm.uiState.value.reviewing)
        assertTrue(!vm.uiState.value.fallback)
        assertTrue(vm.uiState.value.transcript.contains("Groceries"))

        vm.acceptReview()
        scope.advanceUntilIdle()

        val persisted = notes.observeNotes().first()
        assertEquals(1, persisted.size)
        val note = persisted.single()
        assertEquals("groceries", note.title)
        assertEquals(2, note.blocks.count { it is NoteBlock.Bullet })
        assertTrue(note.tags.contains("shopping"))
        // The note carries the VOICE source.
        assertEquals(NoteSource.VOICE, note.source)
        assertNotNull(vm.uiState.value.lastNote)
    }

    @Test
    fun `create-task transcript persists both a note and a task with VOICE source`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val recognizer = FakeSpeechRecognizerEngine(
            cannedTranscript = "create a task titled Pay rent for the month",
        )
        val vm = newVm(notes, tasks, scope, recognizer)

        vm.startRecording()
        scope.runCurrent()
        vm.stopRecording()
        scope.advanceUntilIdle()

        vm.acceptReview()
        scope.advanceUntilIdle()

        assertEquals(1, notes.observeNotes().first().size)
        assertEquals(NoteSource.VOICE, notes.observeNotes().first().single().source)
        val persistedTasks = tasks.observeTasks().first()
        assertEquals(1, persistedTasks.size)
        assertEquals("Pay rent", persistedTasks.single().title)
        assertNull(vm.uiState.value.pendingConfirmation)
    }

    @Test
    fun `fallback path yields an editable empty transcript that once typed still persists a note`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        // Empty canned transcript -> EMPTY_TRANSCRIPT fallback.
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "")
        val vm = newVm(notes, tasks, scope, recognizer)

        vm.startRecording()
        scope.runCurrent()
        vm.stopRecording()
        scope.advanceUntilIdle()

        // Fallback review: empty + editable + a stable error code, manual entry reachable.
        assertTrue(vm.uiState.value.reviewing)
        assertTrue(vm.uiState.value.fallback)
        assertEquals("", vm.uiState.value.transcript)
        assertNotNull(vm.uiState.value.errorCode)

        // The user types a transcript manually and accepts.
        vm.onTranscriptChange("title: Manual entry tag voice")
        vm.acceptReview()
        scope.advanceUntilIdle()

        val persisted = notes.observeNotes().first()
        assertEquals(1, persisted.size)
        assertEquals(NoteSource.VOICE, persisted.single().source)
        assertEquals("manual entry", persisted.single().title)
    }

    @Test
    fun `high-risk export surfaces a pending confirmation and is not auto-run`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "export note now")
        val vm = newVm(notes, tasks, scope, recognizer)

        vm.startRecording()
        scope.runCurrent()
        vm.stopRecording()
        scope.advanceUntilIdle()

        vm.acceptReview()
        scope.advanceUntilIdle()

        val pending = assertNotNull(vm.uiState.value.pendingConfirmation)
        assertEquals(ActionType.EXPORT_NOTE, pending.request.type)

        // Confirming clears the gate (registry re-authorizes with confirmed = true).
        vm.confirmPending()
        scope.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingConfirmation)
    }

    @Test
    fun `cancel returns to idle without producing a transcript`() = runTest {
        val notes = FakeNoteRepository()
        val tasks = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "something")
        val vm = newVm(notes, tasks, scope, recognizer)

        vm.startRecording()
        scope.runCurrent()
        vm.cancelRecording()
        scope.advanceUntilIdle()

        assertTrue(vm.uiState.value.recording is RecordingState.Idle)
        assertTrue(!vm.uiState.value.reviewing)
        assertEquals(0, notes.observeNotes().first().size)
    }
}
