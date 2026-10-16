package com.voit.voice

import com.voit.domain.ActionRequest
import com.voit.domain.Intent

/**
 * In-memory fakes for the voice engine adapters. These let later phases and tests exercise
 * the pipeline deterministically without any native/ML engine or a real microphone.
 */

/** Fake audio session that just tracks state transitions. */
class FakeAudioSessionController(initial: AudioSessionState = AudioSessionState.IDLE) : AudioSessionController {
    override var state: AudioSessionState = initial
        private set

    override fun start() { state = AudioSessionState.LISTENING }
    override fun pause() { if (state == AudioSessionState.LISTENING) state = AudioSessionState.PAUSED }
    override fun resume() { if (state == AudioSessionState.PAUSED) state = AudioSessionState.LISTENING }
    override fun stop() { state = AudioSessionState.STOPPED }
}

/** Fake wake-word detector returning a configurable, fixed result. */
class FakeWakeWordDetector(var shouldDetect: Boolean = true) : WakeWordDetector {
    override fun detect(audioFrame: ByteArray): Boolean = shouldDetect
}

/** Fake speaker verifier returning a configurable result. */
class FakeSpeakerVerifier(
    var shouldVerify: Boolean = true,
    var score: Double = 1.0,
) : SpeakerVerifier {
    override fun verify(audioFrame: ByteArray): SpeakerVerificationResult =
        SpeakerVerificationResult(shouldVerify, score)
}

/** Fake ASR engine returning a canned/provided transcript. */
class FakeSpeechRecognizerEngine(
    var cannedTranscript: String = "",
    var language: String? = null,
) : SpeechRecognizerEngine {
    override suspend fun recognize(audioFrame: ByteArray): RecognitionResult =
        RecognitionResult(cannedTranscript, isFinal = true, language = language)
}

/** Fake interpreter that delegates to a provided lambda (defaults to no match). */
class FakeIntentInterpreter(
    var handler: (String) -> Intent? = { null },
) : IntentInterpreter {
    override fun interpret(transcript: String): Intent? = handler(transcript)
}

/** Fake action executor that records what it was asked to run. */
class FakeActionExecutor(var succeed: Boolean = true) : ActionExecutor {
    val executed = mutableListOf<ActionRequest>()
    override suspend fun execute(request: ActionRequest): Boolean {
        executed.add(request)
        return succeed
    }
}
