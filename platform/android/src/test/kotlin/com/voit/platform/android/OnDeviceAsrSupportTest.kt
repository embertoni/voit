package com.voit.platform.android

import com.voit.voice.FakeSpeechRecognizerEngine
import com.voit.voice.RecognitionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM unit tests for the on-device ASR adapter's testable surface
 * ([OnDeviceAsrSupport]) plus the unavailable -> fallback routing of
 * [AndroidOnDeviceSpeechRecognizer] with an injected availability probe. No real
 * android [android.speech.SpeechRecognizer] or [android.content.Context] is constructed.
 */
class OnDeviceAsrSupportTest {

    private val legacySdk = 30 // below API 31
    private val supportedSdk = 33 // API 31+

    @Test
    fun isSupported_belowApi31_false() {
        assertFalse(OnDeviceAsrSupport.isSupported(legacySdk))
    }

    @Test
    fun isSupported_api31AndUp_true() {
        assertTrue(OnDeviceAsrSupport.isSupported(OnDeviceAsrSupport.API_ON_DEVICE_ASR))
        assertTrue(OnDeviceAsrSupport.isSupported(supportedSdk))
    }

    @Test
    fun shouldUseOnDevice_requiresBothSdkAndFrameworkAvailability() {
        assertFalse(OnDeviceAsrSupport.shouldUseOnDevice(legacySdk, frameworkAvailable = true))
        assertFalse(OnDeviceAsrSupport.shouldUseOnDevice(supportedSdk, frameworkAvailable = false))
        assertTrue(OnDeviceAsrSupport.shouldUseOnDevice(supportedSdk, frameworkAvailable = true))
    }

    @Test
    fun mapResult_firstNonBlankPhrase_mapsToTranscript() {
        val result = OnDeviceAsrSupport.mapResult(
            recognizedPhrases = listOf("  ", "lembrar do leite", "milk reminder"),
            isFinal = true,
            language = "pt-BR",
        )
        assertEquals(
            RecognitionResult(transcript = "lembrar do leite", isFinal = true, language = "pt-BR"),
            result,
        )
    }

    @Test
    fun mapResult_nullOrEmpty_mapsToEmptyFinalTranscript() {
        val fromNull = OnDeviceAsrSupport.mapResult(recognizedPhrases = null, isFinal = true)
        assertEquals(RecognitionResult(transcript = "", isFinal = true, language = null), fromNull)

        val fromEmpty = OnDeviceAsrSupport.mapResult(recognizedPhrases = emptyList(), isFinal = false)
        assertEquals(RecognitionResult(transcript = "", isFinal = false, language = null), fromEmpty)
    }

    @Test
    fun adapter_belowApi31_isUnavailable_routesToFallback() = runTest {
        val fallback = FakeSpeechRecognizerEngine(cannedTranscript = "fallback transcript", language = "en-US")
        val adapter = AndroidOnDeviceSpeechRecognizer(
            fallback = fallback,
            sdkInt = legacySdk,
            availability = { true },
        )

        assertFalse(adapter.isAvailable())

        val result = adapter.recognize(ByteArray(4))
        assertEquals("fallback transcript", result.transcript)
        assertEquals("en-US", result.language)
    }

    @Test
    fun adapter_frameworkUnavailable_routesToFallback() = runTest {
        val fallback = FakeSpeechRecognizerEngine(cannedTranscript = "canned", language = "pt-BR")
        val adapter = AndroidOnDeviceSpeechRecognizer(
            fallback = fallback,
            sdkInt = supportedSdk,
            availability = { false },
        )

        assertFalse(adapter.isAvailable())
        assertEquals("canned", adapter.recognize(ByteArray(1)).transcript)
    }

    @Test
    fun adapter_supportedAndAvailable_reportsAvailable() {
        val adapter = AndroidOnDeviceSpeechRecognizer(
            fallback = FakeSpeechRecognizerEngine(),
            sdkInt = supportedSdk,
            availability = { true },
        )
        assertTrue(adapter.isAvailable())
    }
}
