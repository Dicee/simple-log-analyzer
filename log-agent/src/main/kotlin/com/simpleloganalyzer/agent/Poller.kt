package com.simpleloganalyzer.agent

import org.slf4j.LoggerFactory

class Poller {
    private val log = LoggerFactory.getLogger(Poller::class.java)

    fun run() {
        // TODO: implement file tailing and batched PUT to ingestion service
        log.info("poller stub — nothing to do yet")
    }
}
