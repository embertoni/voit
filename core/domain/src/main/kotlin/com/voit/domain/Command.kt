package com.voit.domain

/** How a command must be confirmed before its actions run. */
enum class ConfirmationPolicy {
    /** No confirmation once a confident trigger match is found. */
    NONE,

    /** Confirm only when the target is ambiguous (multiple candidate matches). */
    IF_AMBIGUOUS,

    /** Always require an explicit confirmation step. */
    ALWAYS,
}

/**
 * Declarative description of an argument a command accepts.
 *
 * @param name slot name.
 * @param type expected value kind.
 * @param required whether the argument must be present for the command to be valid.
 */
data class ArgumentSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = false,
)

/** The kind of a declared argument, mirroring [ParamValue] variants. */
enum class ParamType {
    TEXT,
    NUMBER,
    BOOL,
    DURATION,
    TIMESTAMP,
    TAGS,
}

/** The full argument schema for a command. */
data class ArgumentSchema(val specs: List<ArgumentSpec> = emptyList())

/**
 * A user- or system-defined command: a set of trigger phrases mapped to an intent and a
 * bounded sequence of allow-listed actions.
 *
 * @param id stable UUID string.
 * @param triggerPhrases phrases (and aliases) that invoke this command.
 * @param intent canonical intent name this command resolves to.
 * @param actions the ordered action requests to run (bounded by the workflow runner).
 */
data class CommandDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val triggerPhrases: List<String> = emptyList(),
    val intent: String,
    val argumentSchema: ArgumentSchema = ArgumentSchema(),
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.IF_AMBIGUOUS,
    val actions: List<ActionRequest> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
