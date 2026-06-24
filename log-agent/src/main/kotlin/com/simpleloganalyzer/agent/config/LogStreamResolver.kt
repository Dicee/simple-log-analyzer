package com.simpleloganalyzer.agent.config

import com.simpleloganalyzer.commons.logging.log
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*
import kotlin.time.Clock
import kotlin.time.Instant

interface LogStreamResolver {
    fun resolveLogStreamName(): String?

    companion object {
        fun defaultChainResolver(programArg: String? = null) = LogStreamResolverChain(resolvers = listOf(
            ArgLogStreamResolver(programArg),
            EnvironmentLogStreamResolver(),
            HostnameLogStreamResolver(),
        ))
    }
}

class LogStreamResolverChain(
    private val resolvers: List<LogStreamResolver> = listOf(),
    private val fallbackResolver: RandomLogStreamResolver = RandomLogStreamResolver(),
) : LogStreamResolver {
    override fun resolveLogStreamName(): String =
        resolvers.firstNotNullOfOrNull { it.resolveLogStreamName() } ?: fallbackResolver.resolveLogStreamName()
}

class ArgLogStreamResolver(private val argValue: String?) : LogStreamResolver {
    override fun resolveLogStreamName(): String? = argValue
}

class EnvironmentLogStreamResolver : LogStreamResolver {
    override fun resolveLogStreamName(): String? = try {
        System.getenv("log.poller.logStreamName")
    } catch (_: RuntimeException) {
        null
    }
}

class HostnameLogStreamResolver internal constructor(
    private val getLocalHost: () -> InetAddress = { InetAddress.getLocalHost() }
): LogStreamResolver {
    override fun resolveLogStreamName(): String? {
        try {
            return getLocalHost().hostName
        } catch (e: UnknownHostException) {
            log.warn("Failed determining current hostname", e)
            return null
        }
    }
}

class RandomLogStreamResolver internal constructor(
    private val clock: Clock = Clock.System,
    private val randomStringGenerator: () -> String = { UUID.randomUUID().toString() }
) : LogStreamResolver {
    override fun resolveLogStreamName(): String {
        val now = Instant.fromEpochSeconds(clock.now().epochSeconds) // truncate nanos
        val uuid = randomStringGenerator()
        return "$now-${uuid.substring(0 until 18)}"
    }
}