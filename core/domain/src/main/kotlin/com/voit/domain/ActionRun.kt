package com.voit.domain

/**
 * A REDACTED audit record of a single command/workflow run.
 *
 * PRIVACY INVARIANT (enforced by the schema and re-affirmed here): an action-run record
 * holds ONLY stable codes and ids - never a transcript, note/task content, or raw audio.
 * [status], [resultCode], and [errorCode] are drawn from the fixed vocabularies below so a
 * diagnostics/audit trail can never leak user content (consistent with docs/privacy.md
 * "Diagnostics use codes, not content").
 *
 * @param id stable UUID string for this run.
 * @param commandId the [CommandDefinition.id] that was dispatched, or null for an ad-hoc run.
 * @param startedAt epoch millis when the run began.
 * @param endedAt epoch millis when the run finished, or null if still running / not recorded.
 * @param status a terminal [ActionRunStatus] code.
 * @param resultCode an optional refining result code (currently reserved; null by default).
 * @param errorCode an optional stable [ActionRunErrorCode] when the run did not fully succeed.
 */
data class ActionRun(
    val id: String,
    val commandId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val resultCode: String? = null,
    val errorCode: String? = null,
)

/** The fixed vocabulary of terminal run statuses (codes only, never content). */
object ActionRunStatus {
    /** Every step completed successfully. */
    const val SUCCESS: String = "SUCCESS"

    /** A step failed (or timed out); earlier steps may have succeeded. */
    const val PARTIAL_FAILURE: String = "PARTIAL_FAILURE"

    /** The run was cancelled before completing. */
    const val CANCELLED: String = "CANCELLED"

    /** The workflow was refused before any step ran (empty, over the step bound, etc.). */
    const val REJECTED: String = "REJECTED"

    /** A prior run with the same idempotency key had already executed. */
    const val ALREADY_RUN: String = "ALREADY_RUN"
}

/**
 * The fixed vocabulary of redacted error codes. These intentionally describe the CLASS of
 * failure (timeout, unknown action, generic step failure) and never carry the underlying
 * exception message, which could echo user content.
 */
object ActionRunErrorCode {
    /** A step exceeded its handler timeout. */
    const val STEP_TIMEOUT: String = "STEP_TIMEOUT"

    /** A step's action type was not in the allow-list. */
    const val UNKNOWN_ACTION: String = "UNKNOWN_ACTION"

    /** A step's handler threw during execution. */
    const val STEP_FAILED: String = "STEP_FAILED"

    /** The workflow was refused before execution (bounds/empty/validation). */
    const val WORKFLOW_REJECTED: String = "WORKFLOW_REJECTED"
}

/**
 * Persistence boundary for the redacted [ActionRun] audit trail. Mirrors the other
 * repository interfaces; the Room-backed impl lives in core:data over the existing
 * `action_runs` table. The trail is append-only plus a user-initiated [clear].
 */
interface ActionRunRepository {
    /** Append a redacted run record. */
    suspend fun record(run: ActionRun)

    /** All recorded runs, most recent first. */
    suspend fun history(): List<ActionRun>

    /** User-initiated wipe of the audit trail (a delete-my-data entry point). */
    suspend fun clear()
}
