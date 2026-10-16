package com.voit.voice

import com.voit.domain.ActionRequest
import com.voit.domain.Intent

/**
 * Engine-adapter interfaces for the voice pipeline. Phase 1 ships INTERFACES ONLY plus
 * simple fakes (see Fakes.kt); no native/ML dependency (sherpa-onnx, whisper.cpp,
 * llama.cpp, Picovoice) is pulled in. Later phases swap in real engines behind these.
 */

/** State of the audio capture session. */
enum class AudioSessionState {
    IDLE,
    LISTENING,
    PAUSED,
    STOPPED,
}

/** Controls the microphone/audio capture session. */
interface AudioSessionController {
    val state: AudioSessionState
    fun start()
    fun pause()
    fun resume()
    fun stop()
}

/** Detects the wake word in an audio frame / buffer. */
interface WakeWordDetector {
    /** Returns true when the configured wake word is detected in [audioFrame]. */
    fun detect(audioFrame: ByteArray): Boolean
}

/** Result of a speaker-verification check. */
data class SpeakerVerificationResult(val verified: Boolean, val score: Double)

/** Verifies that speech came from the enrolled speaker. */
interface SpeakerVerifier {
    fun verify(audioFrame: ByteArray): SpeakerVerificationResult
}

/** A speech recognition (ASR) result. */
data class RecognitionResult(
    val transcript: String,
    val isFinal: Boolean,
    val language: String? = null,
)

/** Converts audio into text. */
interface SpeechRecognizerEngine {
    suspend fun recognize(audioFrame: ByteArray): RecognitionResult
}

/** The interpretation boundary: transcript -> validated structured [Intent]. */
interface IntentInterpreter {
    fun interpret(transcript: String): Intent?
}

/** The execution boundary: performs an authorized [ActionRequest]. */
interface ActionExecutor {
    suspend fun execute(request: ActionRequest): Boolean
}
