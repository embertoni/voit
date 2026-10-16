package com.voit.voice

import com.voit.domain.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The pure, device-independent WAKE-WORD + SPEAKER-VERIFICATION GATE state machine.
 *
 * This is the Phase-3 analogue of the Phase-2 [RecordingController]: a testable state
 * holder that drives the EXISTING [WakeWordDetector] + [SpeakerVerifier] interfaces
 * (fakes in tests) through the hands-free trigger lifecycle
 * (idle -> listening -> detected -> verifying -> {triggered | rejected}). On a verified
 * trigger it invokes [onTriggered] - the single hand-off seam the app wires to the
 * Phase-2 [RecordingController.startPushToTalk], so wake-word+voice-match replaces the
 * manual push-to-talk tap without the gate ever knowing about recording.
 *
 * It contains NO Android, NO network, and NO model dependency. The detector/verifier are
 * the only ML seams and are injected; tests drive it with the fakes
 * ([FakeWakeWordDetector] + [FakeSpeakerVerifier]), an injected [Clock], and an injected
 * test scope + dispatcher. The real on-device wake-word / speaker models are still pending
 * the Phase-0 hardware benchmark (see docs/models.md); nothing native is bundled here.
 *
 * PRIVACY INVARIANTS (consistent with the Phase-2 recording invariant and
 * docs/privacy.md):
 *  - The raw audio [ByteArray] frames handed to [WakeWordDetector.detect] and
 *    [SpeakerVerifier.verify] are transient locals produced only to feed detection /
 *    verification. They are NEVER assigned to a field, NEVER placed on a [WakeState], and
 *    NEVER persisted. No biometric template or voice embedding is stored by this class.
 *  - Speaker verification is a GATE, not a capture: it yields only a boolean + score, and
 *    only the stable score (a number, not audio) is surfaced on the rejected state.
 *  - There is no file I/O and no networking anywhere in this class. Diagnostics are stable
 *    codes only (see [WakeErrorCode]), never captured audio.
 */

/** Explicit hands-free trigger lifecycle states exposed via [WakeWordController.state]. */
sealed interface WakeState {
    /** Not listening for the wake word. */
    data object Idle : WakeState

    /** Continuously scanning audio frames for the wake word. */
    data object Listening : WakeState

    /** The wake word fired; speaker verification has not run yet. */
    data object Detected : WakeState

    /** Speaker verification is running on the triggering utterance. */
    data object Verifying : WakeState

    /**
     * Wake word + enrolled-speaker match both passed; the downstream capture has been
     * invoked. Transient terminal beat before returning to [Listening] (or [Idle] on stop).
     */
    data object Triggered : WakeState

    /**
     * A detected wake word was NOT accepted. Either the speaker did not match or
     * verification failed; the gate silently rearms back to [Listening] so a false trigger
     * never starts a capture.
     *
     * @param score the verifier score (a number, never audio) when a mismatch is the
     *   reason, else null. Carried for a UI hint / tuning only; no audio is retained.
     * @param errorCode a stable [WakeErrorCode] describing why the trigger was rejected.
     */
    data class Rejected(
        val score: Double? = null,
        val errorCode: String,
    ) : WakeState
}

/** Tuning for the hands-free gate. */
data class WakeConfig(
    /**
     * When true, speaker verification must pass after a wake-word hit before the trigger
     * fires. When false (speaker verification disabled in settings), a wake-word hit alone
     * triggers. Mirrors the privacy-safe default where speaker verification is opt-in.
     */
    val requireSpeakerMatch: Boolean = true,
    /**
     * Minimum verifier score to accept a speaker as the enrolled user, in addition to the
     * verifier's own boolean. A hit must clear BOTH the verifier's `verified` flag and this
     * threshold, so tuning never loosens the verifier's own decision - only tightens it.
     */
    val minSpeakerScore: Double = 0.0,
)

/**
 * Stable diagnostic codes for a rejected trigger. CODES ONLY - never captured audio - so
 * diagnostics stay privacy-safe (consistent with the diagnostics-are-codes rule).
 */
object WakeErrorCode {
    /** The wake word matched but the speaker did not match the enrolled user. */
    const val SPEAKER_MISMATCH: String = "SPEAKER_MISMATCH"

    /** [SpeakerVerifier.verify] threw while checking the speaker. */
    const val VERIFIER_FAILURE: String = "VERIFIER_FAILURE"

    /** [WakeWordDetector.detect] threw while scanning a frame. */
    const val DETECTOR_FAILURE: String = "DETECTOR_FAILURE"
}

/**
 * A source of audio frames for the always-listening scan loop. This is the ONLY place raw
 * audio enters the gate; it is deliberately a small suspend seam so a real capture source
 * (microphone / VAD) can supply frames later while tests supply a scripted, deterministic
 * sequence. Returning null ends the scan loop (e.g. the capture source closed).
 *
 * Implementations MUST hand back a TRANSIENT buffer the caller may discard; the gate never
 * stores it. Pure/device-independent in tests.
 */
fun interface AudioFrameSource {
    /** Suspends until the next audio frame is available, or returns null when exhausted. */
    suspend fun nextFrame(): ByteArray?
}

