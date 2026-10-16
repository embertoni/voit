package com.voit.actions

import com.voit.domain.ActionRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * A bounded workflow: an ordered list of action requests plus an idempotency key.
 *
 * The runner enforces [MAX_STEPS] (MVP cap) and disallows chaining loops. The
 * [idempotencyKey] lets a caller safely retry a workflow without re-running it.
 */
data class Workflow(
    val steps: List<ActionRequest>,
    val idempotencyKey: String,
)

/** The outcome of a single step. */
data class StepResult(
    val request: ActionRequest,
    val result: ActionResult?,
    val error: String?,
) {
    val succeeded: Boolean get() = result != null && error == null
}

/** The overall outcome of running a workflow. */
sealed interface WorkflowOutcome {
    data class Completed(val steps: List<StepResult>) : WorkflowOutcome

    /** A step failed; prior successful steps are reported in [steps]. */
    data class PartialFailure(val steps: List<StepResult>, val failedReason: String) : WorkflowOutcome

    data class Rejected(val reason: String) : WorkflowOutcome
    data class Cancelled(val steps: List<StepResult>) : WorkflowOutcome

    /** The workflow was already executed under this idempotency key; cached result returned. */
    data class AlreadyRun(val outcome: WorkflowOutcome) : WorkflowOutcome
}

/** Caller-controlled cancellation flag, checked before each step. */
class CancellationToken {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/**
 * Executes bounded workflows through the [ActionRegistry].
 *
 * Guarantees:
 * - rejects any workflow with more than [MAX_STEPS] steps,
 * - rejects empty workflows,
 * - checks a [CancellationToken] before each step,
 * - applies each handler's own timeout (via [withTimeout]),
 * - de-duplicates by idempotency key (a repeated key returns the cached outcome),
 * - stops on first failure and reports a [WorkflowOutcome.PartialFailure].
 */
class WorkflowRunner(private val registry: ActionRegistry) {

    private val completedKeys = mutableMapOf<String, WorkflowOutcome>()

    suspend fun run(
        workflow: Workflow,
        cancellation: CancellationToken = CancellationToken(),
    ): WorkflowOutcome {
        completedKeys[workflow.idempotencyKey]?.let { return WorkflowOutcome.AlreadyRun(it) }

        if (workflow.steps.isEmpty()) {
            return WorkflowOutcome.Rejected("Workflow has no steps")
        }
        if (workflow.steps.size > MAX_STEPS) {
            return WorkflowOutcome.Rejected("Workflow exceeds max $MAX_STEPS steps (${workflow.steps.size})")
        }

        val results = mutableListOf<StepResult>()
        for (step in workflow.steps) {
            if (cancellation.cancelled) {
                return WorkflowOutcome.Cancelled(results.toList())
            }
            if (!registry.isAllowed(step.type)) {
                val reason = "Unknown action type ${step.type}"
                results.add(StepResult(step, null, reason))
                val outcome = WorkflowOutcome.PartialFailure(results.toList(), reason)
                completedKeys[workflow.idempotencyKey] = outcome
                return outcome
            }
            val handler = registry.handlerFor(step.type)!!
            try {
                val result = withTimeout(handler.timeoutMillis) { registry.execute(step) }
                results.add(StepResult(step, result, null))
            } catch (e: TimeoutCancellationException) {
                val reason = "Step ${step.type} timed out after ${handler.timeoutMillis}ms"
                results.add(StepResult(step, null, reason))
                val outcome = WorkflowOutcome.PartialFailure(results.toList(), reason)
                completedKeys[workflow.idempotencyKey] = outcome
                return outcome
            } catch (e: Exception) {
                val reason = e.message ?: "Step ${step.type} failed"
                results.add(StepResult(step, null, reason))
                val outcome = WorkflowOutcome.PartialFailure(results.toList(), reason)
                completedKeys[workflow.idempotencyKey] = outcome
                return outcome
            }
        }

        val outcome = WorkflowOutcome.Completed(results.toList())
        completedKeys[workflow.idempotencyKey] = outcome
        return outcome
    }

    companion object {
        const val MAX_STEPS = 5
    }
}
