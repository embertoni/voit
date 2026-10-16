package com.voit.voice

import com.voit.domain.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pure, device-independent push-to-talk RECORDING STATE MACHINE.
 *
 * This is the Phase-2 analogue of Phase-1's TranscriptProcessor wiring: a testable state
 * holder that drives the EXISTING [AudioSessionController] + [SpeechRecognizerEngine]
 * interfaces through the full capture lifecycle
 * (idle -> recording -> transcribing -> review) with cancel, a max-utterance timeout,
 * silence/stop handling, and a fallback to an editable/empty transcript so a manual-entry
 * path always stays reachable.
 *
 * It contains NO Android, NO network, and NO model dependency. Tests drive it with the
 * fakes ([FakeAudioSessionController] + [FakeSpeechRecognizerEngine]), an injected
 * [Clock], and an injected test scope + dispatcher.
 *
 * PRIVACY INVARIANT: the raw audio [ByteArray] handed to [SpeechRecognizerEngine.recognize]
 * is a transient local produced only to feed recognition. It is NEVER stored on this
 * controller or on any [RecordingState] object, it is dereferenced immediately after the
 * recognize call, and nothing audio-related is persisted. There is no file I/O and no
 * networking anywhere in this class. Diagnostics are stable codes only (see
 * [RecordingErrorCode]), never captured content.
 */

/** Explicit recording lifecycle states exposed via [RecordingController.state]. */
sealed interface RecordingState {
    /** No capture in progress. */
    data object Idle : RecordingState

    /** Audio capture is active; [startedAtMillis] is the clock instant capture began. */
    data class Recording(val startedAtMillis: Long) : RecordingState

    /** Capture finished; recognition is running. */
    data object Transcribing : RecordingState

    /**
     * Terminal review state the user lands on.
     *
     * @param transcript the (possibly edited, possibly empty) text to carry onward.
     * @param fallback true when recognition failed / returned empty and the user must edit
     *   or fall back to manual entry; the downstream note/task path stays reachable.
     * @param errorCode a stable [RecordingErrorCode] when [fallback] is true, else null.
     */
    data class Review(
        val transcript: String,
        val fallback: Boolean = false,
        val errorCode: String? = null,
    ) : RecordingState
}

/** Tuning for a capture session. */
data class RecordingConfig(
    /** Upper bound on a single utterance; capture is force-stopped past this. */
    val maxUtteranceMillis: Long = 15_000L,
)

/**
 * Stable diagnostic codes. These are CODES ONLY and never carry captured content, keeping
 * diagnostics privacy-safe (consistent with the diagnostics-are-codes rule).
 */
object RecordingErrorCode {
    /** The utterance exceeded [RecordingConfig.maxUtteranceMillis] and was force-stopped. */
    const val TIMEOUT: String = "TIMEOUT"

    /** [SpeechRecognizerEngine.recognize] threw. */
    const val RECOGNIZER_FAILURE: String = "RECOGNIZER_FAILURE"

    /** Recognition produced a blank / whitespace-only transcript. */
    const val EMPTY_TRANSCRIPT: String = "EMPTY_TRANSCRIPT"

    /** The user cancelled the capture. */
    const val CANCELLED: String = "CANCELLED"
}

