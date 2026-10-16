package com.voit.voice

import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.Intent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakesTest {

    @Test
    fun audioSessionStateTransitions() {
        val session = FakeAudioSessionController()
        assertEquals(AudioSessionState.IDLE, session.state)
        session.start()
        assertEquals(AudioSessionState.LISTENING, session.state)
        session.pause()
        assertEquals(AudioSessionState.PAUSED, session.state)
        session.resume()
        assertEquals(AudioSessionState.LISTENING, session.state)
        session.stop()
        assertEquals(AudioSessionState.STOPPED, session.state)
    }

    @Test
    fun wakeWordDetectorConfigurable() {
        val detector = FakeWakeWordDetector(shouldDetect = false)
        assertFalse(detector.detect(ByteArray(0)))
        detector.shouldDetect = true
        assertTrue(detector.detect(ByteArray(0)))
    }

    @Test
    fun speakerVerifierConfigurable() {
        val verifier = FakeSpeakerVerifier(shouldVerify = false, score = 0.1)
        val result = verifier.verify(ByteArray(0))
        assertFalse(result.verified)
        assertEquals(0.1, result.score)
    }

    @Test
    fun speechRecognizerReturnsCannedTranscript() = runTest {
        val engine = FakeSpeechRecognizerEngine(cannedTranscript = "hello world", language = "en")
        val result = engine.recognize(ByteArray(0))
        assertEquals("hello world", result.transcript)
        assertTrue(result.isFinal)
        assertEquals("en", result.language)
    }

    @Test
    fun intentInterpreterDelegates() {
        val interpreter = FakeIntentInterpreter { if (it == "go") Intent("start") else null }
        assertEquals("start", interpreter.interpret("go")?.name)
        assertNull(interpreter.interpret("other"))
    }

    @Test
    fun actionExecutorRecordsRequests() = runTest {
        val executor = FakeActionExecutor()
        val req = ActionRequest(ActionType.LIST_TASKS)
        assertTrue(executor.execute(req))
        assertEquals(listOf(req), executor.executed)
    }
}
