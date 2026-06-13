package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.DEFAULT_PENDING_FILES_PER_LOG_GROUP
import com.simpleloganalyzer.agent.config.LogPollerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime

private val log = LoggerFactory.getLogger("log-agent")

private const val PROP_MAX_FILES = "poller.maxPendingFilesPerLogGroup"

val loomIo: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor()
        .asCoroutineDispatcher()

@ExperimentalTime
fun main() {
    val maxFiles = parseMaxFiles()
    log.info("log-agent starting")

    runBlocking {
        val logPoller = LogPoller(
            logGroupConfigs = mapOf(),
            pollerConfig = LogPollerConfig(maxPendingFilesPerLogGroup = maxFiles),
            ingestionServiceClient = DummyLogIngestionServiceClient(),
        )

        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("SIGTERM received, stopping poller...")
            coroutineContext.cancel()
            logPoller.close()
        })

        logPoller.start(scope = this)
    }
    log.info("log-agent stopped")
}

// for now very simple, if we need more arguments we can use a real CLI arg parsing library
private fun parseMaxFiles(): Int {
    val raw = System.getProperty(PROP_MAX_FILES) ?: return DEFAULT_PENDING_FILES_PER_LOG_GROUP
    val value = raw.toIntOrNull()
    if (value == null || value < 1) {
        System.err.println("""
            Invalid value for -D$PROP_MAX_FILES: '$raw'

            Usage: java -D$PROP_MAX_FILES=<n> -jar log-agent.jar

              $PROP_MAX_FILES  (default: $DEFAULT_PENDING_FILES_PER_LOG_GROUP)
                Maximum number of files allowed to match a log group's glob pattern.
                Acts as a safety guard against misconfigured globs that accidentally
                match a large number of files. Must be a positive integer.
        """.trimIndent())
        exitProcess(1)
    }
    return value
}

fun tailFile(path: Path): Flow<String> = flow {
    path.toFile().bufferedReader().use { reader ->
        while (currentCoroutineContext().isActive) {
//            val line = reader.readLine() ?: run { delay(100); null } ?: continue
            val line = reader.readLine()
            if (line == null) {
                run {
//                    println("\tWaiting...")
                    delay(100);
                }
            } else {
//                println("\tEmitting ${line}...")
                emit(line)
            }
        }
    }
}.flowOn(loomIo)

suspend fun runFile(path: Path, uploadCh: SendChannel<String>) = coroutineScope {
    tailFile(path)
//        .map { parse(it) }
//        .chunked(maxBatchSize)
        .flowOn(Dispatchers.Default)
        .collect { elt -> uploadCh.send(elt) }
}

