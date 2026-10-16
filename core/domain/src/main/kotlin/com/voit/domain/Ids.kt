package com.voit.domain

import java.util.UUID

/**
 * Injectable identifier generator so domain logic and tests are deterministic.
 */
fun interface IdGenerator {
    fun newId(): String
}

/** Real id generator, backed by random UUIDs. */
object UuidGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Deterministic id generator that emits a predictable, monotonically numbered
 * sequence (e.g. "id-1", "id-2"). Intended for unit tests.
 */
class SequentialIdGenerator(private val prefix: String = "id-") : IdGenerator {
    private var counter = 0L
    override fun newId(): String = "$prefix${++counter}"
}