/**
 * Drives the hands-free wake-word + speaker-verification gate over the injected engines.
 *
 * @param wakeWordDetector the wake-word boundary (fake in tests).
 * @param speakerVerifier the speaker-verification boundary (fake in tests).
 * @param frameSource supplies transient audio frames to scan (scripted in tests).
 * @param onTriggered invoked exactly once per accepted trigger - the hand-off seam the app
 *   wires to [RecordingController.startPushToTalk]. The gate itself does no recording.
 * @param config gate tuning (speaker-match requirement + score threshold).
 * @param clock injected time provider (kept for parity with the Phase-2 controller and for
 *   future diagnostics timestamps; no wall-clock behavior is baked into the gate logic).
 * @param scope the coroutine scope the scan loop runs in; tests pass a TestScope so virtual
 *   time drives it, mirroring the Phase-2 [RecordingController] pattern.
 * @param dispatcher the dispatcher detection/verification run on; tests pass a
 *   StandardTestDispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WakeWordController(
    private val wakeWordDetector: WakeWordDetector,
    private val speakerVerifier: SpeakerVerifier,
    private val frameSource: AudioFrameSource,
    private val onTriggered: suspend () -> Unit,
    private val config: WakeConfig = WakeConfig(),
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<WakeState>(WakeState.Idle)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    /** The running scan job, if any. */
    private var scanJob: Job? = null

    /**
     * Begin always-listening scanning. Moves to [WakeState.Listening] and consumes frames
     * from [frameSource] until [stop]/[cancel], the source is exhausted, or the scope is
     * cancelled. A no-op if a scan is already running.
     */
    fun start() {
        if (scanJob?.isActive == true) return
        _state.value = WakeState.Listening
        scanJob = scope.launch { scanLoop() }
    }

    /**
     * Stop scanning and return to [WakeState.Idle]. A no-op if not scanning. Cancels the
     * in-flight scan so no detection/verification result can publish after stop returns.
     */
    fun stop() {
        val job = scanJob
        scanJob = null
        job?.cancel()
        _state.value = WakeState.Idle
    }

    /** The always-listening loop: scan each transient frame; accept or silently rearm. */
    private suspend fun scanLoop() {
        while (coroutineContext.isActive) {
            // Transient capture frame, scoped to this iteration only: produced by the source,
            // passed to detect()/verify(), then it goes out of scope (GC-eligible) at the
            // end of the loop body. It is NEVER assigned to a field or to a WakeState,
            // honoring the privacy invariant that raw audio is not retained or persisted.
            val frame = frameSource.nextFrame() ?: break

            val detected = detectTransient(frame)
            if (detected == null) {
                // Detector threw: surface a stable code and rearm. A broken detector must
                // not dead-end the gate or leak content.
                rearmRejected(WakeState.Rejected(errorCode = WakeErrorCode.DETECTOR_FAILURE))
                continue
            }
            if (!detected) continue // No wake word in this frame; keep listening.

            _state.value = WakeState.Detected

            if (!config.requireSpeakerMatch) {
                fire()
                continue
            }

            _state.value = WakeState.Verifying
            val verification = verifyTransient(frame)
            when {
                verification == null ->
                    rearmRejected(WakeState.Rejected(errorCode = WakeErrorCode.VERIFIER_FAILURE))

                verification.verified && verification.score >= config.minSpeakerScore ->
                    fire()

                else ->
                    rearmRejected(
                        WakeState.Rejected(
                            score = verification.score,
                            errorCode = WakeErrorCode.SPEAKER_MISMATCH,
                        ),
                    )
            }
        }
        // Loop ended (source exhausted or cancelled): settle on Idle unless a cancel already
        // moved us there. If the scope was cancelled, this line never publishes (we unwind).
        coroutineContext.ensureActive()
        _state.value = WakeState.Idle
        scanJob = null
    }

    /** Accept a trigger: invoke the hand-off, flash [WakeState.Triggered], then rearm. */
    private suspend fun fire() {
        onTriggered()
        _state.value = WakeState.Triggered
        // Rearm to keep listening for the next hands-free trigger. If the scope is still
        // active we fall back to Listening; the loop continues on the next iteration.
        if (coroutineContext.isActive) _state.value = WakeState.Listening
    }

    /** Publish a rejected state then rearm to listening so a false trigger never captures. */
    private fun rearmRejected(rejected: WakeState.Rejected) {
        _state.value = rejected
        _state.value = WakeState.Listening
    }

    /**
     * Run wake-word detection over a transient frame. Returns the boolean result, or null
     * if the detector threw. A [CancellationException] is rethrown (never swallowed) so a
     * [stop]/scope-cancel mid-scan unwinds cleanly instead of publishing a stale state -
     * the same contract the Phase-2 controller enforces around recognition.
     */
    private suspend fun detectTransient(frame: ByteArray): Boolean? =
        try {
            withContext(dispatcher) { wakeWordDetector.detect(frame) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }

    /**
     * Run speaker verification over a transient frame. Returns the result, or null if the
     * verifier threw. A [CancellationException] is rethrown (never swallowed) for the same
     * clean-cancel reason as [detectTransient]. The returned [SpeakerVerificationResult]
     * carries only a boolean + numeric score - never audio or a template.
     */
    private suspend fun verifyTransient(frame: ByteArray): SpeakerVerificationResult? =
        try {
            withContext(dispatcher) { speakerVerifier.verify(frame) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
}
