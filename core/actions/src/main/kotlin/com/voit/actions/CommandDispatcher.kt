package com.voit.actions

import com.voit.domain.CommandDefinition
import com.voit.domain.ConfirmationPolicy

/**
 * The PURE, device-independent execution path for user-defined [CommandDefinition]s - the
 * Phase-5 piece that finally connects a stored command to the bounded [WorkflowRunner].
 *
 * Before Phase 5 the pieces existed but were orphaned: the editor persisted commands and
 * the [WorkflowRunner] could run a [Workflow], but NOTHING matched a spoken/typed transcript
 * against a command's trigger phrases and dispatched its actions. `CommandDispatcher` is that
 * missing seam:
 *
 *  1. match the transcript against the enabled commands' trigger phrases (whole-word,
 *     case-insensitive - the same matching discipline the IntentParser uses so an incidental
 *     fragment never fires a command);
 *  2. apply the command's [ConfirmationPolicy] (NONE / IF_AMBIGUOUS / ALWAYS) together with
 *     the [ActionRegistry] authorization of each action (the registry stays the SOLE
 *     authorizer - a HIGH / confirmation-mandatory action is ALWAYS gated regardless of the
 *     command's policy);
 *  3. on a clear, authorized match build a bounded [Workflow] from the command's ordered
 *     actions and run it through the [WorkflowRunner] (so MAX_STEPS, per-step timeout,
 *     cancellation, and idempotency all still apply).
 *
 * It contains NO Android, NO network, NO persistence, and NO model: it takes the command
 * list as an argument (the caller loads it from the repository) and returns a result the
 * caller persists/audits. Fully unit-testable on the JVM.
 */
class CommandDispatcher(
    private val registry: ActionRegistry,
    private val runner: WorkflowRunner,
) {

    /**
     * Attempt to dispatch [transcript] against [commands].
     *
     * @param commands the candidate commands (caller passes the stored set; disabled ones
     *   are filtered out here so the caller need not pre-filter).
     * @param transcript the user utterance / typed text to match.
     * @param confirmed whether the user has already explicitly confirmed this dispatch (set
     *   on the second call after a [CommandDispatchResult.NeedsConfirmation]).
     * @param idempotencyKey optional stable key so a retried dispatch is de-duplicated by the
     *   runner; defaults to the matched command id so re-issuing the same command is safe.
     */
    suspend fun dispatch(
        commands: List<CommandDefinition>,
        transcript: String,
        confirmed: Boolean = false,
        idempotencyKey: String? = null,
    ): CommandDispatchResult {
        val matches = matchingCommands(commands, transcript)

        if (matches.isEmpty()) {
            return CommandDispatchResult.NoMatch
        }
        // More than one command matched the transcript -> never auto-pick; present a choice.
        if (matches.size > 1 && !confirmed) {
            return CommandDispatchResult.NeedsChoice(matches.map { it.id })
        }

        val command = matches.first()

        if (command.actions.isEmpty()) {
            return CommandDispatchResult.Rejected(command.id, "Command has no actions")
        }
        if (command.actions.size > WorkflowRunner.MAX_STEPS) {
            return CommandDispatchResult.Rejected(
                command.id,
                "Command exceeds max ${WorkflowRunner.MAX_STEPS} actions (${command.actions.size})",
            )
        }

        // Authorize every action through the registry (the sole authorizer). This both
        // rejects unknown/invalid actions and surfaces the gating each action requires.
        val decisions = command.actions.map { request ->
            registry.authorize(request, confirmed = confirmed)
        }

        decisions.firstNotNullOfOrNull { it as? AuthorizationDecision.Rejected }?.let {
            return CommandDispatchResult.Rejected(command.id, it.reason)
        }

        // Does any action, or the command's own policy, require explicit confirmation?
        val anyActionNeedsConfirmation =
            decisions.any { it is AuthorizationDecision.NeedsConfirmation }
        val policyRequiresConfirmation = when (command.confirmationPolicy) {
            ConfirmationPolicy.ALWAYS -> true
            // IF_AMBIGUOUS: the ambiguity gate is the multi-command NeedsChoice above; a
            // single matched command is unambiguous, so the policy itself does not add a
            // confirmation here. (Per-target ambiguity inside an action is the registry's
            // NeedsChoice contract, which the caller resolves before dispatch.)
            ConfirmationPolicy.IF_AMBIGUOUS -> false
            ConfirmationPolicy.NONE -> false
        }

        if (!confirmed && (anyActionNeedsConfirmation || policyRequiresConfirmation)) {
            val reason = when {
                anyActionNeedsConfirmation ->
                    "Command contains an action that requires explicit confirmation"
                else -> "Command policy requires explicit confirmation"
            }
            return CommandDispatchResult.NeedsConfirmation(command.id, reason)
        }

        // Authorized (or confirmed): run the bounded workflow.
        val workflow = Workflow(
            steps = command.actions,
            idempotencyKey = idempotencyKey ?: command.id,
        )
        val outcome = runner.run(workflow)
        return CommandDispatchResult.Dispatched(command.id, outcome)
    }

    /**
     * The enabled commands whose trigger phrases match [transcript]. Matching is
     * case-insensitive and on whole-word boundaries: a trigger phrase must appear as a
     * standalone run of words in the transcript, so "note" never matches inside "notebook".
     * Empty/blank trigger phrases are ignored.
     */
    private fun matchingCommands(
        commands: List<CommandDefinition>,
        transcript: String,
    ): List<CommandDefinition> {
        val haystack = normalize(transcript)
        if (haystack.isBlank()) return emptyList()
        return commands.filter { command ->
            command.enabled && command.triggerPhrases.any { phrase ->
                val needle = normalize(phrase)
                needle.isNotBlank() && containsWholeWord(haystack, needle)
            }
        }
    }

    private fun normalize(text: String): String = text.trim().lowercase()

    /**
     * True when [needle] appears in [haystack] on whole-word boundaries (both already
     * normalized/lowercased). A boundary is the string edge or a space, so an incidental
     * fragment inside a longer word does not match - mirroring the IntentParser's
     * whole-word substring discipline recorded in docs/decisions.md.
     */
    private fun containsWholeWord(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) return false
            val beforeOk = idx == 0 || haystack[idx - 1] == ' '
            val end = idx + needle.length
            val afterOk = end == haystack.length || haystack[end] == ' '
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
    }
}

/** The outcome of a [CommandDispatcher.dispatch] attempt. */
sealed interface CommandDispatchResult {
    /** No enabled command's trigger phrases matched the transcript. */
    data object NoMatch : CommandDispatchResult

    /** Several commands matched; present [commandIds] and never auto-pick. */
    data class NeedsChoice(val commandIds: List<String>) : CommandDispatchResult

    /**
     * The matched command (or an action in it) requires explicit confirmation before it
     * runs. Re-call [CommandDispatcher.dispatch] with `confirmed = true` to proceed.
     */
    data class NeedsConfirmation(val commandId: String, val reason: String) : CommandDispatchResult

    /** The command could not be dispatched (unknown/invalid action, empty, or over the bound). */
    data class Rejected(val commandId: String, val reason: String) : CommandDispatchResult

    /** The command's workflow ran; [outcome] is the [WorkflowRunner]'s result. */
    data class Dispatched(val commandId: String, val outcome: WorkflowOutcome) : CommandDispatchResult
}
