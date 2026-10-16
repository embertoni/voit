package com.voit.feature.simulator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.actions.PendingConfirmation
import com.voit.actions.ProcessedTranscript
import com.voit.actions.TranscriptProcessor
import com.voit.domain.Note
import com.voit.domain.NoteRepository
import com.voit.domain.NoteSource
import com.voit.domain.TaskItem
import com.voit.domain.TaskRepository
import com.voit.voice.AudioSessionController
import com.voit.voice.RecordingConfig
import com.voit.voice.RecordingController
import com.voit.voice.RecordingState
import com.voit.voice.SpeechRecognizerEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unidirectional UI state for the push-to-talk voice screen.
 *
 * @param recording the current [RecordingState] from the owned [RecordingController].
 * @param reviewing true while a (possibly edited) transcript is awaiting accept.
 * @param transcript the editable transcript shown in the review field.
 * @param fallback true when recognition failed / returned empty / timed out, so the field
 *   starts empty and a clearly-labelled fallback message is shown (manual entry stays open).
 * @param errorCode a stable [com.voit.voice.RecordingErrorCode] when [fallback] is true.
 * @param lastNote the structured note created by the most recent accepted run (preview).
 * @param lastTask the task created by the most recent run, if any.
 * @param pendingConfirmation a high-risk action awaiting explicit confirmation.
 * @param reviewRequired whether the created note was flagged for review.
 * @param message a short status line for the user.
 */
data class PushToTalkUiState(
    val recording: RecordingState = RecordingState.Idle,
    val reviewing: Boolean = false,
    val transcript: String = "",
    val fallback: Boolean = false,
    val errorCode: String? = null,
    val lastNote: Note? = null,
    val lastTask: TaskItem? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val reviewRequired: Boolean = false,
    val message: String? = null,
)

/**
 * Drives the end-to-end push-to-talk path: press to start capture, release to transcribe,
 * then review/EDIT the (possibly faked) transcript before it is handed to the EXISTING
 * [TranscriptProcessor] with `source = NoteSource.VOICE` and persisted via the repositories
 * exactly like [SimulatorViewModel.persist]. High-risk actions surface a
 * [PendingConfirmation] and are NEVER auto-executed.
 *
 * This ViewModel OWNS the FEAT-002 [RecordingController], building it from the injected
 * engines + [RecordingConfig] so the controller can run on the ViewModel's scope (the
 * Phase-1 injected-scope pattern). The DEFAULT engines supplied by DI are fakes: there is
 * no real microphone or ASR model here (that awaits the Phase-0 hardware benchmark), and
 * the raw audio buffer is discarded immediately after recognition by the controller.
 *
 * [scope] is injected so JVM unit tests can supply a TestScope (no Android main looper).
 */
class PushToTalkViewModel(
    private val processor: TranscriptProcessor,
    private val noteRepository: NoteRepository,
    private val taskRepository: TaskRepository,
    audioSessionController: AudioSessionController,
    speechRecognizer: SpeechRecognizerEngine,
    recordingConfig: RecordingConfig = RecordingConfig(),
    clock: com.voit.domain.Clock,
    private val scope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    /**
     * The owned recording state machine. It runs on [workScope] so JVM tests drive its
     * timeout/recognition with virtual time, mirroring the Phase-1 injected-scope pattern.
     */
    private val controller: RecordingController = RecordingController(
        audioSessionController = audioSessionController,
        speechRecognizer = speechRecognizer,
        config = recordingConfig,
        clock = clock,
        scope = workScope,
        dispatcher = dispatcher,
    )

    private val _uiState = MutableStateFlow(PushToTalkUiState())
    val uiState: StateFlow<PushToTalkUiState> = _uiState.asStateFlow()

    init {
        // Mirror the controller's lifecycle into the UI state. When the controller reaches
        // Review we open the editable review field (empty + labelled on the fallback path).
        workScope.launch {
            controller.state.collect { recordingState ->
                _uiState.value = when (recordingState) {
                    is RecordingState.Review -> _uiState.value.copy(
                        recording = recordingState,
                        reviewing = true,
                        transcript = recordingState.transcript,
                        fallback = recordingState.fallback,
                        errorCode = recordingState.errorCode,
                        message = reviewMessage(recordingState),
                    )
                    else -> _uiState.value.copy(recording = recordingState)
                }
            }
        }
    }

    private fun reviewMessage(review: RecordingState.Review): String? = when {
        !review.fallback -> "Transcript ready. Review and edit, then Accept."
        else -> "No speech was recognized (${review.errorCode}). " +
            "Type the transcript manually, then Accept."
    }

    /** Press-and-hold start: begin capturing audio. */
    fun startRecording() {
        _uiState.value = _uiState.value.copy(message = "Recording. Release to transcribe.")
        controller.startPushToTalk()
    }

    /** Release: stop capture and run recognition, landing on the editable review. */
    fun stopRecording() {
        controller.stopAndTranscribe()
    }

    /** Cancel the capture and return to idle with no transcript. */
    fun cancelRecording() {
        controller.cancel()
        _uiState.value = _uiState.value.copy(
            reviewing = false,
            transcript = "",
            fallback = false,
            errorCode = null,
            message = "Recording cancelled.",
        )
    }

    /** Edit the transcript shown in the review field. */
    fun onTranscriptChange(text: String) {
        _uiState.value = _uiState.value.copy(transcript = text)
        controller.editReview(text)
    }

    /**
     * Accept the (possibly edited) review transcript: hand it to the EXISTING
     * [TranscriptProcessor] with [NoteSource.VOICE] and persist the result. A blank
     * transcript does nothing but reports a message (manual entry is still required).
     */
    fun acceptReview() {
        val edited = _uiState.value.transcript.trim()
        // Clear the controller's review state regardless; we carry the edited text onward.
        controller.acceptReview(edited)
        if (edited.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                reviewing = false,
                message = "Transcript is empty. Type something first.",
            )
            return
        }
        _uiState.value = _uiState.value.copy(reviewing = false, message = null)
        workScope.launch {
            val result = processor.process(edited, source = NoteSource.VOICE)
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
            transcript = "",
            lastNote = result.note,
            lastTask = result.task,
            pendingConfirmation = result.pendingConfirmation,
            reviewRequired = result.reviewRequired,
            message = message,
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
}
