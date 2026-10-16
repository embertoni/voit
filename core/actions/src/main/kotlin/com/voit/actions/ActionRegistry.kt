package com.voit.actions

import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.RiskLevel

/**
 * The authorization decision for an action request. The registry/policy is the ONLY
 * authorizer: a model may propose an [ActionRequest], but only [ActionRegistry] decides
 * whether it is allowed, needs confirmation, or must present a choice.
 */
sealed interface AuthorizationDecision {
    /** The action may run immediately. */
    data class Authorized(val request: ActionRequest) : AuthorizationDecision

    /** The action may run only after the user explicitly confirms. */
    data class NeedsConfirmation(val request: ActionRequest, val reason: String) : AuthorizationDecision

    /** Multiple candidate targets matched; present [choices], never auto-pick. */
    data class NeedsChoice(val request: ActionRequest, val choices: List<String>) : AuthorizationDecision

    /** The request is not allowed (unknown type or failed validation). */
    data class Rejected(val reason: String) : AuthorizationDecision
}

/**
 * Thrown when execution is attempted on an unknown action type. The registry rejects
 * unknown actions rather than silently ignoring them.
 */
class UnknownActionException(type: ActionType) :
    IllegalArgumentException("No handler registered for action type $type")

/**
 * Typed, allow-listed registry of action handlers plus the confirmation policy.
 *
 * Confirmation policy (per spec):
 * - LOW risk, reversible  -> no confirmation after a confident match.
 * - MEDIUM (append/update/archive/etc.) -> confirm only if the target is ambiguous.
 * - HIGH (delete/bulk/export/share/permission/privacy) -> explicit confirmation always.
 * - Multiple candidate targets -> return choices, never auto-pick.
 */
class ActionRegistry(handlers: List<ActionHandler> = DefaultHandlers.all()) {

    private val handlers: Map<ActionType, ActionHandler> = handlers.associateBy { it.type }

    fun handlerFor(type: ActionType): ActionHandler? = handlers[type]

    fun isAllowed(type: ActionType): Boolean = handlers.containsKey(type)

    val registeredTypes: Set<ActionType> get() = handlers.keys

    /**
     * Authorizes [request] under the confirmation policy.
     *
     * Ambiguity / target-resolution contract: this registry does NOT resolve targets. It
     * only knows an action's type is ambiguous because the CALLER has already resolved the
     * referenced target against the user's data and passed the matching candidates in
     * [candidateTargets]. A `NeedsChoice` decision is therefore returned ONLY when the
     * caller supplies more than one candidate; passing an empty list asserts "the target
     * is unambiguous (or there is no target)". Callers that handle references to existing
     * notes/tasks MUST perform that resolution and pass the candidates, otherwise a
     * genuinely ambiguous reference silently skips the choice gate. (See docs/decisions.md,
     * review-driven changes.)
     *
     * @param matchConfidence confidence from the interpreter. It can gate a LOW- or
     *        MEDIUM-risk auto action (a low-confidence mutation falls through to
     *        `NeedsConfirmation`) but can NEVER by itself authorize a HIGH /
     *        confirmation-mandatory action.
     * @param candidateTargets the caller-resolved candidate targets for an ambiguous
     *        reference. More than one -> `NeedsChoice` (never auto-pick); empty or single
     *        -> treated as unambiguous.
     * @param confirmed whether the user has already explicitly confirmed this request.
     */
    fun authorize(
        request: ActionRequest,
        matchConfidence: Double = 1.0,
        candidateTargets: List<String> = emptyList(),
        confirmed: Boolean = false,
    ): AuthorizationDecision {
        val handler = handlers[request.type]
            ?: return AuthorizationDecision.Rejected("Unknown action type ${request.type}")

        when (val v = handler.validate(request)) {
            is ValidationResult.Invalid -> return AuthorizationDecision.Rejected(v.reason)
            ValidationResult.Valid -> Unit
        }

        // Multiple matches -> never auto-pick.
        if (candidateTargets.size > 1 && !confirmed) {
            return AuthorizationDecision.NeedsChoice(request, candidateTargets)
        }

        // High risk / confirmation-mandatory -> explicit confirmation, confidence cannot override.
        if (handler.risk == RiskLevel.HIGH || handler.confirmationMandatory) {
            return if (confirmed) {
                AuthorizationDecision.Authorized(request)
            } else {
                AuthorizationDecision.NeedsConfirmation(request, "High-risk action requires explicit confirmation")
            }
        }

        // Medium risk -> confirm if the target is ambiguous (handled above) OR the match
        // is low-confidence. A MEDIUM mutation must be at least as well-gated as LOW risk,
        // so a low-confidence match falls through to NeedsConfirmation rather than
        // authorizing outright.
        if (handler.risk == RiskLevel.MEDIUM) {
            return if (confirmed || matchConfidence >= MEDIUM_RISK_CONFIDENCE_THRESHOLD) {
                AuthorizationDecision.Authorized(request)
            } else {
                AuthorizationDecision.NeedsConfirmation(request, "Low-confidence mutation requires confirmation")
            }
        }

        // Low risk reversible -> auto after a confident match.
        return if (confirmed || matchConfidence >= LOW_RISK_CONFIDENCE_THRESHOLD) {
            AuthorizationDecision.Authorized(request)
        } else {
            AuthorizationDecision.NeedsConfirmation(request, "Low-confidence match requires confirmation")
        }
    }

    /** Validates then executes. Throws [UnknownActionException] for unknown types. */
    suspend fun execute(request: ActionRequest): ActionResult {
        val handler = handlers[request.type] ?: throw UnknownActionException(request.type)
        when (val v = handler.validate(request)) {
            is ValidationResult.Invalid -> throw IllegalArgumentException("Invalid action request: ${v.reason}")
            ValidationResult.Valid -> Unit
        }
        return handler.execute(request)
    }

    companion object {
        const val LOW_RISK_CONFIDENCE_THRESHOLD = 0.6

        /**
         * Minimum confidence for a MEDIUM-risk mutation to auto-authorize. A mutation is
         * no weaker a gate than a LOW-risk read, so it shares the same threshold.
         */
        const val MEDIUM_RISK_CONFIDENCE_THRESHOLD = 0.6
    }
}
