@file:OptIn(ExperimentalTime::class)

package com.simpleloganalyzer.agent.config

import com.simpleloganalyzer.testcommons.time.FakeTickerClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val NOW = Instant.fromEpochSeconds(17)
private const val UUID: String = "5759456d-0ad2-4a3b-ba06-5bcac4f33481"
private const val FALLBACK_STREAM_NAME = "1970-01-01T00:00:17Z-5759456d-0ad2-4a3b"

class LogStreamResolverTest {
    private lateinit var clock: Clock
    private lateinit var randomResolver: RandomLogStreamResolver

    @BeforeEach
    fun setUp() {
        clock = FakeTickerClock(NOW)
        randomResolver = RandomLogStreamResolver(clock, { UUID })
    }

    @Test
    fun testLogStreamResolverChain_empty() {
        val chain = newResolverChain(listOf())
        assertThat(chain.resolveLogStreamName()).isEqualTo(FALLBACK_STREAM_NAME)
    }

    @Test
    fun testLogStreamResolverChain_single_succeeds() {
        val chain = newResolverChain(listOf(ArgLogStreamResolver("hello")))
        assertThat(chain.resolveLogStreamName()).isEqualTo("hello")
    }

    @Test
    fun testLogStreamResolverChain_single_falls_back() {
        val chain = newResolverChain(listOf(ArgLogStreamResolver(null)))
        assertThat(chain.resolveLogStreamName()).isEqualTo(FALLBACK_STREAM_NAME)
    }

    @Test
    fun testLogStreamResolverChain_multiple_one_succeeds() {
        val chain = newResolverChain(listOf(
            ArgLogStreamResolver(null),
            ArgLogStreamResolver("hello"),
            ArgLogStreamResolver("world"),
        ))
        assertThat(chain.resolveLogStreamName()).isEqualTo("hello")
    }

    @Test
    fun testLogStreamResolverChain_multiple_falls_back() {
        val chain = newResolverChain(listOf(
            ArgLogStreamResolver(null),
            ArgLogStreamResolver(null),
        ))
        assertThat(chain.resolveLogStreamName()).isEqualTo(FALLBACK_STREAM_NAME)
    }

    @Test
    fun testHostLogStreamResolver_success() {
        val resolver = HostnameLogStreamResolver { InetAddress.getByName("localhost") }
        assertThat(resolver.resolveLogStreamName()).isEqualTo("localhost")
    }

    @Test
    fun testHostLogStreamResolver_unknownHost() {
        val resolver = HostnameLogStreamResolver { throw UnknownHostException("Oppsy poopsy") }
        assertThat(resolver.resolveLogStreamName()).isNull()
    }

    private fun newResolverChain(resolvers: List<ArgLogStreamResolver>): LogStreamResolverChain =
        LogStreamResolverChain(resolvers = resolvers, fallbackResolver = randomResolver)
}
