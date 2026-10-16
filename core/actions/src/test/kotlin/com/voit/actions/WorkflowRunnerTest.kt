package com.voit.actions

import com.voit.domain.ActionPermission
import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.ParamValue
import com.voit.domain.RiskLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowRunnerTest {

    private fun speak(text: String) =
        ActionRequest(ActionType.SPEAK_RESPONSE, mapOf("text" to ParamValue.Text(text)))

    @Test
    fun runsAllStepsInOrder() = runTest {
        val runner = WorkflowRunner(ActionRegistry())
        val wf = Workflow(listOf(speak("a"), speak("b"), speak("c")), idempotencyKey = "k1")
        val outcome = runner.run(wf)
        assertTrue(outcome is WorkflowOutcome.Completed)
        assertEquals(3, (outcome as WorkflowOutcome.Completed).steps.size)
        assertTrue(outcome.steps.all { it.succeeded })
    }

    @Test
    fun rejectsEmptyWorkflow() = runTest {
        val runner = WorkflowRunner(ActionRegistry())
        val outcome = runner.run(Workflow(emptyList(), "k-empty"))
        assertTrue(outcome is WorkflowOutcome.Rejected)
    }

    @Test
    fun rejectsMoreThanFiveSteps() = runTest {
        val runner = WorkflowRunner(ActionRegistry())
        val steps = List(6) { speak("step$it") }
        val outcome = runner.run(Workflow(steps, "k-6"))
        assertTrue(outcome is WorkflowOutcome.Rejected)
        assertTrue((outcome as WorkflowOutcome.Rejected).reason.contains("max"))
    }

    @Test
    fun exactlyFiveStepsAllowed() = runTest {
        val runner = WorkflowRunner(ActionRegistry())
        val steps = List(5) { speak("step$it") }
        val outcome = runner.run(Workflow(steps, "k-5"))
        assertTrue(outcome is WorkflowOutcome.Completed)
    }

    @Test
    fun cancellationStopsBeforeNextStep() = runTest {
        val token = CancellationToken().apply { cancel() }
        val runner = WorkflowRunner(ActionRegistry())
        val outcome = runner.run(Workflow(listOf(speak("a")), "k-cancel"), token)
        assertTrue(outcome is WorkflowOutcome.Cancelled)
        assertEquals(0, (outcome as WorkflowOutcome.Cancelled).steps.size)
    }

    @Test
    fun timeoutProducesPartialFailure() = runTest {
        val slow = object : ActionHandler {
            override val type = ActionType.SPEAK_RESPONSE
            override val schema = emptyList<ParamSpec>()
            override val permissions = emptySet<ActionPermission>()
            override val risk = RiskLevel.LOW
            override val timeoutMillis = 10L
            override val confirmationMandatory = false
            override suspend fun execute(request: ActionRequest): ActionResult {
                delay(10_000L) // far exceeds the 10ms timeout
                return ActionResult(type)
            }
        }
        val runner = WorkflowRunner(ActionRegistry(listOf(slow)))
        val outcome = runner.run(Workflow(listOf(speak("x")), "k-timeout"))
        assertTrue(outcome is WorkflowOutcome.PartialFailure)
        assertTrue((outcome as WorkflowOutcome.PartialFailure).failedReason.contains("timed out"))
    }

    @Test
    fun partialFailureStopsOnThrowingStep() = runTest {
        val boom = object : ActionHandler {
            override val type = ActionType.SPEAK_RESPONSE
            override val schema = emptyList<ParamSpec>()
            override val permissions = emptySet<ActionPermission>()
            override val risk = RiskLevel.LOW
            override val timeoutMillis = 1_000L
            override val confirmationMandatory = false
            override suspend fun execute(request: ActionRequest): ActionResult {
                throw IllegalStateException("boom")
            }
        }
        val runner = WorkflowRunner(ActionRegistry(listOf(boom)))
        val outcome = runner.run(Workflow(listOf(speak("x")), "k-boom"))
        assertTrue(outcome is WorkflowOutcome.PartialFailure)
        assertEquals("boom", (outcome as WorkflowOutcome.PartialFailure).failedReason)
    }

    @Test
    fun idempotencyKeyReturnsCachedOutcome() = runTest {
        val runner = WorkflowRunner(ActionRegistry())
        val wf = Workflow(listOf(speak("a")), "k-idem")
        val first = runner.run(wf)
        assertTrue(first is WorkflowOutcome.Completed)
        val second = runner.run(wf)
        assertTrue(second is WorkflowOutcome.AlreadyRun)
    }
}
