package com.voit.actions

import com.voit.domain.ActionPermission
import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.ParamType
import com.voit.domain.ParamValue
import com.voit.domain.RiskLevel

/** A single declared parameter in an action's schema. */
data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
)

/** The validated output of running a handler. */
data class ActionResult(
    val type: ActionType,
    val output: Map<String, ParamValue> = emptyMap(),
)

/** Outcome of validating an [ActionRequest] against a handler's schema. */
sealed interface ValidationResult {
    object Valid : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}

/**
 * A typed, allow-listed action handler.
 *
 * Every executable capability declares its full contract here: parameter [schema],
 * required [permissions], [risk] level, a [timeoutMillis], and whether confirmation is
 * [confirmationMandatory]. The registry (never a model) is the authority on execution.
 */
interface ActionHandler {
    val type: ActionType
    val schema: List<ParamSpec>
    val permissions: Set<ActionPermission>
    val risk: RiskLevel
    val timeoutMillis: Long
    val confirmationMandatory: Boolean

    /** Validates a request's args against [schema]. Default checks presence + types. */
    fun validate(request: ActionRequest): ValidationResult {
        if (request.type != type) {
            return ValidationResult.Invalid("Request type ${request.type} does not match handler $type")
        }
        for (spec in schema) {
            val value = request.args[spec.name]
            if (value == null) {
                if (spec.required) return ValidationResult.Invalid("Missing required arg '${spec.name}'")
                continue
            }
            if (!typeMatches(spec.type, value)) {
                return ValidationResult.Invalid("Arg '${spec.name}' expected ${spec.type} but was ${value::class.simpleName}")
            }
        }
        return ValidationResult.Valid
    }

    /** Executes the (already validated) request. */
    suspend fun execute(request: ActionRequest): ActionResult

    companion object {
        fun typeMatches(type: ParamType, value: ParamValue): Boolean = when (type) {
            ParamType.TEXT -> value is ParamValue.Text
            ParamType.NUMBER -> value is ParamValue.Number
            ParamType.BOOL -> value is ParamValue.Bool
            ParamType.DURATION -> value is ParamValue.Duration
            ParamType.TIMESTAMP -> value is ParamValue.Timestamp
            ParamType.TAGS -> value is ParamValue.Tags
        }
    }
}

/**
 * Convenience base for handlers that only echo their validated args as the result.
 * Phase 1 has no persistence yet, so handlers simply confirm acceptance; later phases
 * wire these to repositories/platform services behind the same contract.
 */
abstract class EchoActionHandler(
    override val type: ActionType,
    override val schema: List<ParamSpec>,
    override val permissions: Set<ActionPermission>,
    override val risk: RiskLevel,
    override val timeoutMillis: Long = 2_000L,
    override val confirmationMandatory: Boolean = false,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult =
        ActionResult(type = type, output = request.args)
}
