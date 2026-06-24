package com.simpleloganalyzer.testcommons.time

import com.simpleloganalyzer.commons.time.TickerClock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A controllable [TickerClock] for tests. Time does not advance automatically;
 * call [advanceBy] to move both the wall-clock instant and the nano ticker forward together.
 */
class FakeTickerClock(initialInstant: Instant = Instant.fromEpochMilliseconds(0)) : TickerClock {
    private var currentInstant = initialInstant
    private var nanos = 0L

    fun advanceBy(duration: Duration): FakeTickerClock {
        currentInstant += duration
        nanos += duration.inWholeNanoseconds
        return this
    }

    override fun now(): Instant = currentInstant
    override fun readNanos(): Long = nanos
}
