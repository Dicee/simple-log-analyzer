@file:OptIn(ExperimentalSerializationApi::class)

package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.DEFAULT_PENDING_FILES_PER_LOG_GROUP
import com.simpleloganalyzer.agent.config.LogPollerConfig
import com.simpleloganalyzer.agent.config.LogPollerConfigParser
import com.simpleloganalyzer.agent.config.LogStreamResolver
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Paths
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("log-agent")

@Command(
    name = "log-agent",
    description = ["Log ingestion agent"],
    mixinStandardHelpOptions = true
)
@ExperimentalSerializationApi
class LogAgentCommand : Runnable {
    @Option(
        names = ["--max-files"],
        description = [
            "Maximum number of files allowed to match a log group's glob pattern.",
            "Acts as a safety guard against misconfigured globs that accidentally match a large number of files.",
            "Must be a positive integer. (default: \${DEFAULT-VALUE})"
        ]
    )
    var maxFiles: Int = DEFAULT_PENDING_FILES_PER_LOG_GROUP

    @Option(
        names = ["--config"],
        description = ["Path to the TOML configuration file"],
        required = true,
    )
    var configPath: String = ""

    @Option(
        names = ["--log-stream-name"],
        description = [
            "Optional log stream name to use for all publishing by this session of the agent across all log groups.",
            "If missing, a default one will be obtained through ",
        ],
    )
    var logStreamName: String? = null

    override fun run() {
        log.info("log-agent starting")
        log.info("Working directory: ${System.getProperty("user.dir")}")
        val configFile = Paths.get(configPath).toAbsolutePath()
        log.info("Looking for config at: $configFile")

        runBlocking {
            val logStreamResolver = LogStreamResolver.defaultChainResolver(logStreamName)
            val logPoller = LogPoller(
                logGroupConfigs = LogPollerConfigParser.parse(configFile),
                pollerConfig = LogPollerConfig(logStreamResolver = logStreamResolver, maxPendingFilesPerLogGroup = maxFiles),
                ingestionServiceClient = LoggingIngestionServiceClient(),
            )

            Runtime.getRuntime().addShutdownHook(Thread {
                println("SIGTERM received, stopping poller...")
                coroutineContext.cancel()
                logPoller.close()
            })

            logPoller.start(scope = this)
            awaitCancellation()
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(LogAgentCommand()).execute(*args))
}

