package com.simpleloganalyzer.agent

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("log-agent")

fun main() {
    log.info("log-agent starting (dummy poller)")
    Poller().run()
}
