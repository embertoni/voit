package com.voit.feature.simulator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.actions.PendingConfirmation
import com.voit.actions.ProcessedTranscript
import com.voit.actions.TranscriptProcessor
import com.voit.domain.Note
import com.voit.domain.NoteRepository
import com.voit.domain.TaskItem
import com.voit.domain.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unidirectional UI state for the text-command Simulator.
 *
 * @param transcript the current text in the input field.
 * @param lastNote the structured note created by the most recent run (preview).
 * @param lastTask the task created by the most recent run, if any.
 * @param pendingConfirmation a high-risk action awaiting explicit confirmation.
 * @param reviewRequired whether the created note was flagged for review.
 * @param message a short status line for the user.
 */
data class SimulatorUiState(
    val transcript: String = "",
    val lastNote: Note? = null,
    val lastTask: TaskItem? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val reviewRequired: Boolean = false,
    val message: String? = null,
    val running: Boolean = false,
)

/**
 * Drives the manual microphone stand-in: a typed transcript is parsed by the
 * [TranscriptProcessor] (real FormattingParser + IntentParser + ActionRegistry) and the
 * resulting note/task is persisted via the repositories. High-risk actions surface a
 * [PendingConfirmation] and are NEVER auto-executed.
 *
 * [scope] is injected so JVM unit tests can supply a test scope (no Android main looper).
 */
class SimulatorViewModel(
    private val processor: TranscriptProcessor,
    private val noteRepository: NoteRepository,
    private val taskRepository: TaskRepository,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(SimulatorUiState())
    val uiState: StateFlow<SimulatorUiState> = _uiState.asStateFlow()

    fun onTranscriptChange(text: String) {
        _uiState.value = _uiState.value.copy(transcript = text)
    }

    /** Parse the current transcript, persist the note (+ task), and show the result. */
    fun run() {
        val transcript = _uiState.value.transcript.trim()
        if (transcript.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "Type a transcript first.")
            return
        }
        _uiState.value = _uiState.value.copy(running = true, message = null)
        workScope.launch {
            val result = processor.process(transcript)
            persist(result)
        }
    }

    private suspend fun persist(result: ProcessedTranscript) {
        // The structured note is always persisted (create-note is low risk).
        noteRepository.create(result.note)
        result.task?.let { taskRepository.create(it) }

        val message = buildString {
            append("Created note")
            if (result.task != null) append(" and task")
            append('.')
            if (result.pendingConfirmation != null) {
                append(" One high-risk action needs confirmation.")
            }
            if (result.reviewRequired) append(" Review recommended.")
        }

        _uiState.value = _uiState.value.copy(
            lastNote = result.note,
            lastTask = result.task,
            pendingConfirmation = result.pendingConfirmation,
            reviewRequired = result.reviewRequired,
            message = message,
            running = false,
        )
    }

    /** User explicitly confirmed the pending high-risk action: re-authorize and clear it. */
    fun confirmPending() {
        val pending = _uiState.value.pendingConfirmation ?: return
        workScope.launch {
            val decision = processor.confirm(pending.request)
            val confirmedAndAllowed =
                decision is com.voit.actions.AuthorizationDecision.Authorized
            _uiState.value = _uiState.value.copy(
                pendingConfirmation = null,
                message = if (confirmedAndAllowed) {
                    "Confirmed: ${pending.request.type} authorized."
                } else {
                    "Action could not be authorized."
                },
            )
        }
    }

    /** User declined the pending high-risk action. */
    fun dismissPending() {
        _uiState.value = _uiState.value.copy(
            pendingConfirmation = null,
            message = "High-risk action dismissed.",
        )
    }

    fun clear() {
        _uiState.value = SimulatorUiState()
    }
}
