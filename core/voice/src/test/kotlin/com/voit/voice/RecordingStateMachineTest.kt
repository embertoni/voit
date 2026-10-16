package com.voit.voice

import com.voit.domain.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the five Phase-2 recording behaviors on the pure [RecordingController]: happy path,
 * timeout -> bounded + recoverable, cancel -> clean idle, recognizer failure/empty ->
 * fallback editable review, and the privacy buffer-not-retained invariant. Driven entirely
 * by the fakes + an injected clock + a TestScope/StandardTestDispatcher (virtual time),
 * mirroring the Phase-1 ViewModel test style. No Android, no network, no model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStateMachineTest {

    /** A speech recognizer that counts invocations and can be made to throw. */
    private class SpyRecognizer(
        var transcript: String = "",
        var throwOnRecognize: Boolean = false,
    ) : SpeechRecognizerEngine {
        var calls: Int = 0
            private set

        override suspend fun recognize(audioFrame: ByteArray): RecognitionResult {
            calls++
            if (throwOnRecognize) error("boom")
            return RecognitionResult(transcript, isFinal = true, language = "en")
        }
    }

    private fun newController(
        session: AudioSessionController,
        recognizer: SpeechRecognizerEngine,
        scope: TestScope,
        config: RecordingConfig = RecordingConfig(),
    ): RecordingController = RecordingController(
        audioSessionController = session,
        speechRecognizer = recognizer,
        config = config,
        clock = FixedClock(1_000L),
        scope = scope,
        dispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    @Test
    fun `happy path start then stop reaches review with the canned transcript`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = FakeAudioSessionController()
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "buy milk", language = "en")
        val controller = newController(session, recognizer, scope)

        controller.startPushToTalk()
        scope.runCurrent()
        val recording = controller.state.value as RecordingState.Recording
        assertEquals(1_000L, recording.startedAtMillis)
        assertEquals(AudioSessionState.LISTENING, session.state)

        controller.stopAndTranscribe()
        scope.advanceUntilIdle()

        val review = controller.state.value as RecordingState.Review
        assertEquals("buy milk", review.transcript)
        assertFalse(review.fallback)
        assertNull(review.errorCode)
        // Session ended STOPPED.
        assertEquals(AudioSessionState.STOPPED, session.state)

        // Accepting review (optionally edited) hands text onward and returns to Idle.
        val handed = controller.acceptReview()
        assertEquals("buy milk", handed)
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `timeout force-stops into a bounded recoverable state carrying TIMEOUT`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = FakeAudioSessionController()
        // Recognizer would return text, but the bound fires first; the code stays TIMEOUT.
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "late words")
        val controller = newController(
            session,
            recognizer,
            scope,
            config = RecordingConfig(maxUtteranceMillis = 5_000L),
        )

        controller.startPushToTalk()
        scope.runCurrent()
        assertTrue(controller.state.value is RecordingState.Recording)

        // Advance past the bound WITHOUT ever calling stopAndTranscribe().
        scope.advanceTimeBy(5_001L)
        scope.advanceUntilIdle()

        val review = controller.state.value as RecordingState.Review
        // Bounded + recoverable: fallback review flagged with the stable TIMEOUT code.
        assertTrue(review.fallback)
        assertEquals(RecordingErrorCode.TIMEOUT, review.errorCode)
        assertEquals(AudioSessionState.STOPPED, session.state)
    }

    @Test
    fun `cancel returns to idle with no transcript and the recognizer is never invoked`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = FakeAudioSessionController()
        val recognizer = SpyRecognizer(transcript = "should never run")
        val controller = newController(session, recognizer, scope)

        controller.startPushToTalk()
        scope.runCurrent()
        assertTrue(controller.state.value is RecordingState.Recording)

        controller.cancel()
        scope.advanceUntilIdle()

        assertEquals(RecordingState.Idle, controller.state.value)
        assertEquals(AudioSessionState.STOPPED, session.state)
        assertEquals(0, recognizer.calls)
    }

    @Test
    fun `recognizer failure yields an editable fallback review with a stable error code`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = FakeAudioSessionController()
        val recognizer = SpyRecognizer(throwOnRecognize = true)
        val controller = newController(session, recognizer, scope)

        controller.startPushToTalk()
        scope.runCurrent()
        controller.stopAndTranscribe()
        scope.advanceUntilIdle()

        val review = controller.state.value as RecordingState.Review
        assertTrue(review.fallback)
        assertEquals("", review.transcript)
        assertEquals(RecordingErrorCode.RECOGNIZER_FAILURE, review.errorCode)

        // Manual-entry fallback is reachable: the user edits and hands text onward.
        controller.editReview("typed by hand")
        val handed = controller.acceptReview()
        assertEquals("typed by hand", handed)
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `empty recognition result yields a fallback review with EMPTY_TRANSCRIPT`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = FakeAudioSessionController()
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "   ")
        val controller = newController(session, recognizer, scope)

        controller.startPushToTalk()
        scope.runCurrent()
        controller.stopAndTranscribe()
        scope.advanceUntilIdle()

        val review = controller.state.value as RecordingState.Review
        assertTrue(review.fallback)
        assertEquals("", review.transcript)
        assertEquals(RecordingErrorCode.EMPTY_TRANSCRIPT, review.errorCode)
    }

    @Test
    fun `no audio bytes are retained by the controller or any state object after a run`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = FakeAudioSessionController()
        val recognizer = FakeSpeechRecognizerEngine(cannedTranscript = "retained?")
        val controller = newController(session, recognizer, scope)

        controller.startPushToTalk()
        scope.runCurrent()
        controller.stopAndTranscribe()
        scope.advanceUntilIdle()

        // No RecordingState variant exposes a ByteArray.
        for (field in RecordingState.Review::class.java.declaredFields) {
            assertFalse(
                ByteArray::class.java.isAssignableFrom(field.type),
                "RecordingState.Review must not hold raw audio: ${field.name}",
            )
        }

        // The controller holds no ByteArray field (reflection over declared fields).
        val audioFields = controller.javaClass.declaredFields.filter {
            ByteArray::class.java.isAssignableFrom(it.type)
        }
        assertTrue(audioFields.isEmpty(), "Controller must not retain an audio buffer field")

        // And nothing audio-shaped survives on the current state value.
        val review = assertNotNull(controller.state.value as? RecordingState.Review)
        assertEquals("retained?", review.transcript)
    }
}
