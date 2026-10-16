package com.voit.actions

import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.ParamValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActionRegistryTest {

    private val registry = ActionRegistry()

    @Test
    fun allMvpActionsAreRegistered() {
        assertEquals(ActionType.entries.toSet(), registry.registeredTypes)
    }

    @Test
    fun unknownActionIsRejectedByAuthorize() {
        // SEARCH_NOTES handler removed from a custom registry -> rejected.
        val custom = ActionRegistry(DefaultHandlers.all().filter { it.type != ActionType.SEARCH_NOTES })
        val decision = custom.authorize(ActionRequest(ActionType.SEARCH_NOTES, mapOf("query" to ParamValue.Text("x"))))
        assertTrue(decision is AuthorizationDecision.Rejected)
    }

    @Test
    fun unknownActionThrowsOnExecute() = runTest {
        val custom = ActionRegistry(DefaultHandlers.all().filter { it.type != ActionType.LIST_TASKS })
        assertFailsWith<UnknownActionException> {
            custom.execute(ActionRequest(ActionType.LIST_TASKS))
        }
    }

    @Test
    fun malformedRequestMissingRequiredArgIsRejected() {
        // CREATE_TASK requires a title.
        val decision = registry.authorize(ActionRequest(ActionType.CREATE_TASK, emptyMap()))
        assertTrue(decision is AuthorizationDecision.Rejected)
    }

    @Test
    fun wrongTypedArgIsRejected() {
        // START_TIMER expects a Duration; pass Text instead.
        val decision = registry.authorize(
            ActionRequest(ActionType.START_TIMER, mapOf("duration" to ParamValue.Text("soon"))),
        )
        assertTrue(decision is AuthorizationDecision.Rejected)
    }

    @Test
    fun highRiskRequiresExplicitConfirmationEvenAtFullConfidence() {
        val export = ActionRequest(ActionType.EXPORT_NOTE, mapOf("noteId" to ParamValue.Text("n1")))
        val decision = registry.authorize(export, matchConfidence = 1.0, confirmed = false)
        assertTrue(decision is AuthorizationDecision.NeedsConfirmation)
    }

    @Test
    fun highRiskAuthorizedOnlyAfterConfirmation() {
        val export = ActionRequest(ActionType.EXPORT_NOTE, mapOf("noteId" to ParamValue.Text("n1")))
        val decision = registry.authorize(export, confirmed = true)
        assertTrue(decision is AuthorizationDecision.Authorized)
    }

    @Test
    fun lowRiskAutoAuthorizedWhenConfident() {
        val decision = registry.authorize(
            ActionRequest(ActionType.SEARCH_NOTES, mapOf("query" to ParamValue.Text("milk"))),
            matchConfidence = 0.95,
        )
        assertTrue(decision is AuthorizationDecision.Authorized)
    }

    @Test
    fun lowRiskLowConfidenceNeedsConfirmation() {
        val decision = registry.authorize(
            ActionRequest(ActionType.SEARCH_NOTES, mapOf("query" to ParamValue.Text("milk"))),
            matchConfidence = 0.3,
        )
        assertTrue(decision is AuthorizationDecision.NeedsConfirmation)
    }

    @Test
    fun ambiguousTargetReturnsChoicesNeverAutoPick() {
        val req = ActionRequest(ActionType.APPEND_TO_NOTE, mapOf(
            "noteId" to ParamValue.Text("?"),
            "body" to ParamValue.Text("add this"),
        ))
        val decision = registry.authorize(req, candidateTargets = listOf("n1", "n2"))
        assertTrue(decision is AuthorizationDecision.NeedsChoice)
        assertEquals(listOf("n1", "n2"), (decision as AuthorizationDecision.NeedsChoice).choices)
    }

    @Test
    fun mediumRiskSingleTargetAuthorized() {
        val req = ActionRequest(ActionType.ARCHIVE_NOTE, mapOf("noteId" to ParamValue.Text("n1")))
        val decision = registry.authorize(req, candidateTargets = listOf("n1"))
        assertTrue(decision is AuthorizationDecision.Authorized)
    }

    @Test
    fun mediumRiskLowConfidenceNeedsConfirmation() {
        // A MEDIUM-risk mutation must be at least as well-gated as a LOW-risk read: a
        // low-confidence match falls through to confirmation even with an unambiguous target.
        val req = ActionRequest(ActionType.ARCHIVE_NOTE, mapOf("noteId" to ParamValue.Text("n1")))
        val decision = registry.authorize(req, matchConfidence = 0.3)
        assertTrue(decision is AuthorizationDecision.NeedsConfirmation)
    }

    @Test
    fun mediumRiskConfidentMatchAuthorized() {
        val req = ActionRequest(ActionType.ARCHIVE_NOTE, mapOf("noteId" to ParamValue.Text("n1")))
        val decision = registry.authorize(req, matchConfidence = 0.9)
        assertTrue(decision is AuthorizationDecision.Authorized)
    }

    @Test
    fun executeReturnsEchoedArgs() = runTest {
        val req = ActionRequest(ActionType.SPEAK_RESPONSE, mapOf("text" to ParamValue.Text("hello")))
        val result = registry.execute(req)
        assertEquals(ActionType.SPEAK_RESPONSE, result.type)
        assertEquals(ParamValue.Text("hello"), result.output["text"])
    }
}
