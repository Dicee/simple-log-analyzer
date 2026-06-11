@file:OptIn(ExperimentalTime::class)

package com.simpleloganalyzer.commons.time

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A [Clock] that also exposes elapsed nanoseconds as a monotonic ticker, suitable for
 * driving time-sensitive caches (e.g. Caffeine via `.ticker { tickerClock.readNanos() }`).
 */
interface TickerClock : Clock {
    fun readNanos(): Long
}

/** Production implementation backed by [Clock.System] and [System.nanoTime]. */
object SystemTickerClock : TickerClock {
    override fun now(): Instant = Clock.System.now()
    override fun readNanos(): Long = System.nanoTime()
}
