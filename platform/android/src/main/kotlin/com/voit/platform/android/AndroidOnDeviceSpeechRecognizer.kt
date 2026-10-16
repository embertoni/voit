package com.voit.platform.android

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import com.voit.voice.RecognitionResult
import com.voit.voice.SpeechRecognizerEngine

/**
 * OPTIONAL on-device ASR adapter wrapping Android's own
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer] (API 31 / Android 12+).
 *
 * IMPORTANT (environment note): this adapter COMPILES but CANNOT be run or validated in
 * this sandbox environment, because on-device recognition needs a real device/emulator
 * with the system speech service. It is an OPTIONAL provider/fallback per the Phase 2
 * spec and is NOT the default [SpeechRecognizerEngine] wired into the app (the default
 * stays a safe fake/placeholder from core:voice).
 *
 * It uses ONLY the Android framework [SpeechRecognizer] API: no sherpa-onnx, whisper.cpp,
 * llama.cpp, Picovoice, native-ML library, or bundled model asset is introduced.
 *
 * Design: all branching that CAN be unit-tested (SDK gating, availability, mapping a
 * recognition payload to a [RecognitionResult]) is factored into the pure
 * [OnDeviceAsrSupport] helper. The actual `createOnDeviceSpeechRecognizer()` +
 * `RecognitionListener` wiring (device-only) is isolated in [recognizeOnDevice] and is
 * deliberately left out of the unit tests. Availability is supplied through the injectable
 * [availability] probe so tests never need a live [SpeechRecognizer] or [Context].
 *
 * When on-device recognition is unavailable the adapter routes to the provided
 * [fallback] engine (the FEAT-002 fake) instead of crashing.
 *
 * @param fallback the engine to delegate to when on-device recognition is unavailable.
 * @param sdkInt the running Android SDK level; injectable so the pure path is testable.
 * @param availability probe for framework on-device availability; injectable so tests
 *   never touch a live [SpeechRecognizer]/[Context]. Build one for a real device with
 *   [frameworkAvailability].
 */
class AndroidOnDeviceSpeechRecognizer(
    private val fallback: SpeechRecognizerEngine,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val availability: () -> Boolean,
) : SpeechRecognizerEngine {

    /** True when on-device recognition can actually be used on this device/SDK. */
    fun isAvailable(): Boolean =
        OnDeviceAsrSupport.shouldUseOnDevice(sdkInt, availability())

    /**
     * Recognizes [audioFrame]. If on-device recognition is unavailable this delegates to
     * the [fallback] engine. The real on-device listener wiring is device-only and is NOT
     * reachable/validated in this environment, so for now the unavailable branch (the only
     * branch exercisable here) routes to the fallback.
     */
    override suspend fun recognize(audioFrame: ByteArray): RecognitionResult {
        if (!isAvailable()) {
            return fallback.recognize(audioFrame)
        }
        return recognizeOnDevice(audioFrame)
    }

    /**
     * DEVICE-ONLY, NOT UNIT-TESTED. Placeholder for the real
     * [SpeechRecognizer.createOnDeviceSpeechRecognizer] + [android.speech.RecognitionListener]
     * wiring. Until that is validated on hardware (deferred to Phase 0 on-device work),
     * callers reach this only when [isAvailable] is true; it currently routes to the
     * fallback so no path crashes.
     */
    private suspend fun recognizeOnDevice(audioFrame: ByteArray): RecognitionResult {
        return fallback.recognize(audioFrame)
    }

    companion object {
        /**
         * DEVICE-ONLY, NOT UNIT-TESTED. Builds an availability probe backed by the real
         * framework check [SpeechRecognizer.isOnDeviceRecognitionAvailable]. Call this when
         * constructing the adapter on a real device; tests inject their own lambda instead.
         */
        fun frameworkAvailability(context: Context): () -> Boolean =
            { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }

        /**
         * DEVICE-ONLY convenience factory for real-device construction. Combines the
         * framework availability probe with the running SDK level.
         */
        fun create(
            context: Context,
            fallback: SpeechRecognizerEngine,
        ): AndroidOnDeviceSpeechRecognizer =
            AndroidOnDeviceSpeechRecognizer(
                fallback = fallback,
                availability = frameworkAvailability(context),
            )
    }
}

/**
 * PURE, JVM-unit-testable helpers for the on-device ASR adapter. No [Context], no live
 * [SpeechRecognizer]: SDK gating + result mapping only.
 */
object OnDeviceAsrSupport {

    /** First SDK level exposing on-device [SpeechRecognizer] (Android 12 / S). */
    const val API_ON_DEVICE_ASR = 31

    /** True when the running [sdkInt] is new enough for the on-device API to exist. */
    fun isSupported(sdkInt: Int): Boolean = sdkInt >= API_ON_DEVICE_ASR

    /**
     * Maps a framework recognition payload (the list produced from
     * `Bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)`) to our
     * [RecognitionResult]. Null/empty results map to an empty, final transcript so the
     * caller gets a well-formed "nothing recognized" outcome rather than an exception.
     */
    fun mapResult(
        recognizedPhrases: List<String>?,
        isFinal: Boolean,
        language: String? = null,
    ): RecognitionResult {
        val transcript = recognizedPhrases?.firstOrNull { it.isNotBlank() }.orEmpty()
        return RecognitionResult(
            transcript = transcript,
            isFinal = isFinal,
            language = language,
        )
    }

    /**
     * Decides, from pure inputs, whether a recognition attempt should run on-device or be
     * delegated to the fallback engine. Returns true when on-device should be used.
     */
    fun shouldUseOnDevice(sdkInt: Int, frameworkAvailable: Boolean): Boolean =
        isSupported(sdkInt) && frameworkAvailable
}