/**
 * Drives a single push-to-talk capture at a time over the injected engines.
 *
 * @param audioSessionController the capture session boundary (fake in tests).
 * @param speechRecognizer the ASR boundary (fake in tests).
 * @param config capture tuning (max-utterance bound).
 * @param clock injected time provider for deterministic [RecordingState.Recording.startedAtMillis].
 * @param scope the coroutine scope the capture runs in; tests pass a TestScope so virtual
 *   time (advanceTimeBy / advanceUntilIdle) drives the timeout. Mirrors the Phase-1
 *   ViewModel pattern of an injected scope instead of an Android main looper.
 * @param dispatcher the dispatcher recognition runs on; tests pass a StandardTestDispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordingController(
    private val audioSessionController: AudioSessionController,
    private val speechRecognizer: SpeechRecognizerEngine,
    private val config: RecordingConfig = RecordingConfig(),
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** The running capture job, if any. */
    private var captureJob: Job? = null

    /** Completed by [stopAndTranscribe] to end capture with a normal stop. */
    private var stopSignal: CompletableDeferred<Unit>? = null

    /**
     * Begin a push-to-talk capture. Starts the audio session, moves to
     * [RecordingState.Recording], and arms the max-utterance bound. Capture ends on an
     * explicit [stopAndTranscribe], a [cancel], or the timeout - whichever comes first.
     * A no-op if a capture is already running.
     */
    fun startPushToTalk() {
        if (captureJob?.isActive == true) return

        val stop = CompletableDeferred<Unit>()
        stopSignal = stop

        audioSessionController.start()
        val startedAt = clock.nowEpochMillis()
        _state.value = RecordingState.Recording(startedAt)

        captureJob = scope.launch {
            // Wait for either an explicit stop or the max-utterance timeout. Using the
            // scope's virtual time means a StandardTestDispatcher drives the bound in tests.
            val timedOut = select<Boolean> {
                stop.onAwait { false }
                onTimeout(config.maxUtteranceMillis) { true }
            }

            if (timedOut) {
                // Bounded capture: force-stop and surface a RECOVERABLE timeout.
                finishWithTimeout()
            } else {
                transcribeAndReview()
            }
        }
    }

    /**
     * End the current capture and run recognition. Transitions to
     * [RecordingState.Transcribing] then [RecordingState.Review]. A no-op if not recording.
     */
    fun stopAndTranscribe() {
        if (captureJob?.isActive != true) return
        stopSignal?.complete(Unit)
    }

    /**
     * Cancel the current capture. Stops the audio session, discards any in-progress buffer,
     * never invokes the recognizer, and returns cleanly to [RecordingState.Idle].
     */
    fun cancel() {
        val job = captureJob
        captureJob = null
        stopSignal = null
        job?.cancel()
        audioSessionController.stop()
        // No transcript, no recognition, no persistence: clean idle.
        _state.value = RecordingState.Idle
    }

    /**
     * Hand a (possibly edited) transcript onward from a [RecordingState.Review] and return
     * to [RecordingState.Idle]. The returned text is what the downstream note/task path
     * consumes; it may be the recognized text, user-edited text, or manual entry after a
     * fallback. A no-op if not currently in review.
     */
    fun acceptReview(edited: String? = null): String? {
        val current = _state.value as? RecordingState.Review ?: return null
        val finalText = edited ?: current.transcript
        _state.value = RecordingState.Idle
        return finalText
    }

    /**
     * Replace the transcript shown in a [RecordingState.Review] (keeping it reviewable) so
     * the user can correct a result or type one after a fallback. A no-op if not in review.
     */
    fun editReview(text: String) {
        val current = _state.value as? RecordingState.Review ?: return
        _state.value = current.copy(transcript = text)
    }

    /** Normal stop: run recognition and route to a review state. */
    private suspend fun transcribeAndReview() {
        audioSessionController.stop()
        _state.value = RecordingState.Transcribing
        recognizeInto(errorCodeOnFailure = RecordingErrorCode.RECOGNIZER_FAILURE)
        captureJob = null
        stopSignal = null
    }

    /** Timeout path: force-stop, run recognition, but keep the recoverable TIMEOUT code. */
    private suspend fun finishWithTimeout() {
        audioSessionController.stop()
        _state.value = RecordingState.Transcribing
        recognizeInto(
            errorCodeOnFailure = RecordingErrorCode.RECOGNIZER_FAILURE,
            forcedFallbackCode = RecordingErrorCode.TIMEOUT,
        )
        captureJob = null
        stopSignal = null
    }

    /**
     * Feed a transient audio buffer to recognition and transition to [RecordingState.Review].
     *
     * The [audioBuffer] below is the ONLY place raw audio exists in this class: a local that
     * is passed to [SpeechRecognizerEngine.recognize] and then dereferenced. It is never
     * assigned to a field, never placed on a [RecordingState], and never persisted. On
     * failure or an empty result we always land on an editable fallback review so the
     * manual-entry path remains reachable (we never dead-end).
     *
     * @param forcedFallbackCode when non-null, the review is always a fallback carrying this
     *   code (used for TIMEOUT), regardless of whether recognition produced text.
     */
    private suspend fun recognizeInto(
        errorCodeOnFailure: String,
        forcedFallbackCode: String? = null,
    ) {
        // Transient capture buffer, scoped to this call only: it is produced, handed to
        // recognize(), and then goes out of scope (GC-eligible) the moment recognition
        // returns. It is NEVER assigned to a field or to a RecordingState, honoring the
        // privacy invariant that raw audio is not retained or persisted.
        val result: RecognitionResult? = recognizeTransient()

        _state.value = when {
            result == null ->
                RecordingState.Review(
                    transcript = "",
                    fallback = true,
                    errorCode = forcedFallbackCode ?: errorCodeOnFailure,
                )

            result.transcript.isBlank() ->
                RecordingState.Review(
                    transcript = "",
                    fallback = true,
                    errorCode = forcedFallbackCode ?: RecordingErrorCode.EMPTY_TRANSCRIPT,
                )

            forcedFallbackCode != null ->
                // Bounded (e.g. timed-out) capture that still produced text: surface the
                // recoverable code but keep the recognized text editable in review.
                RecordingState.Review(
                    transcript = result.transcript,
                    fallback = true,
                    errorCode = forcedFallbackCode,
                )

            else ->
                RecordingState.Review(transcript = result.transcript, fallback = false)
        }
    }

    /**
     * Run recognition over a transient audio buffer and return the result, or null on
     * failure. The buffer is a local argument scoped entirely to this function: it never
     * escapes to a field or to a [RecordingState], so after this returns no raw audio is
     * reachable from the controller. Pure/device-independent (no real microphone here).
     */
    private suspend fun recognizeTransient(): RecognitionResult? =
        try {
            // TODO(phase-0 real capture): the empty ByteArray(0) below is a PLACEHOLDER
            //  buffer. The default engine is FakeSpeechRecognizerEngine, which ignores its
            //  input and returns a canned transcript, so no real audio flows here yet. When
            //  a REAL capture source (microphone / on-device recognizer) is wired in behind
            //  AudioSessionController, the ACTUAL captured audio buffer MUST be supplied here
            //  instead of ByteArray(0) - otherwise a real recognizer would receive no audio
            //  (a silent no-audio bug). The buffer must stay a TRANSIENT local (never stored
            //  on this controller, never placed on a RecordingState, never persisted) to
            //  keep the raw-audio-not-retained privacy invariant intact.
            withContext(dispatcher) { speechRecognizer.recognize(ByteArray(0)) }
        } catch (c: CancellationException) {
            // A coroutine cancellation (e.g. cancel() firing mid-recognition) must NOT be
            // swallowed into a null "recognizer failure" result: doing so would let a
            // cancelled run publish a Review state after cancel() already returned to Idle,
            // racing cancel()'s clean-idle contract. Rethrow so the capture job unwinds and
            // the Idle state cancel() set stands.
            throw c
        } catch (t: Throwable) {
            null
        }
}
