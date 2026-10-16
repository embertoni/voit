package com.voit.actions

import com.voit.domain.ActionRequest
import com.voit.domain.ActionRun
import com.voit.domain.ActionRunErrorCode
import com.voit.domain.ActionRunRepository
import com.voit.domain.ActionRunStatus
import com.voit.domain.ActionType
import com.voit.domain.FixedClock
import com.voit.domain.ParamValue
import com.voit.domain.SequentialIdGenerator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Phase-5 redacted audit recorder: a dispatched/rejected command writes exactly
 * one code-only ActionRun; pending/no-match results write nothing; free-text failure
 * reasons are collapsed to stable error codes (never stored verbatim). Uses an in-memory
 * fake repository + FixedClock + SequentialIdGenerator.
 */
class ActionRunRecorderTest {

    private class FakeActionRunRepository : ActionRunRepository {
        val recorded = mutableListOf<ActionRun>()
        override suspend fun record(run: ActionRun) { recorded.add(run) }
        override suspend fun history(): List<ActionRun> = recorded.reversed()
        override suspend fun clear() { recorded.clear() }
    }

    private fun recorder(repo: ActionRunRepository) = ActionRunRecorder(
        repository = repo,
        clock = FixedClock(5_000L),
        ids = SequentialIdGenerator("run-"),
    )

    private fun speak(text: String) =
        ActionRequest(ActionType.SPEAK_RESPONSE, mapOf("text" to ParamValue.Text(text)))

    @Test
    fun `a completed dispatch records SUCCESS with no error code`() = runTest {
        val repo = FakeActionRunRepository()
        val outcome = WorkflowOutcome.Completed(
            steps = listOf(StepResult(speak("a"), ActionResult(ActionType.SPEAK_RESPONSE), null)),
        )
        val run = recorder(repo).record(CommandDispatchResult.Dispatched("c1", outcome), startedAt = 4_000L)

        val saved = assertNotNull(run)
        assertEquals(1, repo.recorded.size)
        assertEquals("c1", saved.commandId)
        assertEquals(ActionRunStatus.SUCCESS, saved.status)
        assertNull(saved.errorCode)
        assertEquals(4_000L, saved.startedAt)
        assertEquals(5_000L, saved.endedAt)
    }

    @Test
    fun `a timeout partial failure records PARTIAL_FAILURE with STEP_TIMEOUT`() = runTest {
        val repo = FakeActionRunRepository()
        val outcome = WorkflowOutcome.PartialFailure(
            steps = emptyList(),
            failedReason = "Step SPEAK_RESPONSE timed out after 10ms",
        )
        val saved = assertNotNull(
            recorder(repo).record(CommandDispatchResult.Dispatched("c1", outcome)),
        )
        assertEquals(ActionRunStatus.PARTIAL_FAILURE, saved.status)
        assertEquals(ActionRunErrorCode.STEP_TIMEOUT, saved.errorCode)
    }

    @Test
    fun `an unknown-action partial failure records UNKNOWN_ACTION`() = runTest {
        val repo = FakeActionRunRepository()
        val outcome = WorkflowOutcome.PartialFailure(
            steps = emptyList(),
            failedReason = "Unknown action type OPEN_APP",
        )
        val saved = assertNotNull(
            recorder(repo).record(CommandDispatchResult.Dispatched("c1", outcome)),
        )
        assertEquals(ActionRunErrorCode.UNKNOWN_ACTION, saved.errorCode)
    }

    @Test
    fun `a generic step failure reason is redacted to STEP_FAILED and never stored verbatim`() = runTest {
        val repo = FakeActionRunRepository()
        // A handler message that could echo user content must NOT be stored.
        val outcome = WorkflowOutcome.PartialFailure(
            steps = emptyList(),
            failedReason = "boom: sensitive note body leaked here",
        )
        val saved = assertNotNull(
            recorder(repo).record(CommandDispatchResult.Dispatched("c1", outcome)),
        )
        assertEquals(ActionRunStatus.PARTIAL_FAILURE, saved.status)
        assertEquals(ActionRunErrorCode.STEP_FAILED, saved.errorCode)
        // The verbatim reason is nowhere on the stored record.
        assertTrue(repo.recorded.none { it.errorCode?.contains("sensitive") == true })
        assertTrue(repo.recorded.none { it.resultCode?.contains("sensitive") == true })
    }

    @Test
    fun `a cancelled outcome records CANCELLED with no error code`() = runTest {
        val repo = FakeActionRunRepository()
        val outcome = WorkflowOutcome.Cancelled(steps = emptyList())
        val saved = assertNotNull(
            recorder(repo).record(CommandDispatchResult.Dispatched("c1", outcome)),
        )
        assertEquals(ActionRunStatus.CANCELLED, saved.status)
        assertNull(saved.errorCode)
    }

    @Test
    fun `an already-run outcome records ALREADY_RUN preserving the underlying error class`() = runTest {
        val repo = FakeActionRunRepository()
        val inner = WorkflowOutcome.Completed(steps = emptyList())
        val outcome = WorkflowOutcome.AlreadyRun(inner)
        val saved = assertNotNull(
            recorder(repo).record(CommandDispatchResult.Dispatched("c1", outcome)),
        )
        assertEquals(ActionRunStatus.ALREADY_RUN, saved.status)
        assertNull(saved.errorCode)
    }

    @Test
    fun `a rejected dispatch records REJECTED with WORKFLOW_REJECTED`() = runTest {
        val repo = FakeActionRunRepository()
        val saved = assertNotNull(
            recorder(repo).record(CommandDispatchResult.Rejected("c1", "Command has no actions")),
        )
        assertEquals(ActionRunStatus.REJECTED, saved.status)
        assertEquals(ActionRunErrorCode.WORKFLOW_REJECTED, saved.errorCode)
    }

    @Test
    fun `no-match and pending results record nothing`() = runTest {
        val repo = FakeActionRunRepository()
        val rec = recorder(repo)
        assertNull(rec.record(CommandDispatchResult.NoMatch))
        assertNull(rec.record(CommandDispatchResult.NeedsChoice(listOf("a", "b"))))
        assertNull(rec.record(CommandDispatchResult.NeedsConfirmation("c1", "needs confirm")))
        assertTrue(repo.recorded.isEmpty())
    }
}
