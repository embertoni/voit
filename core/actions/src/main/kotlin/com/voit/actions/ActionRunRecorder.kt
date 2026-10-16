package com.voit.actions

import com.voit.domain.ActionRun
import com.voit.domain.ActionRunErrorCode
import com.voit.domain.ActionRunRepository
import com.voit.domain.ActionRunStatus
import com.voit.domain.Clock
import com.voit.domain.IdGenerator
import com.voit.domain.SystemClock
import com.voit.domain.UuidGenerator

/**
 * Turns a [CommandDispatchResult] / [WorkflowOutcome] into a REDACTED [ActionRun] and
 * persists it through the [ActionRunRepository]. This is the Phase-5 audit seam: when a
 * dispatched command runs, exactly one code-only record is written - never the transcript,
 * the matched phrase, note/task content, or an exception message.
 *
 * The mapping from a free-text failure reason to a stable [ActionRunErrorCode] is the
 * REDACTION step: the runner's `StepResult.error` / `failedReason` may contain a handler's
 * exception message (potentially echoing user content), so it is NEVER stored. Only the
 * CLASS of failure is recorded, inferred from the outcome shape.
 *
 * Pure except for the single repository write; fully unit-testable with a fake repository +
 * injected [Clock]/[IdGenerator].
 */
class ActionRunRecorder(
    private val repository: ActionRunRepository,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidGenerator,
) {

    /**
     * Record the outcome of dispatching [commandId].
     *
     * Only terminal dispatch results are recorded. A [CommandDispatchResult.NoMatch],
     * [CommandDispatchResult.NeedsChoice], or [CommandDispatchResult.NeedsConfirmation] is
     * NOT an executed run (nothing happened / we are waiting on the user), so nothing is
     * written and the method returns null. A [CommandDispatchResult.Rejected] or
     * [CommandDispatchResult.Dispatched] writes one record and returns it.
     *
     * @param startedAt when the dispatch began (caller captures it before dispatching so the
     *   duration is meaningful); defaults to now.
     */
    suspend fun record(
        result: CommandDispatchResult,
        startedAt: Long = clock.nowEpochMillis(),
    ): ActionRun? {
        val run = when (result) {
            is CommandDispatchResult.Dispatched ->
                buildRun(result.commandId, result.outcome, startedAt)
            is CommandDispatchResult.Rejected ->
                ActionRun(
                    id = ids.newId(),
                    commandId = result.commandId,
                    startedAt = startedAt,
                    endedAt = clock.nowEpochMillis(),
                    status = ActionRunStatus.REJECTED,
                    errorCode = ActionRunErrorCode.WORKFLOW_REJECTED,
                )
            // Not an executed run: nothing to audit.
            CommandDispatchResult.NoMatch,
            is CommandDispatchResult.NeedsChoice,
            is CommandDispatchResult.NeedsConfirmation,
            -> null
        } ?: return null

        repository.record(run)
        return run
    }

    private fun buildRun(commandId: String, outcome: WorkflowOutcome, startedAt: Long): ActionRun {
        val endedAt = clock.nowEpochMillis()
        val (status, errorCode) = statusAndError(outcome)
        return ActionRun(
            id = ids.newId(),
            commandId = commandId,
            startedAt = startedAt,
            endedAt = endedAt,
            status = status,
            errorCode = errorCode,
        )
    }

    /**
     * Map a [WorkflowOutcome] to a (status, errorCode) pair of stable codes. An
     * [WorkflowOutcome.AlreadyRun] is unwrapped to classify by the underlying outcome but is
     * reported with the [ActionRunStatus.ALREADY_RUN] status so a replay is distinguishable.
     */
    private fun statusAndError(outcome: WorkflowOutcome): Pair<String, String?> = when (outcome) {
        is WorkflowOutcome.Completed -> ActionRunStatus.SUCCESS to null
        is WorkflowOutcome.Cancelled -> ActionRunStatus.CANCELLED to null
        is WorkflowOutcome.Rejected ->
            ActionRunStatus.REJECTED to ActionRunErrorCode.WORKFLOW_REJECTED
        is WorkflowOutcome.PartialFailure ->
            ActionRunStatus.PARTIAL_FAILURE to classifyStepFailure(outcome)
        is WorkflowOutcome.AlreadyRun -> {
            // Classify by the cached outcome but mark the status as a replay.
            val (_, err) = statusAndError(outcome.outcome)
            ActionRunStatus.ALREADY_RUN to err
        }
    }

    /**
     * Infer the failure CLASS of a partial failure from the outcome SHAPE - never from the
     * free-text reason. The failing step is the last recorded one (the runner stops on first
     * failure); an unknown-action failure is detected by the runner recording a step with no
     * registered handler, and a timeout vs generic failure is distinguished by the presence
     * of the word "timed out" ONLY as a shape signal set by the runner itself (a fixed,
     * content-free marker), falling back to the generic STEP_FAILED code otherwise.
     */
    private fun classifyStepFailure(outcome: WorkflowOutcome.PartialFailure): String {
        val reason = outcome.failedReason
        return when {
            // The runner composes these reasons itself from the ActionType + fixed words
            // (see WorkflowRunner): they contain no user content, only the action type name
            // and a fixed phrase, so matching on the fixed phrase is a safe shape signal.
            reason.startsWith("Unknown action type") -> ActionRunErrorCode.UNKNOWN_ACTION
            reason.contains("timed out") -> ActionRunErrorCode.STEP_TIMEOUT
            else -> ActionRunErrorCode.STEP_FAILED
        }
    }
}
