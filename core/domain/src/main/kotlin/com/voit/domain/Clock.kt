package com.voit.domain

/**
 * Injectable time provider so domain logic and tests are deterministic.
 *
 * [nowEpochMillis] returns a wall-clock timestamp in milliseconds since the Unix epoch.
 * Production code can use [SystemClock]; tests can use [FixedClock] or [MutableClock].
 */
fun interface Clock {
    fun nowEpochMillis(): Long
}

/** Real system clock, backed by [System.currentTimeMillis]. */
object SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

/** Deterministic clock pinned to a single instant. */
class FixedClock(private val instant: Long) : Clock {
    override fun nowEpochMillis(): Long = instant
}

/**
 * Deterministic clock whose value can be advanced by tests.
 * Not thread-safe; intended for single-threaded unit tests.
 */
class MutableClock(private var instant: Long = 0L) : Clock {
    override fun nowEpochMillis(): Long = instant

    fun advanceBy(millis: Long) {
        instant += millis
    }

    fun set(millis: Long) {
        instant = millis
    }
}
