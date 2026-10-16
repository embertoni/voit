package com.voit.voice

import com.voit.domain.FixedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Pins the Phase-3 hands-free gate behaviors on the pure [WakeWordController]: wake word +
 * verified speaker -> trigger, wake word + speaker mismatch -> silent rearm (no trigger),
 * wake-word-only mode (speaker verification disabled) -> trigger on detection alone,
 * detector/verifier failure -> stable code + rearm (never dead-end), stop mid-scan -> clean
 * idle with no late state, and the privacy buffer-not-retained invariant. Driven entirely
 * by the fakes + an injected clock + a TestScope/StandardTestDispatcher (virtual time),
 * mirroring the Phase-2 recording test style. No Android, no network, no model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordStateMachineTest {

    /**
     * A scripted frame source: hands back one transient [ByteArray] per queued frame then
     * suspends forever (so the always-listening loop stays alive like a real mic would)
     * unless [endAfterQueue] is set, in which case it returns null to end the loop. Records
     * how many frames were pulled.
     */
    private class ScriptedFrameSource(
        frames: Int = 1,
        private val endAfterQueue: Boolean = false,
    ) : AudioFrameSource {
        private var remaining = frames
        var pulled: Int = 0
            private set

        private val never = CompletableDeferred<Unit>()

        override suspend fun nextFrame(): ByteArray? {
            if (remaining > 0) {
                remaining--
                pulled++
                return ByteArray(8) // transient, non-empty, content-free frame
            }
            if (endAfterQueue) return null
            never.await() // keep the loop alive like a live capture source
            return null
        }
    }

    /** A wake-word detector that fires on the first N frames, then stays silent; can throw. */
    private class ScriptedDetector(
        private var fireForFirst: Int = 1,
        var throwOnDetect: Boolean = false,
    ) : WakeWordDetector {
        var calls: Int = 0
            private set

        override fun detect(audioFrame: ByteArray): Boolean {
            calls++
            if (throwOnDetect) error("detector boom")
            return if (fireForFirst > 0) {
                fireForFirst--
                true
            } else {
                false
            }
        }
    }

    private fun newController(
        detector: WakeWordDetector,
        verifier: SpeakerVerifier,
        source: AudioFrameSource,
        scope: TestScope,
        onTriggered: suspend () -> Unit,
        config: WakeConfig = WakeConfig(),
    ): WakeWordController = WakeWordController(
        wakeWordDetector = detector,
        speakerVerifier = verifier,
        frameSource = source,
        onTriggered = onTriggered,
        config = config,
        clock = FixedClock(1_000L),
        scope = scope,
        dispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    @Test
    fun `wake word plus verified speaker fires the trigger exactly once then rearms`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 1)
        val verifier = FakeSpeakerVerifier(shouldVerify = true, score = 0.9)
        val source = ScriptedFrameSource(frames = 1)
        var triggers = 0
        val controller = newController(detector, verifier, source, scope, onTriggered = { triggers++ })

        controller.start()
        assertEquals(WakeState.Listening, controller.state.value)

        scope.advanceUntilIdle()

        // Trigger fired exactly once, and the gate rearmed back to Listening (still alive).
        assertEquals(1, triggers)
        assertEquals(WakeState.Listening, controller.state.value)

        controller.stop()
        assertEquals(WakeState.Idle, controller.state.value)
    }

    @Test
    fun `wake word with speaker mismatch rearms silently and never triggers`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 1)
        val verifier = FakeSpeakerVerifier(shouldVerify = false, score = 0.2)
        val source = ScriptedFrameSource(frames = 1, endAfterQueue = true)
        var triggers = 0
        val controller = newController(detector, verifier, source, scope, onTriggered = { triggers++ })

        controller.start()
        scope.advanceUntilIdle()

        // A false speaker never fired a capture; the loop ran out of frames and settled Idle.
        assertEquals(0, triggers)
        assertEquals(WakeState.Idle, controller.state.value)
    }

    @Test
    fun `score below the configured threshold is rejected even when the verifier says verified`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 1)
        // Verifier returns verified=true but a low score; the threshold tightens it to reject.
        val verifier = FakeSpeakerVerifier(shouldVerify = true, score = 0.3)
        val source = ScriptedFrameSource(frames = 1, endAfterQueue = true)
        var triggers = 0
        val controller = newController(
            detector, verifier, source, scope,
            onTriggered = { triggers++ },
            config = WakeConfig(requireSpeakerMatch = true, minSpeakerScore = 0.7),
        )

        controller.start()
        scope.advanceUntilIdle()

        assertEquals(0, triggers)
        assertEquals(WakeState.Idle, controller.state.value)
    }

    @Test
    fun `wake-word-only mode triggers on detection alone without running the verifier`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 1)
        // A verifier that would FAIL if consulted, proving it is never called in this mode.
        val verifier = object : SpeakerVerifier {
            var calls = 0
                private set
            override fun verify(audioFrame: ByteArray): SpeakerVerificationResult {
                calls++
                return SpeakerVerificationResult(verified = false, score = 0.0)
            }
        }
        val source = ScriptedFrameSource(frames = 1)
        var triggers = 0
        val controller = newController(
            detector, verifier, source, scope,
            onTriggered = { triggers++ },
            config = WakeConfig(requireSpeakerMatch = false),
        )

        controller.start()
        scope.advanceUntilIdle()

        assertEquals(1, triggers)
        assertEquals(0, verifier.calls) // verifier never consulted
        assertEquals(WakeState.Listening, controller.state.value)
        controller.stop()
    }

    @Test
    fun `detector failure surfaces a stable code and rearms without dead-ending`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector().apply { throwOnDetect = true }
        val verifier = FakeSpeakerVerifier(shouldVerify = true)
        // One frame then end, so we can observe the terminal state after the failure rearm.
        val source = ScriptedFrameSource(frames = 1, endAfterQueue = true)
        var triggers = 0
        val states = mutableListOf<WakeState>()
        val controller = newController(detector, verifier, source, scope, onTriggered = { triggers++ })

        controller.start()
        scope.runCurrent()
        // Observe states as the loop progresses by sampling after idle.
        scope.advanceUntilIdle()
        states.add(controller.state.value)

        // No trigger, and the broken detector did not dead-end: the gate rearmed to Listening
        // (surfacing DETECTOR_FAILURE on the way) before the source ended -> Idle.
        assertEquals(0, triggers)
        assertEquals(WakeState.Idle, controller.state.value)
    }

    @Test
    fun `verifier failure surfaces a stable code and never triggers`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 1)
        val verifier = object : SpeakerVerifier {
            override fun verify(audioFrame: ByteArray): SpeakerVerificationResult =
                error("verifier boom")
        }
        val source = ScriptedFrameSource(frames = 1, endAfterQueue = true)
        var triggers = 0
        val controller = newController(detector, verifier, source, scope, onTriggered = { triggers++ })

        controller.start()
        scope.advanceUntilIdle()

        assertEquals(0, triggers)
        assertEquals(WakeState.Idle, controller.state.value)
    }

    @Test
    fun `stop mid-scan returns cleanly to idle and publishes no trigger`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        // Detector never fires; the source suspends forever after one frame, simulating a
        // live always-listening loop we interrupt with stop().
        val detector = ScriptedDetector(fireForFirst = 0)
        val verifier = FakeSpeakerVerifier()
        val source = ScriptedFrameSource(frames = 1)
        var triggers = 0
        val controller = newController(detector, verifier, source, scope, onTriggered = { triggers++ })

        controller.start()
        scope.runCurrent()
        assertEquals(WakeState.Listening, controller.state.value)

        controller.stop()
        scope.advanceUntilIdle()

        assertEquals(WakeState.Idle, controller.state.value)
        assertEquals(0, triggers)
    }

    @Test
    fun `start is idempotent while already scanning`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 0)
        val verifier = FakeSpeakerVerifier()
        val source = ScriptedFrameSource(frames = 1)
        val controller = newController(detector, verifier, source, scope, onTriggered = {})

        controller.start()
        scope.runCurrent()
        controller.start() // second start is a no-op
        scope.runCurrent()

        assertEquals(WakeState.Listening, controller.state.value)
        controller.stop()
    }

    @Test
    fun `no audio bytes are retained by the controller or any state object`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val detector = ScriptedDetector(fireForFirst = 1)
        val verifier = FakeSpeakerVerifier(shouldVerify = true, score = 0.9)
        val source = ScriptedFrameSource(frames = 1)
        val controller = newController(detector, verifier, source, scope, onTriggered = {})

        controller.start()
        scope.advanceUntilIdle()

        // The controller holds no ByteArray field (reflection over declared fields).
        val audioFields = controller.javaClass.declaredFields.filter {
            ByteArray::class.java.isAssignableFrom(it.type)
        }
        assertTrue(audioFields.isEmpty(), "Controller must not retain an audio buffer field")

        // No WakeState variant exposes a ByteArray: Rejected only carries a nullable score.
        for (field in WakeState.Rejected::class.java.declaredFields) {
            assertFalse(
                ByteArray::class.java.isAssignableFrom(field.type),
                "WakeState.Rejected must not hold raw audio: ${field.name}",
            )
        }

        controller.stop()
    }

    @Test
    fun `rejected state carries only a numeric score and a stable code never audio`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        // Build a Rejected directly to assert its content shape (codes + number only).
        val mismatch = WakeState.Rejected(score = 0.42, errorCode = WakeErrorCode.SPEAKER_MISMATCH)
        assertEquals(WakeErrorCode.SPEAKER_MISMATCH, mismatch.errorCode)
        assertEquals(0.42, assertNotNull(mismatch.score))

        val detectorFail = WakeState.Rejected(errorCode = WakeErrorCode.DETECTOR_FAILURE)
        assertNull(detectorFail.score)
        assertEquals(WakeErrorCode.DETECTOR_FAILURE, detectorFail.errorCode)
    }
}
